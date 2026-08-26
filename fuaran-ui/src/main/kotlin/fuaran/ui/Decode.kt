// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.ui

/**
 * The render-projection decoder: canonical tree JSON — as read back from a session's
 * `tree_json` — into the sealed [Node] model. Decode-only by design; there is no
 * canonical encoder.
 *
 * Every `$type` dispatch throws [FuaranDecodeException] on an unrecognised
 * discriminator rather than silently absorbing it into a catch-all case, so a wire kind
 * the model doesn't cover surfaces loudly. Closure-typed slots (`"<closure>"`) at
 * optional positions are simply not read; host-opaque payloads (`Static.value`,
 * `Transform.source`) are held as raw [JsonValue].
 */

/** Decode a canonical wire `Node` JSON string into the typed tree. */
fun decodeNode(json: String): Node {
    NodeWalk.beginDocument()
    val root =
        try {
            Json.parse(json)
        } catch (e: JsonSyntaxException) {
            throw FuaranDecodeException(FuaranDecodeException.INVALID_JSON, "$", e.message ?: "invalid JSON")
        } catch (e: JsonLimitException) {
            // The two are NOT interchangeable: a limit breach is well-formed input that
            // is merely too large to walk, and reporting it as INVALID_JSON is the one
            // diagnosis the format explicitly forbids. Only the reader can tell them
            // apart, which is why it raises two types rather than one with a flag.
            throw FuaranDecodeException(FuaranDecodeException.LIMIT_EXCEEDED, "$", e.message ?: "resource limit exceeded")
        }
    return decodeNode(root, "$")
}

// --------------------------------------------------------------------------- //
// JSON accessor helpers
// --------------------------------------------------------------------------- //

private fun JsonValue.obj(path: String): JsonObject =
    this as? JsonObject
        ?: throw FuaranDecodeException(FuaranDecodeException.WRONG_TYPE, path, "expected object")

/**
 * Lenient AI-ingest (WIRE_FORMAT 3.6, generalised): a well-formed `Static` envelope wrapped
 * around a PLAIN scalar unwraps before every scalar reader — the inverse of the
 * bare-scalar-in-a-Binding-slot confusion, applied at every plain-scalar position in ONE place
 * rather than site by site. An object that is not a well-formed `Static` envelope passes through
 * untouched and fails with the ordinary error.
 *
 * Applied to the scalar readers only. An `array`/`obj` slot is never unwrapped: those are
 * structural positions where the envelope has a second reading.
 */
private fun JsonValue.unwrapStaticEnvelope(): JsonValue =
    if (this is JsonObject && (this["\$type"] as? JsonString)?.value == "Static") {
        this["value"] ?: JsonNull
    } else {
        this
    }

private fun JsonValue.str(path: String): String =
    (unwrapStaticEnvelope() as? JsonString)?.value
        ?: throw FuaranDecodeException(FuaranDecodeException.WRONG_TYPE, path, "expected string")

/**
 * The three non-finite spellings a FLOAT slot accepts beside a JSON number (WIRE_FORMAT 7).
 * JSON has no non-finite number literal, so the wire spells the three IEEE-754 values as
 * strings — and as EXACTLY these strings, case-sensitive.
 *
 * Matched by map lookup (`equals`) against the literals, never by a parse, and that is
 * load-bearing on this platform rather than a stylistic choice. `"nan".toDoubleOrNull()`
 * returns NaN on the JVM, because `Double.parseDouble` is case-INSENSITIVE — and it also
 * accepts `"Infinity"` with a leading `+`, the `1d` / `2.5f` type suffixes, hex literals like
 * `0x1p3`, and leading/trailing whitespace. A parse-based reader would therefore admit a whole
 * family of spellings the format refuses, silently and with the value preserved; the corpus
 * pins the smallest of them (`"nan"` at a float slot) as WRONG_TYPE. The SET is the contract,
 * so there is no parse left to get wrong.
 *
 * An INTEGER slot has no counterpart. `requireInt`'s vocabulary is JSON numbers alone — there
 * is no non-finite integer, so `"NaN"` at an int slot is WRONG_TYPE, not a sentinel.
 */
private val FLOAT_SENTINELS: Map<String, Double> =
    mapOf(
        "NaN" to Double.NaN,
        "Infinity" to Double.POSITIVE_INFINITY,
        "-Infinity" to Double.NEGATIVE_INFINITY,
    )

/**
 * An INTEGER slot: a JSON number, truncated by an integer cast. Deliberately narrower than
 * [double] — see [FLOAT_SENTINELS].
 *
 * The discrimination is on the JSON AST's own case, never on a widening numeric conversion, so
 * a `true` cannot reach a numeric slot by way of a boolean-to-number coercion the platform
 * would happily perform.
 */
private fun JsonValue.int(path: String): Int =
    (unwrapStaticEnvelope() as? JsonNumber)?.toInt()
        ?: throw FuaranDecodeException(
            FuaranDecodeException.WRONG_TYPE,
            path,
            "expected a JSON number (an integer slot has no non-finite form, so the 'NaN' / " +
                "'Infinity' / '-Infinity' sentinels are not accepted here)",
        )

/** A FLOAT slot: a JSON number, or one of the three exact sentinel strings ([FLOAT_SENTINELS]). */
private fun JsonValue.double(path: String): Double =
    when (val v = unwrapStaticEnvelope()) {
        is JsonNumber -> v.toDouble()
        is JsonString ->
            FLOAT_SENTINELS[v.value]
                ?: throw FuaranDecodeException(
                    FuaranDecodeException.WRONG_TYPE,
                    path,
                    "expected a JSON number, or one of the non-finite sentinel strings " +
                        "'NaN' / 'Infinity' / '-Infinity' — got '${v.value}'. The three spellings are " +
                        "exact and case-sensitive",
                )
        else ->
            throw FuaranDecodeException(
                FuaranDecodeException.WRONG_TYPE,
                path,
                "expected a JSON number (or a 'NaN' / 'Infinity' / '-Infinity' sentinel string)",
            )
    }

private fun JsonValue.bool(path: String): Boolean =
    (unwrapStaticEnvelope() as? JsonBool)?.value
        ?: throw FuaranDecodeException(FuaranDecodeException.WRONG_TYPE, path, "expected boolean")

private fun JsonValue.array(path: String): List<JsonValue> =
    (this as? JsonArray)?.items
        ?: throw FuaranDecodeException(FuaranDecodeException.WRONG_TYPE, path, "expected array")

/**
 * A host-opaque payload slot (`SetState.value`, `Notify.payload`, `AiTool.args`, a `Custom` prop,
 * an `I18n` arg). The value is held RAW — the projection never interprets it — but an explicit
 * `null` is not a payload: the wire spells absence by omitting the key, so a `null` here is a
 * malformed document (the corpus's `reject-null-*` family). Accepting it would hand the embedding
 * app a slot that claims to carry a value and does not.
 */
private fun JsonValue.payload(path: String): JsonValue =
    if (this is JsonNull) {
        throw FuaranDecodeException(
            FuaranDecodeException.WRONG_TYPE,
            path,
            "expected a JSON value; an explicit null is not a payload — omit the key instead",
        )
    } else {
        this
    }

/** The same rule over a string-keyed payload map, per ENTRY so the refusal names the offending key. */
private fun JsonValue.payloadMap(path: String): JsonValue {
    val o = obj(path)
    for ((key, v) in o.members) v.payload("$path.$key")
    return o
}

private fun JsonObject.req(key: String, path: String): JsonValue =
    this[key] ?: throw FuaranDecodeException(FuaranDecodeException.MISSING_FIELD, "$path.$key", "required field absent")

/**
 * A field with an accepted decode-side alias SET (WIRE_FORMAT 3.6 field aliases). The canonical
 * name always wins when both are present, and the nested path always uses the canonical name, so a
 * document written in a foreign spelling still reports errors in the language's own terms.
 *
 * An alias SET rather than a single alias: `Navigate.route` accepts `href | url | to` and a
 * grid column's `label` accepts `header | title`, so a one-alias helper structurally cannot
 * express the vocabulary the reference host already accepts.
 */
private fun JsonObject.getAliased(key: String, vararg aliases: String): JsonValue? =
    this[key] ?: aliases.firstNotNullOfOrNull { this[it] }

private fun JsonObject.reqAliased(key: String, path: String, vararg aliases: String): JsonValue =
    getAliased(key, *aliases)
        ?: throw FuaranDecodeException(FuaranDecodeException.MISSING_FIELD, "$path.$key", "required field absent")

private fun JsonObject.discriminator(path: String): String =
    (req("\$type", path) as? JsonString)?.value
        ?: throw FuaranDecodeException(FuaranDecodeException.WRONG_TYPE, "$path.\$type", "expected string discriminator")

private fun JsonObject.optStr(key: String, path: String): String? = this[key]?.str("$path.$key")

private fun JsonObject.optInt(key: String, path: String): Int? = this[key]?.int("$path.$key")

private fun JsonObject.optDouble(key: String, path: String): Double? = this[key]?.double("$path.$key")

private fun JsonObject.optBool(key: String, path: String): Boolean? = this[key]?.bool("$path.$key")

/**
 * The ENUMERATED near-miss refusal (WIRE_FORMAT 3.2 "Near-miss names are refused, not ignored").
 *
 * Rule 2 tolerates an unknown key, which is right for a field a future profile may add. It is
 * wrong for a name that is a near miss of one that EXISTS: the tree then decodes, validates and
 * renders while the declaration does nothing, so the emitter cannot tell a spelling mistake from
 * a declaration that worked - a fake affordance arriving through a typo. The set is closed and
 * small by design, and `schema.json` forbids each with `not: { required: [...] }`, so the two
 * artefacts agree.
 *
 * Refused rather than ALIASED, deliberately: these are not synonyms. `currentPage` carries a
 * literal page number the vocabulary cannot express at all, and `readOnly` is the INVERSE of
 * `editable` - an alias that inverts a boolean makes a read-only column editable when it guesses
 * wrong. Naming the canonical form beats guessing.
 */
private fun JsonObject.refuseNearMiss(path: String, canonical: Map<String, String>) {
    for ((name, replacement) in canonical) {
        if (this[name] != null) {
            throw FuaranDecodeException(
                FuaranDecodeException.WRONG_TYPE,
                "$path.$name",
                "'$name' is a near miss of the canonical form; use $replacement",
            )
        }
    }
}

/** An integer slot with a schema-pinned lower bound (`minimum`). Below it is `WRONG_TYPE`. */
private fun JsonValue.intAtLeast(min: Int, path: String): Int {
    val n = int(path)
    if (n < min) {
        throw FuaranDecodeException(
            FuaranDecodeException.WRONG_TYPE,
            path,
            "expected an integer >= $min, got $n",
        )
    }
    return n
}

private fun JsonObject.strList(key: String, path: String): List<String> =
    req(key, path).array("$path.$key").mapIndexed { i, v -> v.str("$path.$key[$i]") }

private fun JsonObject.optStrList(key: String, path: String): List<String>? =
    this[key]?.array("$path.$key")?.mapIndexed { i, v -> v.str("$path.$key[$i]") }

// --------------------------------------------------------------------------- //
// Node envelope
// --------------------------------------------------------------------------- //

/**
 * The single funnel for NODE recursion — every child, every nested slot and the root all
 * arrive here, which is what makes one `enterNode` call sufficient to bound the whole
 * node axis. The counter is entered BEFORE the shape check below, so a document past the
 * limit is refused for being too deep rather than for whatever the over-deep value
 * happens to look like.
 */
private fun decodeNode(value: JsonValue, path: String): Node {
    NodeWalk.enterNode(path)
    try {
        val obj = value.obj(path)
        val idValue =
            obj["id"] ?: throw FuaranDecodeException(FuaranDecodeException.MISSING_FIELD, "$path.id", "required field absent")
        val id = idValue.str("$path.id")
        if (id.isEmpty()) throw FuaranDecodeException(FuaranDecodeException.EMPTY_NODE_ID, "$path.id", "node id is empty")
        val kind = decodeNodeKind(obj.req("kind", path), "$path.kind")
        val style = obj["style"]?.let { decodeStyle(it, "$path.style") }
        val state = obj["state"]?.let { decodeState(it, "$path.state") }
        val accessibility = obj["accessibility"]?.let { decodeAccessibility(it, "$path.accessibility") }
        return Node(id = id, kind = kind, style = style, state = state, accessibility = accessibility)
    } finally {
        // In `finally` because a default-deny decoder leaves by a throw more often than
        // by a return, and a counter that only decrements on success would tighten with
        // every refusal until a valid tree was refused too.
        NodeWalk.exitNode()
    }
}

/**
 * The one `ToneVariant` reader (Phase 750), because two positions now teach the tone
 * vocabulary — a `tone` field and a `TonedPill` tone-map VALUE — and a second reader is
 * exactly how one of them comes to accept a spelling the other refuses.
 *
 * It carries the WIRE_FORMAT 3.6 enum-value aliases, which the bare [enumOf] does not:
 * `Positive` → Success, `Danger`/`Negative` → Critical, `Neutral` → Default. Faithful
 * same-concept mappings only; a name betraying a different concept stays a reject, and
 * an unknown spelling still raises `UNKNOWN_DU_CASE` naming the seven legal cases.
 */
private fun toneVariantOf(raw: String, path: String): ToneVariant =
    when (raw) {
        "Positive" -> ToneVariant.Success
        "Danger", "Negative" -> ToneVariant.Critical
        "Neutral" -> ToneVariant.Default
        else -> enumOf<ToneVariant>(raw, path)
    }

/**
 * The remaining WIRE_FORMAT 3.6 enum-value aliases, each a faithful same-concept mapping from the
 * dominant foreign spelling to the language's canonical case. A name betraying a DIFFERENT concept
 * is not aliased and stays a reject — the aliases exist to accept a synonym, never to guess.
 *
 * One reader per vocabulary, for the reason the tone reader states: a second reader at a second
 * position is exactly how one position comes to accept a spelling the other refuses.
 */
private fun badgeVariantOf(raw: String, path: String): BadgeVariant =
    when (raw) {
        "Default" -> BadgeVariant.Neutral
        "Danger", "Negative" -> BadgeVariant.Critical
        "Positive" -> BadgeVariant.Success
        else -> enumOf<BadgeVariant>(raw, path)
    }

private fun buttonVariantOf(raw: String, path: String): ButtonVariant =
    when (raw) {
        // The web/design-system prior: "danger" is the near-universal name for the
        // destructive button, and it is what models emit.
        "Danger" -> ButtonVariant.Destructive
        else -> enumOf<ButtonVariant>(raw, path)
    }

private fun headingVariantOf(raw: String, path: String): HeadingVariant =
    when (raw) {
        "Default" -> HeadingVariant.Standard
        else -> enumOf<HeadingVariant>(raw, path)
    }

/** The CSS flex-direction prior: a row lays out horizontally, a column vertically. */
private fun orientationOf(raw: String, path: String): Orientation =
    when (raw) {
        "Row", "row" -> Orientation.Horizontal
        "Column", "column" -> Orientation.Vertical
        else -> enumOf<Orientation>(raw, path)
    }

/**
 * The `Emphasis` style ENUM slot. Prominence intent survives cross-vocabulary, so a BOOL in the
 * enum slot projects one-to-one (`true` ⇒ Loud, `false` ⇒ Normal), and the 3.6 aliases
 * Strong/Bold ⇒ Loud, Subtle/Muted ⇒ Quiet apply.
 */
private fun decodeEmphasisEnum(value: JsonValue, path: String): Emphasis =
    when (val v = value.unwrapStaticEnvelope()) {
        is JsonBool -> if (v.value) Emphasis.Loud else Emphasis.Normal
        else ->
            when (val raw = v.str(path)) {
                "Strong", "Bold" -> Emphasis.Loud
                "Subtle", "Muted" -> Emphasis.Quiet
                else -> enumOf<Emphasis>(raw, path)
            }
    }

/**
 * The behavioural `emphasis` BOOL (`Fact` / `LabelValueRow`) — the other half of the same-name
 * collision with the style enum above. Booleans pass through; the enum and its aliases project
 * one-to-one; any other string is the didactic refusal naming BOTH vocabularies, because at this
 * position "expected boolean" alone does not tell the author which of the two they hit.
 */
private fun decodeEmphasisFlag(value: JsonValue, path: String): Boolean =
    when (val v = value.unwrapStaticEnvelope()) {
        is JsonBool -> v.value
        is JsonString ->
            when (v.value) {
                "Loud", "Strong", "Bold" -> true
                "Normal", "Quiet", "Subtle", "Muted" -> false
                else ->
                    throw FuaranDecodeException(
                        FuaranDecodeException.WRONG_TYPE,
                        path,
                        "expected boolean, got '${v.value}' — this `emphasis` is a BOOL (is this an " +
                            "emphasised row/fact?); the Emphasis style enum (Quiet|Normal|Loud) lives on " +
                            "style/Metric.emphasis. Write true or false",
                    )
            }
        else -> throw FuaranDecodeException(FuaranDecodeException.WRONG_TYPE, path, "expected boolean")
    }

private fun decodeStyle(value: JsonValue, path: String): SemanticStyle {
    val o = value.obj(path)
    return SemanticStyle(
        emphasis = o["emphasis"]?.let { decodeEmphasisEnum(it, "$path.emphasis") } ?: Emphasis.Normal,
        tone = o.optStr("tone", path)?.let { toneVariantOf(it, "$path.tone") } ?: ToneVariant.Default,
        weight = o.optStr("weight", path)?.let { enumOf<StyleWeight>(it, "$path.weight") } ?: StyleWeight.Standard,
        role = o.optStr("role", path),
        voice = o.optStr("voice", path),
    )
}

private fun decodeState(value: JsonValue, path: String): StateBehaviour {
    val o = value.obj(path)
    return StateBehaviour(
        onLoading = o["onLoading"]?.let { decodeNode(it, "$path.onLoading") },
        onEmpty = o["onEmpty"]?.let { decodeNode(it, "$path.onEmpty") },
        hasOnError = o["onError"] != null,
    )
}

/**
 * `liveRegion` is a CLOSED token set (WIRE_FORMAT 3.1), lower-case by specification rather than
 * PascalCase, so it does not go through [enumOf]. It was read as any string, which is why this
 * surface accepted `"urgent"`; an unknown spelling now fails `UNKNOWN_DU_CASE` naming the three.
 */
private fun decodeLiveRegion(value: JsonValue, path: String): String {
    val raw = value.str(path)
    if (raw !in LIVE_REGIONS) {
        throw FuaranDecodeException(
            FuaranDecodeException.UNKNOWN_DU_CASE,
            path,
            "unrecognised liveRegion '$raw'; expected one of ${LIVE_REGIONS.joinToString(", ")}",
        )
    }
    return raw
}

private val LIVE_REGIONS = listOf("polite", "assertive", "off")

private fun decodeAccessibility(value: JsonValue, path: String): Accessibility {
    val o = value.obj(path)
    return Accessibility(
        label = o["label"]?.let { decodeBindingString(it, "$path.label") },
        labelledBy = o.optStr("labelledBy", path),
        describedBy = o.optStr("describedBy", path),
        role = o.optStr("role", path),
        liveRegion = o["liveRegion"]?.let { decodeLiveRegion(it, "$path.liveRegion") },
        hidden = o["hidden"]?.let { decodeBindingBool(it, "$path.hidden") },
    )
}

// --------------------------------------------------------------------------- //
// NodeKind dispatch
// --------------------------------------------------------------------------- //

private fun decodeNodeKind(value: JsonValue, path: String): NodeKind {
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
        // Layout
        "Box" ->
            Box(
                children = decodeNodeList(o.req("children", path), "$path.children"),
                layout = decodeBoxLayout(o.req("layout", path), "$path.layout"),
                role = enumOf<BoxRole>(o.req("role", path).str("$path.role"), "$path.role"),
                // Field alias: title → heading (the universal card/modal prior).
                heading = o.getAliased("heading", "title")?.let { decodeTextSource(it, "$path.heading") },
            )
        "SplitPanel" ->
            SplitPanel(
                children = decodeNodeList(o.req("children", path), "$path.children"),
                weight = o.req("weight", path).double("$path.weight"),
            )
        "Tabs" ->
            Tabs(
                // `activeIndex` round-trips; absent (legacy wire) defaults to Static 0.
                activeIndex = o["activeIndex"]?.let { decodeBindingInt(it, "$path.activeIndex") }
                    ?: StaticBinding(JsonNumber("0")),
                children = decodeNodeList(o.req("children", path), "$path.children"),
                // 0.2.0 — omitted-when-default (Horizontal).
                orientation = o.optStr("orientation", path)?.let { orientationOf(it, "$path.orientation") }
                    ?: Orientation.Horizontal,
                activeTag = o["activeTag"]?.let { decodeBindingString(it, "$path.activeTag") },
                tabTags = o.optStrList("tabTags", path),
                tabHeaders = o["tabHeaders"]?.array("$path.tabHeaders")?.mapIndexed { i, v ->
                    decodeTabHeader(v, "$path.tabHeaders[$i]")
                },
            )
        "Stepper" ->
            Stepper(
                activeStep = decodeBindingInt(o.req("activeStep", path), "$path.activeStep"),
                children = decodeNodeList(o.req("children", path), "$path.children"),
            )
        "SummaryList" ->
            SummaryList(
                children = decodeNodeList(o.req("children", path), "$path.children"),
                // Field alias: title → heading (the universal card/modal prior).
                heading = o.getAliased("heading", "title")?.let { decodeTextSource(it, "$path.heading") },
            )
        "Disclosure" ->
            Disclosure(
                children = decodeNodeList(o.req("children", path), "$path.children"),
                heading = decodeTextSource(o.reqAliased("heading", path, "title"), "$path.heading"),
                open = decodeBindingBool(o.req("open", path), "$path.open"),
                defaultOpen = o.req("defaultOpen", path).bool("$path.defaultOpen"),
            )
        "Modal" ->
            Modal(
                children = decodeNodeList(o.req("children", path), "$path.children"),
                dismissable = o.req("dismissable", path).bool("$path.dismissable"),
                open = decodeBindingBool(o.req("open", path), "$path.open"),
                // Field alias: title → heading (the universal card/modal prior).
                heading = o.getAliased("heading", "title")?.let { decodeTextSource(it, "$path.heading") },
                onDismiss = o["onDismiss"]?.let { decodeAction(it, "$path.onDismiss") },
            )
        "ScrollArea" ->
            ScrollArea(
                children = decodeNodeList(o.req("children", path), "$path.children"),
                orientation = enumOf<ScrollOrientation>(o.req("orientation", path).str("$path.orientation"), "$path.orientation"),
                maxHeight = o.optInt("maxHeight", path),
                maxWidth = o.optInt("maxWidth", path),
            )
        "Mount" ->
            Mount(
                scopeId = o.req("scopeId", path).str("$path.scopeId"),
                capabilities = o.strList("capabilities", path),
                channel = decodeMountChannel(o.req("channel", path), "$path.channel"),
                inputs = o["inputs"]?.let { decodeFragmentArgs(it, "$path.inputs") },
            )
        "Switch" -> {
            // The selector widened: `on` takes any Binding (a `Selection` makes the branch follow
            // the clicked row), so `stateKey` is no longer required on its own. The schema states
            // the obligation as `anyOf: [required stateKey, required on]` - at least one, and the
            // refusal below is what makes a Switch carrying NEITHER a decode error rather than a
            // node that silently always renders its default.
            val stateKey = o.optStr("stateKey", path)
            val on = o["on"]?.let { decodeBinding(it, "$path.on") }
            if (stateKey == null && on == null) {
                throw FuaranDecodeException(
                    FuaranDecodeException.MISSING_FIELD,
                    "$path.stateKey",
                    "a Switch selects on `stateKey` or `on`; neither is present",
                )
            }
            Switch(
                stateKey = stateKey,
                on = on,
                cases = o.req("cases", path).array("$path.cases").mapIndexed { i, v ->
                    val c = v.obj("$path.cases[$i]")
                    SwitchCase(
                        match = c.req("match", "$path.cases[$i]").str("$path.cases[$i].match"),
                        child = decodeNode(c.req("child", "$path.cases[$i]"), "$path.cases[$i].child"),
                    )
                },
                default = decodeNode(o.req("default", path), "$path.default"),
            )
        }
        // Display
        "Heading" ->
            Heading(
                level = o.req("level", path).int("$path.level"),
                text = decodeTextSource(o.req("text", path), "$path.text"),
                variant = headingVariantOf(o.req("variant", path).str("$path.variant"), "$path.variant"),
            )
        "Markdown" -> Markdown(text = decodeTextSource(o.req("text", path), "$path.text"))
        "Metric" ->
            Metric(
                label = decodeTextSource(o.req("label", path), "$path.label"),
                // 0.2.0 rename law — scalar displayed value ⇒ `value` (`data` alias kept;
                // the retired `source` spelling is a hard MISSING_FIELD, mirroring the core).
                value = decodeBindingFloat(o.reqAliased("value", path, "data"), "$path.value"),
                // 0.2.x — stylistic fields omitted-when-default.
                format = o["format"]?.let { decodeValueFormat(it, "$path.format") } ?: NoValueFormat,
                emphasis = o["emphasis"]?.let { decodeEmphasisEnum(it, "$path.emphasis") } ?: Emphasis.Normal,
                tone = o.optStr("tone", path)?.let { toneVariantOf(it, "$path.tone") } ?: ToneVariant.Default,
                weight = o.optStr("weight", path)?.let { enumOf<StyleWeight>(it, "$path.weight") } ?: StyleWeight.Standard,
                icon = o.optStr("icon", path),
                subtext = o["subtext"]?.let { decodeTextSource(it, "$path.subtext") },
                trend = o["trend"]?.let { decodeBindingFloat(it, "$path.trend") },
                trendFormat = o["trendFormat"]?.let { decodeValueFormat(it, "$path.trendFormat") },
            )
        "Badge" ->
            Badge(
                label = decodeTextSource(o.req("label", path), "$path.label"),
                variant = badgeVariantOf(o.req("variant", path).str("$path.variant"), "$path.variant"),
            )
        // Field alias: data → source (the chart-library prior).
        "Sparkline" -> Sparkline(source = decodeBinding(o.reqAliased("source", path, "data"), "$path.source"))
        "Callout" ->
            Callout(
                body = decodeTextSource(o.req("body", path), "$path.body"),
                // 0.2.0 — omitted-when-false; heading is optional.
                dismissable = o.optBool("dismissable", path) ?: false,
                tone = o.optStr("tone", path)?.let { toneVariantOf(it, "$path.tone") } ?: ToneVariant.Default,
                // Field alias: title → heading (the universal card/modal prior).
                heading = o.getAliased("heading", "title")?.let { decodeTextSource(it, "$path.heading") },
                icon = o.optStr("icon", path),
            )
        "Progress" ->
            Progress(
                fraction = decodeBindingFloat(o.req("fraction", path), "$path.fraction"),
                // 0.2.0 — omitted-when-false; label is optional; `caveat` added.
                indeterminate = o.optBool("indeterminate", path) ?: false,
                tone = o.optStr("tone", path)?.let { toneVariantOf(it, "$path.tone") } ?: ToneVariant.Default,
                label = o["label"]?.let { decodeTextSource(it, "$path.label") },
                caveat = o["caveat"]?.let { decodeTextSource(it, "$path.caveat") },
            )
        "Skeleton" -> Skeleton(rows = o.req("rows", path).int("$path.rows"))
        "LabelValueRow" ->
            LabelValueRow(
                label = decodeTextSource(o.req("label", path), "$path.label"),
                // 0.2.0 rename law — scalar displayed value ⇒ `value` (`data` alias kept).
                value = decodeBindingFloat(o.reqAliased("value", path, "data"), "$path.value"),
                format = o["format"]?.let { decodeValueFormat(it, "$path.format") } ?: NoValueFormat,
                // The behavioural bool; 0.2.2 — omitted-when-false.
                emphasis = o["emphasis"]?.let { decodeEmphasisFlag(it, "$path.emphasis") } ?: false,
                help = o["help"]?.let { decodeTextSource(it, "$path.help") },
            )
        "Fact" ->
            Fact(
                label = decodeTextSource(o.req("label", path), "$path.label"),
                value = decodeTextSource(o.req("value", path), "$path.value"),
                emphasis = o["emphasis"]?.let { decodeEmphasisFlag(it, "$path.emphasis") } ?: false,
                tone = o.optStr("tone", path)?.let { toneVariantOf(it, "$path.tone") } ?: ToneVariant.Default,
                help = o["help"]?.let { decodeTextSource(it, "$path.help") },
                icon = o.optStr("icon", path),
            )
        "Icon" ->
            Icon(
                icon = o.req("icon", path).str("$path.icon"),
                label = o.optStr("label", path),
                size = o.optStr("size", path)?.let { enumOf<IconSize>(it, "$path.size") } ?: IconSize.Medium,
                tone = o.optStr("tone", path)?.let { toneVariantOf(it, "$path.tone") } ?: ToneVariant.Default,
            )
        "Link" ->
            Link(
                href = decodeBindingString(o.req("href", path), "$path.href"),
                label = decodeTextSource(o.req("label", path), "$path.label"),
                download = o.req("download", path).bool("$path.download"),
                rel = o.optStr("rel", path),
                target = o.optStr("target", path),
                protection = o.optStr("protection", path)?.let { wireEnumOf<LinkProtection>(it, "$path.protection") },
            )
        "Image" ->
            Image(
                alt = decodeTextSource(o.req("alt", path), "$path.alt"),
                src = decodeBindingString(o.req("src", path), "$path.src"),
                variant = enumOf<ImageVariant>(o.req("variant", path).str("$path.variant"), "$path.variant"),
            )
        "List" ->
            ListNode(
                items = o.req("items", path).array("$path.items").mapIndexed { i, v -> decodeTextSource(v, "$path.items[$i]") },
                ordered = o.req("ordered", path).bool("$path.ordered"),
            )
        "Toast" ->
            Toast(
                message = decodeTextSource(o.req("message", path), "$path.message"),
                open = decodeBindingBool(o.req("open", path), "$path.open"),
                tone = o.optStr("tone", path)?.let { toneVariantOf(it, "$path.tone") } ?: ToneVariant.Default,
                // 0.2.0 — omitted-when-TRUE (the one inverted default).
                dismissable = o.optBool("dismissable", path) ?: true,
            )
        "CodeBlock" ->
            CodeBlock(
                code = o.req("code", path).str("$path.code"),
                copyable = o.req("copyable", path).bool("$path.copyable"),
                highlightLines = o.req("highlightLines", path).array("$path.highlightLines").mapIndexed { i, v -> v.int("$path.highlightLines[$i]") },
                language = o.req("language", path).str("$path.language"),
                lineNumbers = o.req("lineNumbers", path).bool("$path.lineNumbers"),
            )
        "Math" ->
            Math(
                display = enumOf<MathDisplay>(o.req("display", path).str("$path.display"), "$path.display"),
                source = o.req("source", path).str("$path.source"),
            )
        "Drawing" ->
            Drawing(
                shapes = o.req("shapes", path).array("$path.shapes").mapIndexed { i, v -> decodeShape(v, "$path.shapes[$i]") },
                style = o["style"]?.let { decodeDrawStyle(it, "$path.style") } ?: DrawStyle(),
                viewBox = decodeViewBox(o.req("viewBox", path), "$path.viewBox"),
                title = o["title"]?.let { decodeTextSource(it, "$path.title") },
                description = o["description"]?.let { decodeTextSource(it, "$path.description") },
            )
        // Input
        "Form" ->
            Form(
                fields = o.req("fields", path).array("$path.fields").mapIndexed { i, v -> decodeFormField(v, "$path.fields[$i]") },
                onSubmit = decodeAction(o.req("onSubmit", path), "$path.onSubmit"),
                submitLabel = decodeTextSource(o.req("submitLabel", path), "$path.submitLabel"),
                disabled = o["disabled"]?.let { decodeBindingBool(it, "$path.disabled") },
            )
        "Button" ->
            Button(
                label = decodeTextSource(o.req("label", path), "$path.label"),
                onClick = decodeAction(o.req("onClick", path), "$path.onClick"),
                variant = buttonVariantOf(o.req("variant", path).str("$path.variant"), "$path.variant"),
                disabled = o["disabled"]?.let { decodeBindingBool(it, "$path.disabled") },
                icon = o.optStr("icon", path),
            )
        "FileUpload" ->
            FileUpload(
                accept = o.strList("accept", path),
                label = decodeTextSource(o.req("label", path), "$path.label"),
                multiple = o.req("multiple", path).bool("$path.multiple"),
                disabled = o["disabled"]?.let { decodeBindingBool(it, "$path.disabled") },
            )
        "Select" ->
            Select(
                label = decodeTextSource(o.req("label", path), "$path.label"),
                // Field aliases: options → source (the HTML `<select>` prior), data → source.
                source = decodeBinding(o.reqAliased("source", path, "options", "data"), "$path.source"),
                multiple = o.optBool("multiple", path) ?: false,
                value = o["value"]?.let { decodeBinding(it, "$path.value") },
                values = o["values"]?.let { decodeBinding(it, "$path.values") },
                placeholder = o["placeholder"]?.let { decodeTextSource(it, "$path.placeholder") },
                disabled = o["disabled"]?.let { decodeBindingBool(it, "$path.disabled") },
            )
        "Filters" ->
            Filters(
                items = o.req("items", path).array("$path.items").mapIndexed { i, v -> decodeFilterItem(v, "$path.items[$i]") },
            )
        // Visualisation
        "DataGrid" -> {
            o.refuseNearMiss(
                path,
                mapOf(
                    "currentPage" to "pageStateKey (the position lives in State as a {\"page\": N} slot)",
                    "page" to "pageStateKey (the position lives in State as a {\"page\": N} slot)",
                    "pageIndex" to "pageStateKey (the position lives in State as a {\"page\": N} slot)",
                    "sortable" to "sortStateKey + a per-column `sortable` (grid-wide `sortable` is the staticRows spelling)",
                    "onEdit" to "editStateKey",
                    "behaviour" to "the sibling behaviour fields; grid behaviour is not a nested record",
                    "behavior" to "the sibling behaviour fields; grid behaviour is not a nested record",
                ),
            )
            DataGrid(
                columns = o.req("columns", path).array("$path.columns").mapIndexed { i, v -> decodeGridColumn(v, "$path.columns[$i]") },
                // Field aliases: data / rows → source (the Chart.js / react-table prior).
                source = decodeBinding(o.reqAliased("source", path, "data", "rows"), "$path.source"),
                // 0.2.0 — omitted-when-false.
                editable = o.optBool("editable", path) ?: false,
                rowKeyField = o.optStr("rowKeyField", path),
                staticRows = o["staticRows"]?.let { decodeStaticRows(it, "$path.staticRows") },
                sortStateKey = o.optStr("sortStateKey", path),
                pageStateKey = o.optStr("pageStateKey", path),
                editStateKey = o.optStr("editStateKey", path),
                // `minimum: 1` - a page size of zero paginates nothing, so it is malformed rather
                // than a degenerate configuration the renderer should try to honour.
                pageSize = o["pageSize"]?.intAtLeast(1, "$path.pageSize"),
                defaultSort = o["defaultSort"]?.let { decodeDefaultSort(it, "$path.defaultSort") },
            )
        }
        "Chart" ->
            Chart(
                kind = enumOf<ChartKind>(o.req("kind", path).str("$path.kind"), "$path.kind"),
                source = decodeBinding(o.reqAliased("source", path, "data"), "$path.source"),
                xField = o.req("xField", path).str("$path.xField"),
                yFields = o.strList("yFields", path),
                // Round-trips when present; absent (legacy wire) defaults to false.
                stacked = o.optBool("stacked", path) ?: false,
                title = o["title"]?.let { decodeTextSource(it, "$path.title") },
            )
        "Map" ->
            MapNode(
                centreLatitude = o.req("centreLatitude", path).double("$path.centreLatitude"),
                centreLongitude = o.req("centreLongitude", path).double("$path.centreLongitude"),
                source = decodeBinding(o.reqAliased("source", path, "data", "markers"), "$path.source"),
                zoom = o.req("zoom", path).int("$path.zoom"),
            )
        // Structural
        "Custom" ->
            Custom(
                moduleId = o.req("moduleId", path).str("$path.moduleId"),
                componentId = o.req("componentId", path).str("$path.componentId"),
                props = o.req("props", path).payloadMap("$path.props"),
                contentHash = o["contentHash"]?.let { decodeContentHash(it, "$path.contentHash") },
                exposedNodeIds = o.optStrList("exposedNodeIds", path),
            )
        "ErrorBoundary" ->
            ErrorBoundary(
                child = decodeNode(o.req("child", path), "$path.child"),
                fallback = decodeNode(o.req("fallback", path), "$path.fallback"),
            )
        "FragmentDecl" ->
            FragmentDecl(
                name = o.req("name", path).str("$path.name"),
                body = decodeNode(o.req("body", path), "$path.body"),
                holes = o["holes"]?.array("$path.holes")?.mapIndexed { i, v -> decodeHoleDecl(v, "$path.holes[$i]") },
                effect = o["effect"]?.let { decodeEffect(it, "$path.effect") },
            )
        "FragmentRef" ->
            FragmentRef(
                name = o.req("name", path).str("$path.name"),
                args = o["args"]?.let { decodeFragmentArgs(it, "$path.args") },
            )
        else ->
            throw FuaranDecodeException(
                FuaranDecodeException.WRONG_NODE_KIND,
                "$path.\$type",
                "'$t' is not a recognised node kind",
            )
    }
}

private fun decodeNodeList(value: JsonValue, path: String): List<Node> =
    value.array(path).mapIndexed { i, v -> decodeNode(v, "$path[$i]") }

private fun decodeTabHeader(value: JsonValue, path: String): TabHeader {
    val o = value.obj(path)
    return TabHeader(
        label = decodeTextSource(o.req("label", path), "$path.label"),
        icon = o.optStr("icon", path),
        disabled = o["disabled"]?.let { decodeBindingBool(it, "$path.disabled") },
    )
}

private fun decodeMountChannel(value: JsonValue, path: String): MountChannel {
    val o = value.obj(path)
    return MountChannel(
        direction = enumOf<MountDirection>(o.req("direction", path).str("$path.direction"), "$path.direction"),
        messageShape = o.optStr("messageShape", path),
    )
}

// --------------------------------------------------------------------------- //
// TextSource
// --------------------------------------------------------------------------- //

private fun decodeTextSource(value: JsonValue, path: String): TextSource {
    // 0.2.0 canonical form (§16, normative): a bare JSON string IS `TextSource.Literal` —
    // the `{"$type":"Literal"}` envelope stays decode-accepted and normalises down.
    if (value is JsonString) return LiteralText(value.value)
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
        "Literal" -> LiteralText(o.req("text", path).str("$path.text"))
        "Bound" -> BoundText(decodeBinding(o.req("binding", path), "$path.binding"))
        "I18n" -> I18nText(o.req("key", path).str("$path.key"), o["args"]?.payloadMap("$path.args"))
        else -> unknownCase(t, path, "TextSource")
    }
}

// --------------------------------------------------------------------------- //
// Binding
// --------------------------------------------------------------------------- //

/**
 * A `Binding<string>` / `Binding<bool>` / `Binding<float>` / `Binding<int>` slot — the typed
 * SCALAR positions.
 *
 * WIRE_FORMAT 3.6's bare-scalar coercion is about SHAPE: every `Binding` case is a
 * `$type`-discriminated object, so a bare scalar can only mean `Static`. The slot's own type still
 * governs the VALUE, which is why `{"hidden": "yes"}` must be refused even though
 * `{"label": "Home"}` is sanctioned shorthand. Without the check this surface decoded every
 * wrong-typed scalar with its value preserved verbatim.
 *
 * Only the `Static` payload is checked. The other value-carrying arms hold their default raw (the
 * render projection never types them), so typing them here would be a separate behaviour change
 * with no fixture pinning it.
 */
private fun decodeBindingScalar(value: JsonValue, path: String, expect: (JsonValue, String) -> Unit): Binding {
    val b = decodeBinding(value, path)
    if (b is StaticBinding) expect(b.value, path)
    return b
}

private fun decodeBindingString(value: JsonValue, path: String): Binding =
    decodeBindingScalar(value, path) { v, p -> v.str(p) }

private fun decodeBindingBool(value: JsonValue, path: String): Binding =
    decodeBindingScalar(value, path) { v, p -> v.bool(p) }

/**
 * The typed NUMERIC `Binding` slots (WIRE_FORMAT 7), on the same machinery as the string/bool
 * pair above and for the same reason: 3.6's bare-scalar coercion is about SHAPE — a bare scalar
 * in a Binding slot can only mean `Static` — while the slot's own type still governs the VALUE.
 * Both arms therefore route through the slot's typed parser: the `{"$type":"Static","value":X}`
 * envelope reaches [decodeBindingScalar] as a [StaticBinding], and so does the bare scalar, via
 * the coercion in [decodeBinding]. Checking only the envelope would leave `fraction: "nan"` —
 * the shorthand a model reaches for first — decoding with its wrong-typed value preserved.
 *
 * [decodeBindingFloat] admits the three sentinel strings; [decodeBindingInt] admits none. That
 * asymmetry is the whole of 7 and is not an oversight at the int slot.
 */
private fun decodeBindingFloat(value: JsonValue, path: String): Binding =
    decodeBindingScalar(value, path) { v, p -> v.double(p) }

private fun decodeBindingInt(value: JsonValue, path: String): Binding =
    decodeBindingScalar(value, path) { v, p -> v.int(p) }

private fun decodeBinding(value: JsonValue, path: String): Binding {
    // Lenient shape coercion (§3.6, mirrored from the reference core): a bare array or
    // scalar where a Binding is expected reads as `Static` with that value — unambiguous,
    // since every Binding case is a `$type`-discriminated object. Objects stay strict.
    when (value) {
        is JsonArray, is JsonString, is JsonNumber, is JsonBool -> return StaticBinding(value)
        else -> {}
    }
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
        // Phase 677 — absence is structural: a MISSING `value` means the binding
        // carries none, and the legacy `"value": null` spelling normalises to the
        // same thing (§16 shorthand), so the two cannot disagree.
        "Static" -> StaticBinding(o["value"] ?: JsonNull)
        // Field aliases: initialValue / default → defaultValue (the useState prior).
        "State" -> StateBinding(o.req("key", path).str("$path.key"), o.getAliased("defaultValue", "initialValue", "default"))
        // Field aliases: deps / dependencies → dependsOn (the React hooks prior).
        "Query" ->
            QueryBinding(
                o.req("name", path).str("$path.name"),
                o.getAliased("dependsOn", "deps", "dependencies")
                    ?.array("$path.dependsOn")
                    ?.mapIndexed { i, v -> v.str("$path.dependsOn[$i]") },
            )
        // 0.2.0 — optional `defaultValue`, held raw (the render projection never types it).
        "Filter" -> FilterBinding(o.req("name", path).str("$path.name"), o["defaultValue"])
        // 0.2.9/0.2.10 — `nodeId` + optional `defaultValue` / `field`.
        "Selection" ->
            SelectionBinding(
                nodeId = o.req("nodeId", path).str("$path.nodeId"),
                defaultValue = o["defaultValue"],
                field = o.optStr("field", path),
            )
        "Computed" -> ComputedBinding
        // The host-furnished instant - no payload; the host clock supplies the value at resolve time.
        "Now" -> NowBinding
        "I18n" -> I18nBinding(o.req("key", path).str("$path.key"), o["args"]?.payloadMap("$path.args"))
        "Local" ->
            LocalBinding(
                flushOn = o["flushOn"]?.let { decodeLocalFlushTrigger(it, "$path.flushOn") } ?: OnBlur,
                initialFrom = decodeBinding(o.req("initialFrom", path), "$path.initialFrom"),
            )
        "Format" ->
            FormatBinding(
                format = decodeNumberFormat(o.req("format", path), "$path.format"),
                locale = decodeLocaleSource(o.req("locale", path), "$path.locale"),
                source = decodeBinding(o.req("source", path), "$path.source"),
            )
        "Transform" ->
            TransformBinding(
                source = unwrapTransformSource(o.req("source", path), "$path.source"),
                pipeline = o.req("pipeline", path),
                params = o["params"]?.let { decodeTransformParams(it, "$path.params") },
            )
        "Invoke" -> InvokeBinding(o.req("capabilityId", path).str("$path.capabilityId"), decodeInvokeArgs(o, path))
        // Lenient: the `TextSource.Bound` wrapper transferred to a bare-Binding slot —
        // one payload field, so the unwrap is one-to-one (decode-only).
        "Bound" -> decodeBinding(o.req("binding", path), "$path.binding")
        else -> unknownCase(t, path, "Binding")
    }
}

/**
 * A `Transform`'s embedded `source` slot.
 *
 * The slot is a COLUMNAR table (`{schema, columns}`) or a host-resolved named source
 * (`{schema, ref}`) - content `Fuaran.Core`'s own codec owns, so the projection holds it raw and
 * does not decompose it. What this surface DOES owe is the one check the raw hold would otherwise
 * skip.
 *
 * Models routinely wrap that table in a binding envelope (`State` / `Static` / `Bound`) because
 * every OTHER source position on the wire takes a Binding. The envelope is accepted and unwraps to
 * its payload before the columnar decode - initial-snapshot semantics, pinned by
 * `lenient/lenient-transform-source-state-rows`. An envelope carrying NO payload member is a
 * different thing entirely: there is nothing to unwrap to, so the transform has no data and the
 * grid renders empty with no indication that a source was ever declared. That is refused
 * (`reject/reject-transform-source-empty-wrapper`).
 *
 * The value is returned UNCHANGED - the canonical form keeps the envelope, so this validates
 * rather than rewrites.
 */
private fun unwrapTransformSource(value: JsonValue, path: String): JsonValue {
    val o = value as? JsonObject ?: return value
    val payloadKey =
        when ((o["\$type"] as? JsonString)?.value) {
            "State" -> if (o["defaultValue"] != null) null else if (o["value"] != null) null else "defaultValue` or `value"
            "Static" -> if (o["value"] != null) null else "value"
            "Bound" -> if (o["binding"] != null) null else "binding"
            // Not an envelope: the ordinary columnar table, passed through untouched.
            else -> null
        }
    if (payloadKey != null) {
        throw FuaranDecodeException(
            FuaranDecodeException.WRONG_TYPE,
            path,
            "a Transform source envelope carries no `$payloadKey` to unwrap to, so the transform has no data",
        )
    }
    return value
}

/**
 * A `Transform`'s query params. Canonical: the `[{name, from}]` array. Lenient (3.6): the
 * `{name: <Binding>}` MAP form, normalised sorted by name — params are a name-keyed SET, so key
 * order carries no meaning and the coercion is lossless. (The `options` map form is deliberately
 * NOT coerced anywhere, because there key order IS visible ordering.) At an array element, `value`
 * aliases `from`.
 */
private fun decodeTransformParams(value: JsonValue, path: String): List<TransformParam> =
    when (value) {
        is JsonObject ->
            value.members.entries.sortedBy { it.key }.map { (name, from) ->
                TransformParam(name = name, from = decodeBinding(from, "$path.$name.from"))
            }
        else ->
            value.array(path).mapIndexed { i, v ->
                val p = v.obj("$path[$i]")
                TransformParam(
                    name = p.req("name", "$path[$i]").str("$path[$i].name"),
                    from = decodeBinding(p.reqAliased("from", "$path[$i]", "value"), "$path[$i].from"),
                )
            }
    }

private fun decodeInvokeArgs(o: JsonObject, path: String): List<InvokeArg> =
    o.req("args", path).array("$path.args").mapIndexed { i, v ->
        val a = v.obj("$path.args[$i]")
        InvokeArg(
            addr = a.req("addr", "$path.args[$i]").str("$path.args[$i].addr"),
            value = a.req("value", "$path.args[$i]").str("$path.args[$i].value"),
        )
    }

// --------------------------------------------------------------------------- //
// Action
// --------------------------------------------------------------------------- //

private fun decodeAction(value: JsonValue, path: String): Action {
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
        "Chain" -> ChainAction(o.req("ops", path).array("$path.ops").mapIndexed { i, v -> decodeAction(v, "$path.ops[$i]") })
        "Dispatch" -> DispatchAction
        // Field alias: url → endpoint (the fetch prior).
        "Call" ->
            CallAction(
                endpoint = o.reqAliased("endpoint", path, "url").str("$path.endpoint"),
                into = o["into"]?.let { decodeCallTarget(it, "$path.into") },
            )
        "Notify" ->
            NotifyAction(
                channel = o.req("channel", path).str("$path.channel"),
                payload = o.req("payload", path).payload("$path.payload"),
            )
        // Canonical field is `route`; the web-prior spellings decode as aliases.
        "Navigate" -> {
            val v =
                o["route"] ?: o["href"] ?: o["url"] ?: o["to"]
                    ?: throw FuaranDecodeException(FuaranDecodeException.MISSING_FIELD, "$path.route", "required field absent")
            NavigateAction(route = v.str("$path.route"))
        }
        "SetState" -> {
            // `oneOf: [required value, required valueFrom]` - a literal payload OR a binding
            // resolved at dispatch time, never both. Both-present is refused rather than settled
            // by precedence: the two say different things about where the value comes from, and
            // silently preferring one hands the emitter a write it did not ask for.
            val v = o["value"]?.payload("$path.value")
            val from = o["valueFrom"]?.let { decodeBinding(it, "$path.valueFrom") }
            if (v != null && from != null) {
                throw FuaranDecodeException(
                    FuaranDecodeException.WRONG_TYPE,
                    "$path.valueFrom",
                    "a SetState carries `value` or `valueFrom`, not both",
                )
            }
            if (v == null && from == null) {
                throw FuaranDecodeException(
                    FuaranDecodeException.MISSING_FIELD,
                    "$path.value",
                    "a SetState carries `value` or `valueFrom`; neither is present",
                )
            }
            SetStateAction(key = o.req("key", path).str("$path.key"), value = v, valueFrom = from)
        }
        "AiTool" ->
            AiToolAction(
                toolName = o.req("toolName", path).str("$path.toolName"),
                args = o.req("args", path).payload("$path.args"),
            )
        "CommitLocal" -> CommitLocalAction(nodeId = o.req("nodeId", path).str("$path.nodeId"))
        "WriteToClipboard" -> WriteToClipboardAction(o.req("text", path).str("$path.text"))
        "ReadFileBody" ->
            ReadFileBodyAction(
                fileRef = o.req("fileRef", path).str("$path.fileRef"),
                encoding = enumOf<FileReadEncoding>(o.req("encoding", path).str("$path.encoding"), "$path.encoding"),
            )
        "Invoke" -> InvokeAction(o.req("capabilityId", path).str("$path.capabilityId"), decodeInvokeArgs(o, path))
        else -> unknownCase(t, path, "Action")
    }
}

private fun decodeCallTarget(value: JsonValue, path: String): CallTarget {
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
        "State" -> CallIntoState(o.req("key", path).str("$path.key"))
        "Query" -> CallIntoQuery(o.req("name", path).str("$path.name"))
        else -> unknownCase(t, path, "CallTarget")
    }
}

// --------------------------------------------------------------------------- //
// Box layout
// --------------------------------------------------------------------------- //

private fun decodeBoxLayout(value: JsonValue, path: String): BoxLayout {
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
        "Flex" ->
            FlexLayout(
                direction = orientationOf(o.req("direction", path).str("$path.direction"), "$path.direction"),
                wrap = o.req("wrap", path).bool("$path.wrap"),
                gap = o.optInt("gap", path),
            )
        // Field alias: columns → cols (the CSS/Tailwind prior). Lenient (3.6): NO column
        // spec at all is the CSS auto-grid prior and canonicalises to `Auto`; absent `cols`
        // WITH a `templateColumns` reads as `cols: 1`, since the template carries the real
        // shape and `Cols` is documented-ignored when it is present.
        "Grid" -> {
            val colsJson = o.getAliased("cols", "columns")
            val template = o.optStr("templateColumns", path)
            if (colsJson == null && template == null) {
                AutoLayout
            } else {
                GridLayout(
                    cols = colsJson?.int("$path.cols") ?: 1,
                    gap = o.optInt("gap", path),
                    templateColumns = template,
                )
            }
        }
        "Auto" -> AutoLayout
        else -> unknownCase(t, path, "BoxLayout")
    }
}

// --------------------------------------------------------------------------- //
// Formats + locale
// --------------------------------------------------------------------------- //

private fun decodeValueFormat(value: JsonValue, path: String): ValueFormat {
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
        "None" -> NoValueFormat
        "Number" -> NumberValueFormat(o.optInt("decimals", path))
        "Currency" -> CurrencyValueFormat(o.req("code", path).str("$path.code"))
        "Percent" -> PercentValueFormat(o.optInt("decimals", path))
        "SignificantDigits" -> SignificantDigitsValueFormat(o.req("digits", path).int("$path.digits"))
        "Date" -> DateValueFormat(o.req("format", path).str("$path.format"))
        "Duration" ->
            DurationValueFormat(
                unit = enumOf<DurationUnit>(o.req("unit", path).str("$path.unit"), "$path.unit"),
                style = enumOf<DurationStyle>(o.req("style", path).str("$path.style"), "$path.style"),
            )
        "RelativeTime" -> RelativeTimeValueFormat(enumOf<RelativeTimeUnit>(o.req("unit", path).str("$path.unit"), "$path.unit"))
        "Custom" -> CustomValueFormat
        else -> unknownCase(t, path, "ValueFormat")
    }
}

private fun decodeNumberFormat(value: JsonValue, path: String): NumberFormat {
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
        "Number" -> NumberNumberFormat(o.optInt("decimals", path))
        "Currency" -> CurrencyNumberFormat(o.req("isoCode", path).str("$path.isoCode"))
        "Percent" -> PercentNumberFormat(o.optInt("decimals", path))
        "Date" -> DateNumberFormat(enumOf<DateStyle>(o.req("dateStyle", path).str("$path.dateStyle"), "$path.dateStyle"))
        "RelativeTime" -> RelativeTimeNumberFormat(enumOf<RelativeTimeUnit>(o.req("unit", path).str("$path.unit"), "$path.unit"))
        else -> unknownCase(t, path, "NumberFormat")
    }
}

private fun decodeLocaleSource(value: JsonValue, path: String): LocaleSource {
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
        "Ambient" -> AmbientLocale
        "Explicit" -> ExplicitLocale(o.req("tag", path).str("$path.tag"))
        else -> unknownCase(t, path, "LocaleSource")
    }
}

// --------------------------------------------------------------------------- //
// Form fields + filters
// --------------------------------------------------------------------------- //

/**
 * The auto-bind context for a control's ABSENT `value` slot (0.2.0/0.2.1, Phase 596):
 * every control may omit `value`; a filter chip auto-binds `Filter(<name>)` and a
 * form field auto-binds `State(<field id>)` with the slot's typed placeholder as the
 * State default (empty string / `0` / `false` / null-choice / `{min 0, max 0}` /
 * ISO-empty date — pinned by `form-declarative-minimal`).
 */
private sealed interface ControlAutoBind {
    data class FilterChip(val name: String) : ControlAutoBind

    data class FormFieldId(val id: String) : ControlAutoBind
}

private fun ControlAutoBind.autoBinding(placeholder: JsonValue): Binding =
    when (this) {
        is ControlAutoBind.FilterChip -> FilterBinding(name)
        is ControlAutoBind.FormFieldId -> StateBinding(id, placeholder)
    }

/** The cross-field operand: an operator plus a `Binding` to compare against. */
private fun decodeCompareRule(value: JsonValue, path: String): CompareRule {
    val o = value.obj(path)
    return CompareRule(
        op = wireEnumOf(o.req("op", path).str("$path.op"), "$path.op"),
        against = decodeBinding(o.req("against", path), "$path.against"),
    )
}

/**
 * A field's declared constraint. Every slot is optional structurally, and two shapes are
 * refused here as POLICY (mirroring the reference host):
 *
 *  - a rule with every constraint slot absent. A rule that constrains nothing is a defect,
 *    not a no-op: it decodes, validates and renders while declaring nothing — the
 *    fake-affordance shape the near-miss set also forecloses, arriving through an empty
 *    object instead of a wrong key. `message` alone does not rescue it: the message is the
 *    prose shown when some OTHER slot is unmet.
 *  - `minLength` above `maxLength`. An inverted bound admits no value at all, so the field
 *    could never be submitted and the form is dead on arrival.
 *
 * Neither is a shape — both are relations BETWEEN slots — which is why they live here rather
 * than in the structural layer.
 */
private fun decodeFieldRule(value: JsonValue, path: String): FieldRule {
    val o = value.obj(path)
    val rule = FieldRule(
        format = o.optStr("format", path)?.let { wireEnumOf<TextFormat>(it, "$path.format") },
        pattern = o.optStr("pattern", path),
        minLength = o.optInt("minLength", path),
        maxLength = o.optInt("maxLength", path),
        compare = o["compare"]?.let { decodeCompareRule(it, "$path.compare") },
        message = o["message"]?.let { decodeTextSource(it, "$path.message") },
    )
    val constrains =
        rule.format != null || rule.pattern != null || rule.minLength != null ||
            rule.maxLength != null || rule.compare != null
    if (!constrains) {
        throw FuaranDecodeException(
            FuaranDecodeException.WRONG_TYPE,
            path,
            "a rule that constrains nothing is a defect, not a no-op — declare at least one of " +
                "format / pattern / minLength / maxLength / compare, or omit 'rule' entirely",
        )
    }
    if (rule.minLength != null && rule.maxLength != null && rule.minLength > rule.maxLength) {
        throw FuaranDecodeException(
            FuaranDecodeException.WRONG_TYPE,
            path,
            "minLength ${rule.minLength} is above maxLength ${rule.maxLength} — an inverted " +
                "length bound admits no value at all, so the field could never be submitted",
        )
    }
    return rule
}

private fun decodeFormField(value: JsonValue, path: String): FormField {
    val o = value.obj(path)
    // The near-miss check runs BEFORE the rule decode, so a field carrying both `validation`
    // and a well-formed `rule` still names the ignored key rather than passing silently.
    o.refuseNearMiss(
        path,
        mapOf(
            "validation" to "rule",
            "constraints" to "rule",
            "validate" to "rule",
        ),
    )
    // Field alias: name → id. Id decodes first so the auto-bind can use it.
    val id = o.reqAliased("id", path, "name").str("$path.id")
    return FormField(
        id = id,
        kind = decodeFormFieldKind(o.req("kind", path), "$path.kind", ControlAutoBind.FormFieldId(id)),
        label = decodeTextSource(o.req("label", path), "$path.label"),
        required = o.req("required", path).bool("$path.required"),
        help = o["help"]?.let { decodeTextSource(it, "$path.help") },
        rule = o["rule"]?.let { decodeFieldRule(it, "$path.rule") },
    )
}

private fun decodeFormFieldKind(value: JsonValue, path: String, autoBind: ControlAutoBind): FormFieldKind {
    val o = value.obj(path)
    // Value slot: present ⇒ decode; absent ⇒ the context's auto-binding (never an error).
    //
    // `decode` selects the SLOT'S typed reader — the numeric controls pass `decodeBindingFloat`
    // so a `Number` field's value is held to 7 exactly as `Metric.value` is. It defaults to the
    // untyped reader so a control whose payload the projection genuinely does not type (a choice,
    // a date, a pair) is unchanged; only the arms that name a typed reader gain a check.
    fun valueOr(placeholder: JsonValue, decode: (JsonValue, String) -> Binding = ::decodeBinding): Binding =
        o["value"]?.let { decode(it, "$path.value") } ?: autoBind.autoBinding(placeholder)
    return when (val t = o.discriminator(path)) {
        "Text" -> TextField(valueOr(JsonString("")))
        "Number" -> NumberField(valueOr(JsonNumber("0"), ::decodeBindingFloat))
        "Checkbox" -> CheckboxField(valueOr(JsonBool(false)))
        // The switch affordance beside a Checkbox: the same boolean slot, a different control.
        "Toggle" -> ToggleField(valueOr(JsonBool(false)))
        "Choice" ->
            ChoiceField(
                options = decodeBinding(o.req("options", path), "$path.options"),
                value = valueOr(JsonNull),
            )
        "TextArea" ->
            TextAreaField(
                value = valueOr(JsonString("")),
                rows = o.req("rows", path).int("$path.rows"),
            )
        "SegmentedChoice" ->
            SegmentedChoiceField(
                options = decodeBinding(o.req("options", path), "$path.options"),
                value = valueOr(JsonNull),
                // 0.2.0 — decode-optional; absent restores the language default.
                orientation = o.optStr("orientation", path)?.let { orientationOf(it, "$path.orientation") }
                    ?: Orientation.Horizontal,
            )
        "RangedNumber" ->
            RangedNumberField(
                value = valueOr(JsonNumber("0"), ::decodeBindingFloat),
                min = o.optDouble("min", path),
                max = o.optDouble("max", path),
                step = o.optDouble("step", path),
            )
        // 0.2.0 — the dual-thumb range: a canonical Static pair rides as the BARE
        // `{"max":…,"min":…}` object (no `$type`) — accept it before the generic dispatch.
        "Range" ->
            RangeField(
                value =
                    when (val v = o["value"]) {
                        null -> autoBind.autoBinding(rangePlaceholder())
                        is JsonObject ->
                            if (v["\$type"] == null && v["min"] != null && v["max"] != null) {
                                StaticBinding(v)
                            } else {
                                decodeBinding(v, "$path.value")
                            }
                        else -> decodeBinding(v, "$path.value")
                    },
                min = o.optDouble("min", path),
                max = o.optDouble("max", path),
                step = o.optDouble("step", path),
            )
        "Date" ->
            DateField(
                value = valueOr(JsonString("")),
                variant = enumOf<DateFieldVariant>(o.req("variant", path).str("$path.variant"), "$path.variant"),
                min = o.optStr("min", path),
                max = o.optStr("max", path),
                step = o.optDouble("step", path),
            )
        "DateRange" ->
            DateRangeField(
                value =
                    when (val v = o["value"]) {
                        null -> autoBind.autoBinding(dateRangePlaceholder())
                        // Canonical: the bare `{from, to}` object (no `$type`).
                        // Lenient: the two-element `[from, to]` array, and the
                        // explicit `Static` envelope around either. All three
                        // NORMALISE to the canonical pair, so a consumer sees one
                        // shape regardless of which spelling arrived.
                        is JsonArray -> StaticBinding(dateRangePair(v, "$path.value"))
                        is JsonObject ->
                            if (v["\$type"] == null && v["from"] != null && v["to"] != null) {
                                StaticBinding(dateRangePair(v, "$path.value"))
                            } else if ((v["\$type"] as? JsonString)?.value == "Static" && v["value"] != null) {
                                StaticBinding(dateRangePair(v["value"]!!, "$path.value.value"))
                            } else {
                                decodeBinding(v, "$path.value")
                            }
                        else -> decodeBinding(v, "$path.value")
                    },
                variant = enumOf<DateFieldVariant>(o.req("variant", path).str("$path.variant"), "$path.variant"),
                min = o.optStr("min", path),
                max = o.optStr("max", path),
                step = o.optDouble("step", path),
            )
        else -> unknownCase(t, path, "FormFieldKind")
    }
}

private fun rangePlaceholder(): JsonValue =
    JsonObject(linkedMapOf<String, JsonValue>("min" to JsonNumber("0"), "max" to JsonNumber("0")))

/** ISO-empty both ends — the pair analogue of `DateField`'s "" placeholder. */
private fun dateRangePlaceholder(): JsonValue =
    JsonObject(linkedMapOf<String, JsonValue>("from" to JsonString(""), "to" to JsonString("")))

/**
 * Reads a `{from, to}` object or a lenient two-element `[from, to]` array into the
 * canonical bare pair object.
 *
 * The pair is ORDERED: a LITERAL pair whose `from` sorts after its `to` is a decode
 * error. Same-variant ISO-8601 strings compare lexicographically in chronological
 * order, so Kotlin's `String.compareTo` — which compares UTF-16 code units — is the
 * ordinal compare the spec requires: no date parsing, no locale, total for every
 * variant. Only a literal pair is checked; a bound pair's ordering is a runtime
 * concern.
 */
private fun dateRangePair(v: JsonValue, path: String): JsonValue {
    val (from, to) =
        when (v) {
            is JsonObject -> {
                val f = v["from"]
                val t = v["to"]
                if (f == null || t == null) {
                    throw FuaranDecodeException(
                        FuaranDecodeException.WRONG_TYPE,
                        path,
                        "expected an object with from and to ISO-8601 strings",
                    )
                }
                f.str("$path.from") to t.str("$path.to")
            }
            is JsonArray ->
                if (v.items.size == 2) {
                    v.items[0].str("$path[0]") to v.items[1].str("$path[1]")
                } else {
                    throw FuaranDecodeException(
                        FuaranDecodeException.WRONG_TYPE,
                        path,
                        "expected a date-range pair ({from, to} object or [from, to] array)",
                    )
                }
            else ->
                throw FuaranDecodeException(
                    FuaranDecodeException.WRONG_TYPE,
                    path,
                    "expected a date-range pair ({from, to} object or [from, to] array)",
                )
        }
    if (from > to) {
        throw FuaranDecodeException(
            FuaranDecodeException.WRONG_TYPE,
            path,
            "date-range start '$from' is after end '$to' — a DateRange pair is ordered (from <= to); " +
                "ISO-8601 strings of one variant compare lexicographically, so swap the two values",
        )
    }
    return JsonObject(linkedMapOf<String, JsonValue>("from" to JsonString(from), "to" to JsonString(to)))
}

private fun decodeFilterItem(value: JsonValue, path: String): FilterItem {
    val o = value.obj(path)
    // 0.2.0 filters-unification: the chip's control is an ordinary FormFieldKind; its
    // absent `value` auto-binds Filter(name). Name decodes first so the synthesis can use it.
    val name = o.req("name", path).str("$path.name")
    return FilterItem(
        name = name,
        label = decodeTextSource(o.req("label", path), "$path.label"),
        kind = decodeFormFieldKind(o.req("kind", path), "$path.kind", ControlAutoBind.FilterChip(name)),
    )
}

private fun decodeLocalFlushTrigger(value: JsonValue, path: String): LocalFlushTrigger {
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
        "OnBlur" -> OnBlur
        "OnSubmit" -> OnSubmit
        "OnCommitAction" -> OnCommitAction
        "OnDebounce" -> OnDebounce(o.req("milliseconds", path).int("$path.milliseconds"))
        else -> unknownCase(t, path, "LocalFlushTrigger")
    }
}

// --------------------------------------------------------------------------- //
// Grid column / cell / width / static rows
// --------------------------------------------------------------------------- //

private fun decodeGridColumn(value: JsonValue, path: String): GridColumn {
    val o = value.obj(path)
    o.refuseNearMiss(path, mapOf("readOnly" to "editable: false (readOnly is its inverse)"))
    return GridColumn(
        // Field aliases: header / title → label, type → kind (the react-table prior).
        label = o.reqAliased("label", path, "header", "title").str("$path.label"),
        kind = decodeCellKind(o.reqAliased("kind", path, "type"), "$path.kind"),
        // 0.2.x — format/width omitted-when-default; `value` is a closure sentinel (dropped).
        format = o["format"]?.let { decodeValueFormat(it, "$path.format") } ?: NoValueFormat,
        width = o["width"]?.let { decodeColumnWidth(it, "$path.width") } ?: AutoWidth,
        field = o.optStr("field", path),
    )
}

/**
 * The tone-map field names a `TonedPill` cell accepts, canonical first. `map` is the
 * shortest honest name for a value→tone dictionary and the least descriptive one.
 */
private val TONE_MAP_KEYS = listOf("map", "toneMap", "tones")

/**
 * A `TonedPill`'s `map`: a string-keyed object whose VALUES are `ToneVariant`s.
 *
 * Routed through the shared [toneVariantOf] per entry, so the 3.6 tone aliases work
 * inside the map exactly as they do at a `tone` field. The refusal is RE-ISSUED rather
 * than passed through: [enumOf] reports "unrecognised ToneVariant '…'", which does not
 * say WHICH map entry is wrong — and "one of your tones is wrong" is not an actionable
 * report when the map has nine entries. The re-issue keeps the code, names the offending
 * KEY and value in the terms the author wrote them, and teaches the seven legal names. A
 * non-string value is a `WRONG_TYPE` from [str] and already reports at the right path.
 */
private fun decodeToneMap(value: JsonValue, path: String): Map<String, ToneVariant> {
    val o = value.obj(path)
    return o.members.mapValues { (key, v) ->
        val entryPath = "$path.$key"
        val raw = v.str(entryPath)
        try {
            toneVariantOf(raw, entryPath)
        } catch (e: FuaranDecodeException) {
            if (e.code != FuaranDecodeException.UNKNOWN_DU_CASE) throw e
            throw FuaranDecodeException(
                FuaranDecodeException.UNKNOWN_DU_CASE,
                entryPath,
                "tone-map value '$raw' for '$key' is not a ToneVariant; expected one of " +
                    ToneVariant.entries.joinToString(", ") { it.name },
            )
        }
    }
}

/**
 * The shared body of the canonical `TonedPill` case and the 16 `Pill`-tagged shorthand —
 * ONE reader, so the two spellings cannot drift apart in what they accept.
 */
private fun decodeTonedPill(o: JsonObject, path: String): TonedPillCell {
    val mapJson =
        TONE_MAP_KEYS.firstNotNullOfOrNull { o[it] }
            ?: throw FuaranDecodeException(FuaranDecodeException.MISSING_FIELD, "$path.map", "required field absent")
    return TonedPillCell(
        field = o.req("field", path).str("$path.field"),
        map = decodeToneMap(mapJson, "$path.map"),
        // `default` is omitted-when-Default (Phase 460); an absent key restores the
        // identity, and an aliased `Neutral` normalises to Default — two rules
        // composing, in that order.
        defaultTone = o.optStr("default", path)?.let { toneVariantOf(it, "$path.default") } ?: ToneVariant.Default,
    )
}

private fun decodeCellKind(value: JsonValue, path: String): CellKind {
    val o = value.obj(path)
    val tag = o.discriminator(path)
    // Lenient-ingest (WIRE_FORMAT 16, Phase 750): "pill" is the WORD for the thing, so a
    // declarative tone rule arrives tagged `Pill` more often than tagged `TonedPill`.
    // Before this phase those keys were accepted and DISCARDED — the author's whole
    // intent gone, silently, with no error to notice. Presence of a tone map is the
    // unambiguous tell: a closure `Pill` carries only `labelFn`/`toneFn` and can never
    // carry one.
    if (tag == "Pill" && TONE_MAP_KEYS.any { o[it] != null }) return decodeTonedPill(o, path)
    return when (val t = tag) {
        "TonedPill" -> decodeTonedPill(o, path)
        "Text" -> TextCell
        "Numeric" -> NumericCell
        "Date" -> DateCell
        "Editable" -> EditableCell
        "Checkbox" -> CheckboxCell
        "Button" -> ButtonCell(decodeTextSource(o.req("label", path), "$path.label"))
        "ButtonGroup" ->
            ButtonGroupCell(
                o.req("buttons", path).array("$path.buttons").mapIndexed { i, v ->
                    val b = v.obj("$path.buttons[$i]")
                    decodeTextSource(b.req("label", "$path.buttons[$i]"), "$path.buttons[$i].label")
                },
            )
        "Link" -> LinkCell
        "Pill" -> PillCell
        "Progress" -> ProgressCell
        "Custom" -> CustomCell
        else -> unknownCase(t, path, "CellKind")
    }
}

private fun decodeColumnWidth(value: JsonValue, path: String): ColumnWidth {
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
        "Auto" -> AutoWidth
        "Fixed" -> FixedWidth(o.req("pixels", path).int("$path.pixels"))
        "Flex" -> FlexWidth(o.req("weight", path).double("$path.weight"))
        else -> unknownCase(t, path, "ColumnWidth")
    }
}

private fun decodeStaticRows(value: JsonValue, path: String): StaticRows {
    val o = value.obj(path)
    return StaticRows(
        headers = o.req("headers", path).array("$path.headers").mapIndexed { i, v -> decodeTextSource(v, "$path.headers[$i]") },
        rows = o.req("rows", path).array("$path.rows").mapIndexed { i, row ->
            row.array("$path.rows[$i]").mapIndexed { j, cell -> decodeTextSource(cell, "$path.rows[$i][$j]") }
        },
        defaultSort = o["defaultSort"]?.let { decodeDefaultSort(it, "$path.defaultSort") },
        sortable = o.optBool("sortable", path),
    )
}

/**
 * An initial sort. Both members are closed: `column` is a zero-based header INDEX (`minimum: 0`,
 * so a negative is malformed rather than a from-the-end convention) and `direction` is the
 * `asc | desc` pair, which default-denies anything else.
 */
private fun decodeDefaultSort(value: JsonValue, path: String): DefaultSort {
    val o = value.obj(path)
    return DefaultSort(
        column = o.req("column", path).intAtLeast(0, "$path.column"),
        direction = wireEnumOf<SortDirection>(o.req("direction", path).str("$path.direction"), "$path.direction"),
    )
}

private fun decodeContentHash(value: JsonValue, path: String): ContentHash {
    val o = value.obj(path)
    return ContentHash(
        algorithm = o.req("algorithm", path).str("$path.algorithm"),
        hash = o.req("hash", path).str("$path.hash"),
        strictness = enumOf<HashStrictness>(o.req("strictness", path).str("$path.strictness"), "$path.strictness"),
    )
}

// --------------------------------------------------------------------------- //
// Drawing
// --------------------------------------------------------------------------- //

private fun decodeShape(value: JsonValue, path: String): Shape {
    val o = value.obj(path)
    // A missing style defaults to the all-inherited empty style (mirrors the core).
    val style = o["style"]?.let { decodeDrawStyle(it, "$path.style") } ?: DrawStyle()
    return when (val t = o.discriminator(path)) {
        "Group" ->
            GroupShape(
                children = o.req("children", path).array("$path.children").mapIndexed { i, v -> decodeShape(v, "$path.children[$i]") },
                style = style,
            )
        "Rectangle" ->
            RectangleShape(
                x = o.req("x", path).double("$path.x"),
                y = o.req("y", path).double("$path.y"),
                width = o.req("width", path).double("$path.width"),
                height = o.req("height", path).double("$path.height"),
                style = style,
                cornerRadius = o.optDouble("cornerRadius", path),
            )
        "Line" ->
            LineShape(
                x1 = o.req("x1", path).double("$path.x1"),
                y1 = o.req("y1", path).double("$path.y1"),
                x2 = o.req("x2", path).double("$path.x2"),
                y2 = o.req("y2", path).double("$path.y2"),
                style = style,
            )
        "Polyline" -> PolylineShape(decodePoints(o, path), style)
        "Polygon" -> PolygonShape(decodePoints(o, path), style)
        "Curve" ->
            CurveShape(
                commands = o.req("commands", path).array("$path.commands").mapIndexed { i, v -> decodeCurveCommand(v, "$path.commands[$i]") },
                style = style,
            )
        "Circle" ->
            CircleShape(
                cx = o.req("cx", path).double("$path.cx"),
                cy = o.req("cy", path).double("$path.cy"),
                r = o.req("r", path).double("$path.r"),
                style = style,
            )
        "Ellipse" ->
            EllipseShape(
                cx = o.req("cx", path).double("$path.cx"),
                cy = o.req("cy", path).double("$path.cy"),
                rx = o.req("rx", path).double("$path.rx"),
                ry = o.req("ry", path).double("$path.ry"),
                style = style,
            )
        "Label" ->
            LabelShape(
                x = o.req("x", path).double("$path.x"),
                y = o.req("y", path).double("$path.y"),
                text = decodeTextSource(o.req("text", path), "$path.text"),
                style = style,
            )
        else -> unknownCase(t, path, "Shape")
    }
}

private fun decodePoints(o: JsonObject, path: String): List<DrawPoint> =
    o.req("points", path).array("$path.points").mapIndexed { i, v -> decodeDrawPoint(v, "$path.points[$i]") }

private fun decodeDrawPoint(value: JsonValue, path: String): DrawPoint {
    val o = value.obj(path)
    return DrawPoint(o.req("x", path).double("$path.x"), o.req("y", path).double("$path.y"))
}

private fun decodeCurveCommand(value: JsonValue, path: String): CurveCommand {
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
        "MoveTo" -> MoveTo(decodeDrawPoint(o.req("to", path), "$path.to"))
        "LineTo" -> LineTo(decodeDrawPoint(o.req("to", path), "$path.to"))
        "CubicTo" ->
            CubicTo(
                control1 = decodeDrawPoint(o.req("control1", path), "$path.control1"),
                control2 = decodeDrawPoint(o.req("control2", path), "$path.control2"),
                to = decodeDrawPoint(o.req("to", path), "$path.to"),
            )
        "QuadraticTo" ->
            QuadraticTo(
                control = decodeDrawPoint(o.req("control", path), "$path.control"),
                to = decodeDrawPoint(o.req("to", path), "$path.to"),
            )
        "Close" -> Close
        else -> unknownCase(t, path, "CurveCommand")
    }
}

private fun decodeDrawStyle(value: JsonValue, path: String): DrawStyle {
    val o = value.obj(path)
    return DrawStyle(
        fill = o["fill"]?.let { decodeBindingString(it, "$path.fill") },
        opacity = o["opacity"]?.let { decodeBindingFloat(it, "$path.opacity") },
        stroke = o["stroke"]?.let { decodeBindingString(it, "$path.stroke") },
        strokeWidth = o["strokeWidth"]?.let { decodeBindingFloat(it, "$path.strokeWidth") },
        emphasis = o.optStr("emphasis", path),
        fontFamily = o.optStr("fontFamily", path),
        fontSize = o.optDouble("fontSize", path),
        textAnchor = o.optStr("textAnchor", path),
        // Phase 642 — keyed mark identity; omitted when absent.
        markId = o.optStr("markId", path),
    )
}

private fun decodeViewBox(value: JsonValue, path: String): ViewBox {
    val o = value.obj(path)
    return ViewBox(
        minX = o.req("minX", path).double("$path.minX"),
        minY = o.req("minY", path).double("$path.minY"),
        width = o.req("width", path).double("$path.width"),
        height = o.req("height", path).double("$path.height"),
    )
}

// --------------------------------------------------------------------------- //
// Fragments (holes / scalars / effects)
// --------------------------------------------------------------------------- //

private fun decodeHoleDecl(value: JsonValue, path: String): HoleDecl {
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
        "Value" ->
            ValueHole(
                name = o.req("name", path).str("$path.name"),
                space = decodeHoleValueSpace(o.req("space", path), "$path.space"),
                default = o["default"]?.let { decodeScalar(it, "$path.default") },
            )
        "Slot" -> SlotHole(o.req("name", path).str("$path.name"), o.optStr("kindConstraint", path))
        "Repeat" ->
            RepeatHole(
                name = o.req("name", path).str("$path.name"),
                countSpace = decodeHoleValueSpace(o.req("countSpace", path), "$path.countSpace"),
            )
        else -> unknownCase(t, path, "HoleDecl")
    }
}

private fun decodeHoleValueSpace(value: JsonValue, path: String): HoleValueSpace {
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
        "IntRange" -> IntRangeSpace(o.req("min", path).int("$path.min"), o.req("max", path).int("$path.max"))
        "FloatRange" -> FloatRangeSpace(o.req("min", path).double("$path.min"), o.req("max", path).double("$path.max"))
        "StringLen" -> StringLenSpace(o.req("minLen", path).int("$path.minLen"), o.req("maxLen", path).int("$path.maxLen"))
        "Enum" -> EnumSpace(o.strList("choices", path))
        "AnyString" -> AnyStringSpace
        else -> unknownCase(t, path, "HoleValueSpace")
    }
}

private fun decodeScalar(value: JsonValue, path: String): Scalar {
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
        "Int" -> IntScalar(o.req("value", path).int("$path.value"))
        "Float" -> FloatScalar(o.req("value", path).double("$path.value"))
        "Bool" -> BoolScalar(o.req("value", path).bool("$path.value"))
        "Str" -> StrScalar(o.req("value", path).str("$path.value"))
        else -> unknownCase(t, path, "Scalar")
    }
}

private fun decodeFragmentArgs(value: JsonValue, path: String): Map<String, FragmentArg> {
    val o = value.obj(path)
    return o.members.mapValues { (k, v) -> decodeFragmentArg(v, "$path.$k") }
}

private fun decodeFragmentArg(value: JsonValue, path: String): FragmentArg {
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
        "Int", "Float", "Bool", "Str" -> ScalarArg(decodeScalar(value, path))
        "SlotArg" -> SlotArg(decodeNode(o.req("tree", path), "$path.tree"))
        else -> unknownCase(t, path, "FragmentArg")
    }
}

private fun decodeEffect(value: JsonValue, path: String): EffectClass {
    val o = value.obj(path)
    return EffectClass(
        hostEffect = enumOf<HostEffect>(o.req("hostEffect", path).str("$path.hostEffect"), "$path.hostEffect"),
        determinism = enumOf<Determinism>(o.req("determinism", path).str("$path.determinism"), "$path.determinism"),
    )
}

// --------------------------------------------------------------------------- //

private fun unknownCase(discriminator: String, path: String, du: String): Nothing =
    throw FuaranDecodeException(
        FuaranDecodeException.UNKNOWN_DU_CASE,
        "$path.\$type",
        "'$discriminator' is not a recognised $du case",
    )
