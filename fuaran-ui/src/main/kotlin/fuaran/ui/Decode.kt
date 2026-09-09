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
 * An INTEGER slot (§7.1): a finite JSON number with no fractional part, inside the signed
 * 32-bit range. Deliberately narrower than [double] — see [FLOAT_SENTINELS].
 *
 * `3.0` decodes as `3` — the two denote the same integer, and refusing the first refuses a
 * document whose intent is unambiguous, for its spelling. `2.5` and `3000000000` are typed
 * refusals rather than the saturating cast this host used to apply; that cast produced a value
 * the author never wrote, silently, which is the same defect §20 closes one layer down in the
 * syntax.
 *
 * The discrimination is on the JSON AST's own case, never on a widening numeric conversion, so
 * a `true` cannot reach a numeric slot by way of a boolean-to-number coercion the platform
 * would happily perform.
 *
 * Two refusals, one code and one `$`-rooted path, because an author repairs both the same way —
 * by writing a different number. The second used to be no refusal at all: `toDouble().toInt()`
 * SATURATED `3000000000` to `Int.MAX_VALUE` and truncated `1.5` to `1`, so the slot received a
 * value the document never carried and every host downstream believed it. The corpus already
 * pins an integer slot as having no non-numeric form (`reject-binding-int-*`); silently reshaping
 * one that IS numeric but out of range is the same defect one step further in.
 */
private fun JsonValue.int(path: String): Int {
    val number =
        unwrapStaticEnvelope() as? JsonNumber
            ?: throw FuaranDecodeException(
                FuaranDecodeException.WRONG_TYPE,
                path,
                "expected a JSON number (an integer slot has no non-finite form, so the 'NaN' / " +
                    "'Infinity' / '-Infinity' sentinels are not accepted here)",
            )
    // The lexeme is named in the message: the author's repair is to change the NUMBER, and the
    // number they wrote is the one thing this refusal knows and they do not.
    return number.toIntOrNull()
        ?: throw FuaranDecodeException(
            FuaranDecodeException.WRONG_TYPE,
            path,
            "expected a JSON integer this platform can hold, got '${number.raw}' — an integer " +
                "slot carries neither a fractional value nor one outside the signed 32-bit range",
        )
}

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

/**
 * 3.6.23 — one OPTIONAL, POSITIVE integer slot, read at decode.
 *
 * The floor is a DECODE RULE rather than a type because this format has no refined-integer type; it
 * is the same rule `SrcSetEntry.width` and `Switch.autoAdvanceMs` already stand on, and it is
 * mirrored by `minimum: 1` in the published JSON Schema so the two expressions of the contract
 * agree. The strict integer reader runs FIRST and refuses a fraction, a 7 sentinel string and
 * anything outside 7.1's signed 32-bit slot, so what is left for this guard is the sign alone.
 */
private fun JsonObject.optPositive(key: String, path: String, expectation: String): Int? =
    this[key]?.let {
        val n = it.int("$path.$key")
        if (n < 1) {
            throw FuaranDecodeException(FuaranDecodeException.WRONG_TYPE, "$path.$key", expectation)
        }
        n
    }

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
        // 3.1 the tooltip trait (Phase 1112) — a node-level TRAIT read from the ENVELOPE, never
        // from inside `kind`. `ButtonSpec`'s legacy host-only `tooltip` slot is not a second
        // spelling of it: a `tooltip` key inside a `kind` object stays an unknown key, tolerated
        // and ignored under rule 2, precisely because nothing here goes looking for one there.
        //
        // Read through the ordinary TextSource reader, so the canonical BARE STRING and the
        // `{"$type":"Literal"}` envelope both land, and `42` is WRONG_TYPE at `$.tooltip` rather
        // than a hint stringified out of a JSON type — a fabrication no downstream check catches.
        val tooltip = obj["tooltip"]?.let { decodeTextSource(it, "$path.tooltip") }
        return Node(
            id = id,
            kind = kind,
            style = style,
            state = state,
            accessibility = accessibility,
            tooltip = tooltip,
            // Phase 1535 — the conditional-presence TRAIT, an ordinary `Binding<bool>` slot beside
            // the tooltip. The 3.6 bare-scalar coercion reaches it like any other binding slot.
            visible = obj["visible"]?.let { decodeBindingBool(it, "$path.visible") },
        )
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
        // Phase 1472 — omitted at `auto`, the inherited direction. Neither a non-string nor an
        // unrecognised token falls back to `auto`: a document declaring a direction the host cannot
        // read must not be rendered in the opposite one in silence.
        direction = o.optStr("direction", path)?.let { enumOf<TextDirection>(it, "$path.direction") }
            ?: TextDirection.auto,
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

/**
 * The `Accessibility` trait's near-miss set (WIRE_FORMAT 3.1 "Near-miss slot names are refused,
 * not ignored") - the 3.2 grid narrowing at the position where its cost is highest.
 *
 * This trait has NO VISIBLE OUTPUT. A mislabelled column is on screen; an ignored `ariaLabel`
 * looks identical to an honoured one from every side, so the refusal is the only feedback that
 * can ever arrive.
 *
 * Refused rather than ALIASED, and note the argument differs from the grid's. There the names
 * were not synonyms; here `ariaLabel` IS one, so admission turns on the other half of the lenient
 * profile's rule - a shorthand earns its place by being a genuine assist to the emitting model,
 * and a six-character key rename is not one. `live` settles it: the HTML idiom it comes from also
 * spells a BOOLEAN, so an alias would bind a possibly-boolean prior onto a closed token set.
 *
 * Three families, grouped by the slot each points at: the ARIA attribute name, its camelCase
 * spelling, and the un-prefixed or un-cased slot name. `mapOf` preserves insertion order, and the
 * order is identical in every host, so which defect surfaces first is deterministic.
 */
private val ACCESSIBILITY_NEAR_MISSES =
    mapOf(
        "aria-label" to "label",
        "ariaLabel" to "label",
        "aria-labelledby" to "labelledBy",
        "ariaLabelledBy" to "labelledBy",
        "labelledby" to "labelledBy",
        "aria-describedby" to "describedBy",
        "ariaDescribedBy" to "describedBy",
        "describedby" to "describedBy",
        "aria-role" to "role",
        "ariaRole" to "role",
        "aria-live" to "liveRegion",
        "ariaLive" to "liveRegion",
        "live" to "liveRegion",
        "liveregion" to "liveRegion",
        "aria-hidden" to "hidden",
        "ariaHidden" to "hidden",
    )

private fun decodeAccessibility(value: JsonValue, path: String): Accessibility {
    val o = value.obj(path)
    // The near-miss check runs BEFORE the slot reads, matching the FormField ordering, so a trait
    // carrying both `ariaLabel` and a well-formed `label` still names the ignored key rather than
    // decoding half the author's intent in silence.
    o.refuseNearMiss(path, ACCESSIBILITY_NEAR_MISSES)
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
                // Phase 1473 — the print-break pair, omitted at `false` and never
                // truthiness-coerced.
                breakBefore = o.optBool("breakBefore", path) ?: false,
                keepTogether = o.optBool("keepTogether", path) ?: false,
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
                // 3.6.11 — omitted at `Modal`, the blocking modality every pre-modality document
                // meant. Neither a non-string nor an unrecognised token falls back: a document
                // asking for a popover and getting a blocking modal has been answered with a
                // different affordance.
                modality = o.optStr("modality", path)?.let { enumOf<ModalityKind>(it, "$path.modality") }
                    ?: ModalityKind.Modal,
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
                    val cp = "$path.cases[$i]"
                    val c = v.obj(cp)
                    // Phase 1535 — EXACTLY ONE of `match` and `when`. "Both" is refused rather than
                    // resolved by precedence (a precedence rule would have to be specified, agreed
                    // on every host and remembered by every author, for a document nobody meant to
                    // write); "neither" keeps the pre-1535 MISSING_FIELD at `.match`, which is what
                    // the corpus pins — a case naming no condition is not one that never matches,
                    // and skipping it silently is the class of silence the predicate form was added
                    // to remove.
                    val whenSlot = c["when"]
                    val condition: SwitchCondition =
                        if (c["match"] != null && whenSlot != null) {
                            throw FuaranDecodeException(
                                FuaranDecodeException.WRONG_TYPE,
                                "$cp.when",
                                "expected exactly one of 'match' and 'when' \u2014 a precedence rule " +
                                    "between them would have to be agreed on every host for a " +
                                    "document nobody meant to write",
                            )
                        } else if (whenSlot != null) {
                            WhenCondition(decodeBindingBool(whenSlot, "$cp.when"))
                        } else {
                            MatchCondition(c.req("match", cp).str("$cp.match"))
                        }
                    SwitchCase(
                        condition = condition,
                        child = decodeNode(c.req("child", cp), "$cp.child"),
                    )
                },
                default = decodeNode(o.req("default", path), "$path.default"),
                // Phase 1122 — a POSITIVE INTEGER count of milliseconds. Non-positive is refused
                // rather than canonicalised: `0` is what an emitter reaches for to mean "off" and
                // absence is already that spelling, so rewriting it would make two document shapes
                // mean one thing and tell the emitter nothing about its misreading. Fractional is
                // refused separately — the slot is an integer count, and a decoder truncating where
                // another rounded would leave two hosts disagreeing about a document neither
                // refused. Both fall out of the strict integer reader plus the bound.
                autoAdvanceMs = o["autoAdvanceMs"]?.let {
                    val ms = it.int("$path.autoAdvanceMs")
                    if (ms < 1) {
                        throw FuaranDecodeException(
                            FuaranDecodeException.WRONG_TYPE,
                            "$path.autoAdvanceMs",
                            "expected a positive millisecond interval \u2014 an absent key is already " +
                                "the spelling for off",
                        )
                    }
                    ms
                },
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
                // 3.6.1 — omitted-when-default, decoded independently of `trend`; an inert
                // declaration round-trips rather than being dropped. An unrecognised spelling
                // (`Neutral` included, and on purpose) fails UNKNOWN_DU_CASE with the canonical
                // two-name list, because `enumOf` builds its expected list from the case set and
                // the case set is the accepted wire set.
                trendPolarity =
                    o.optStr("trendPolarity", path)?.let { enumOf<TrendPolarity>(it, "$path.trendPolarity") }
                        ?: TrendPolarity.HigherIsBetter,
            )
        "Badge" ->
            Badge(
                label = decodeTextSource(o.req("label", path), "$path.label"),
                variant = badgeVariantOf(o.req("variant", path).str("$path.variant"), "$path.variant"),
            )
        // Field alias: data → source (the chart-library prior).
        // 5 + Phase 1099 — a typed float SEQUENCE slot, so the elements are typed too.
        "Sparkline" -> Sparkline(source = decodeBindingFloatSeq(o.reqAliased("source", path, "data"), "$path.source"))
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
                // 3.6.2 — three identity-defaulted presentation tokens. Absent restores the
                // pre-phase behaviour exactly, which is what makes the untouched `image-1` fixture
                // proof of the claim rather than a restatement of it.
                fit = o.optStr("fit", path)?.let { enumOf<ImageFit>(it, "$path.fit") } ?: ImageFit.Natural,
                aspectRatio =
                    o.optStr("aspectRatio", path)?.let { enumOf<ImageAspect>(it, "$path.aspectRatio") }
                        ?: ImageAspect.Natural,
                loading = o.optStr("loading", path)?.let { enumOf<ImageLoading>(it, "$path.loading") } ?: ImageLoading.Eager,
                // 3.6.3 — CONTENT, so an ordinary optional field and a full TextSource. Narrowing
                // it to a plain string costs nothing until somebody needs a locale, which is why
                // the corpus pins an `I18n` caption on this slot specifically.
                caption = o["caption"]?.let { decodeTextSource(it, "$path.caption") },
                // 3.6.4 — the missing-list-field class: ABSENT means the empty list, and a present
                // `null` is refused rather than read as a second spelling of absence (the `array`
                // reader raises WRONG_TYPE on JsonNull, which is exactly the refusal wanted here).
                // `mapIndexed` preserves the AUTHORED order; nothing sorts.
                srcSet =
                    o["srcSet"]?.array("$path.srcSet")?.mapIndexed { i, v -> decodeSrcSetEntry(v, "$path.srcSet[$i]") }
                        ?: emptyList(),
                // 3.6.5 — omitted at false; the stringified boolean is refused, not coerced.
                expandable = o.optBool("expandable", path) ?: false,
            )
        "Media" ->
            Media(
                // 3.6.6 — REQUIRED. A transport has no decorative case, and there is no value to
                // default to that would not be a fabricated name for someone else's recording.
                label = decodeTextSource(o.req("label", path), "$path.label"),
                src = decodeBindingString(o.req("src", path), "$path.src"),
                kind = decodeMediaKind(o.req("kind", path), "$path.kind"),
                // Omitted at TRUE — the inverted polarity `Toast.dismissable` also takes.
                controls = o.optBool("controls", path) ?: true,
                loop = o.optBool("loop", path) ?: false,
                // 3.6.6 text tracks (Phase 1110) — the missing-list-field class, exactly as
                // `Image.srcSet` above: ABSENT means the empty list and a present `null` is
                // refused. `mapIndexed` preserves the AUTHORED order, and here that is normative
                // rather than incidental — a reader picks a track from a menu the user agent
                // builds in document order, so re-sorting would be rewriting someone else's menu.
                tracks =
                    o["tracks"]?.array("$path.tracks")?.mapIndexed { i, v -> decodeTrackEntry(v, "$path.tracks[$i]") }
                        ?: emptyList(),
                // An ordinary optional TextSource: absent means the document offers no transcript,
                // which is a different statement from offering an empty one.
                transcript = o["transcript"]?.let { decodeTextSource(it, "$path.transcript") },
            )
        // 3.6.8 (Phase 1111). `title` is required — a browsing context has no decorative case —
        // and the permission list omits at EMPTY, which means total denial.
        "Embed" ->
            Embed(
                src = decodeBindingString(o.req("src", path), "$path.src"),
                title = decodeTextSource(o.req("title", path), "$path.title"),
                // REUSES ImageAspect: pure layout ratios, bare strings on the wire, so the type
                // name reaches no document and a parallel enum would be two sets to keep in step.
                aspectRatio =
                    o.optStr("aspectRatio", path)?.let { enumOf<ImageAspect>(it, "$path.aspectRatio") }
                        ?: ImageAspect.Natural,
                // A BARE enum per element, so the refusal reports at the ELEMENT's own path with
                // no `.$type` suffix. Both refusals are load-bearing and neither may become a
                // silent drop: a bare `true` cannot be read as a granted permission (a host would
                // have to invent WHICH one it names), and dropping an unrecognised token would
                // turn a document asking for something this vocabulary cannot name into one
                // asking for LESS — which reads as success.
                permissions =
                    o["permissions"]?.array("$path.permissions")?.mapIndexed { i, v ->
                        enumOf<EmbedPermission>(v.str("$path.permissions[$i]"), "$path.permissions[$i]")
                    } ?: emptyList(),
            )
        // 3.6.12 (Phase 1120) — the format's first self-referential shape. `onSelect` is a
        // closure and is dropped, as every other handler slot in this projection is.
        "Tree" ->
            Tree(
                items =
                    o.req("items", path).array("$path.items").mapIndexed { i, v ->
                        decodeTreeItem(v, "$path.items[$i]", 1)
                    },
                expandedStateKey = o.optStr("expandedStateKey", path),
                selectionStateKey = o.optStr("selectionStateKey", path),
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
        "FileUpload" -> {
            // Phase 1117 — the empty string is a name no host registers, so a document carrying it
            // describes an upload that can never stream. Refused rather than read as absence: that
            // coercion silently turns an upload the author meant to stream into a client-only one,
            // while every visible thing about the control still works.
            val destination = o.optStr("destination", path)
            if (destination == "") {
                throw FuaranDecodeException(
                    FuaranDecodeException.WRONG_TYPE,
                    "$path.destination",
                    "expected a registered destination name \u2014 an absent member is already the " +
                        "spelling for an upload that streams nowhere",
                )
            }
            FileUpload(
                accept = o.strList("accept", path),
                label = decodeTextSource(o.req("label", path), "$path.label"),
                multiple = o.req("multiple", path).bool("$path.multiple"),
                disabled = o["disabled"]?.let { decodeBindingBool(it, "$path.disabled") },
                // Phase 1115 — read through the STRICT bool reader: each slot decides whether a
                // whole ingress route exists, and a truthiness read would open a drop target on
                // `"no"` and `"false"` alike.
                dropTarget = o.optBool("dropTarget", path) ?: false,
                acceptPaste = o.optBool("acceptPaste", path) ?: false,
                // Phase 1116 — OPTIONAL, not omit-at-default: an absent member asks for the ordinary
                // picker, which is not one of the two devices wearing a default, and an unrecognised
                // value MUST NOT fall back to either device.
                capture = o.optStr("capture", path)?.let { enumOf<CaptureSource>(it, "$path.capture") },
                destination = destination,
                // Phase 1548 — the two declared ceilings. `maxFiles` is NOT cross-checked against
                // `multiple`: beside `"multiple":false` the member is INERT by specification, and a
                // host refusing it would reject documents every other host accepts, which is the
                // divergence a declared ceiling exists to remove.
                maxBytes =
                    o.optPositive(
                        "maxBytes",
                        path,
                        "expected a positive per-file byte ceiling — a ceiling of zero is not a " +
                            "small ceiling but an upload that can accept no file at all, and an absent " +
                            "member is already the spelling for no ceiling",
                    ),
                maxFiles =
                    o.optPositive(
                        "maxFiles",
                        path,
                        "expected a positive selection-count ceiling — a multiple upload admitting " +
                            "zero files has no reachable selection, and an absent member is already the " +
                            "spelling for no ceiling",
                    ),
            )
        }
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
                // Phase 1473 — the paginated-media pair, on Box's terms.
                keepRowsTogether = o.optBool("keepRowsTogether", path) ?: false,
                repeatHeader = o.optBool("repeatHeader", path) ?: false,
                // Phase 1123 — a bool, omitted at `false`, never truthiness-coerced: the slot
                // decides whether a whole affordance exists.
                exportable = o.optBool("exportable", path) ?: false,
                // Phase 1125 — separate decoder arms, so a wrong type on either is reported at its
                // own path.
                transferInKey = o.optStr("transferInKey", path),
                transferOutKey = o.optStr("transferOutKey", path),
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
                // Phase 1490 (4l) — omitted when the chart declares none.
                annotations = o["annotations"]?.array("$path.annotations")?.mapIndexed { i, v ->
                    decodeChartAnnotation(v, "$path.annotations[$i]")
                },
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
 * The `Binding<string list>` slot (Phase 1121's `Tokens.value`, and the multi-select `values` it
 * shares its type with).
 *
 * The LIFT is the tempting coercion and it is exactly wrong: a document saying `"urgent"` where the
 * wire says a list has a misunderstanding of the slot, and silently agreeing with it would let an
 * emitter ship a token field that can only ever hold one token while every host round-tripped it
 * perfectly. So a bare string here is `WRONG_TYPE` at the slot's own path rather than a one-element
 * list.
 */
private fun decodeBindingStringList(value: JsonValue, path: String): Binding =
    decodeBindingScalar(value, path) { v, p ->
        val arr = v as? JsonArray
            ?: throw FuaranDecodeException(
                FuaranDecodeException.WRONG_TYPE,
                p,
                "expected a list of strings \u2014 a bare string is not lifted into a one-element " +
                    "list, since that would let an emitter ship a token field that can only ever " +
                    "hold one token",
            )
        arr.items.forEachIndexed { i, item -> item.str("$p[$i]") }
    }

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

/**
 * The residual-opaque sentinel (WIRE_FORMAT.md 5). A pre-429 encoder wrote it wherever a typed
 * `Static` payload is now emitted, and every host stays decode-accepting of it INDEFINITELY —
 * every tree persisted, permalinked or op-stream-logged before those phases carries it.
 */
private const val OPAQUE_SENTINEL: String = "<opaque>"

/**
 * A `Binding<float seq>` slot — today only `Sparkline.source` (WIRE_FORMAT.md 5, Phase 1099).
 *
 * **The element type is the point.** [decodeBindingScalar] above types the `Static` payload of a
 * SCALAR slot, so `Metric.value: "lots"` is refused; a SEQUENCE slot needs the same check one
 * level in, and without it a spark array carrying a string, a boolean or an object decoded here
 * with its wrong-typed elements preserved verbatim — accepted on this surface and nowhere else.
 * `spark-nonfinite-sentinel` passed throughout and proved nothing about the element type, because
 * the untyped path never looked at an element at all.
 *
 * Each element goes through the SAME [double] reader every other float slot uses, so §7's three
 * exact sentinel strings are admitted at an element exactly as they are at a scalar, and no
 * looser spelling is (`"nan"` is `WRONG_TYPE`, because the accept SET is the contract and there
 * is no parse left to get wrong).
 *
 * **Two read-compat payloads are accepted and NOT typed** (§5, indefinitely — not a migration
 * window): a `null`, which is the pre-429 F# `box ([]: 'a list)` null reference an older encoder
 * wrote for an empty seq, and the [OPAQUE_SENTINEL] string. Both denote the empty feed. Any OTHER
 * non-array payload is refused as the wrong type, which is what keeps the two exceptions
 * enumerated rather than a hole shaped like "a string is fine here".
 *
 * The payload PATH is computed from the shape that actually arrived, so the refusal names the
 * position the reference host names: `…source.value[i]` under the canonical `Static` envelope and
 * `…source[i]` under §3.6's bare-array coercion. [decodeBinding] collapses both into a
 * [StaticBinding], which is why the discrimination happens here, before it is called.
 */
private fun decodeBindingFloatSeq(value: JsonValue, path: String): Binding {
    val payloadPath =
        if (value is JsonObject && (value["\$type"] as? JsonString)?.value == "Static") "$path.value" else path
    val b = decodeBinding(value, path)
    if (b is StaticBinding) {
        when (val v = b.value) {
            is JsonNull -> {}
            is JsonString ->
                if (v.value != OPAQUE_SENTINEL) {
                    // Not a recognised read-compat spelling — refuse it as the array it is not,
                    // rather than letting an arbitrary string stand in for a series.
                    v.array(payloadPath)
                }
            else -> v.array(payloadPath).forEachIndexed { i, e -> e.double("$payloadPath[$i]") }
        }
    }
    return b
}

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
        // Phase 1533 — the declared `grain` is the one wire field, optional, absent meaning
        // `Second`. Present-but-unreadable is a REFUSAL rather than a silent fallback to the
        // default: a document naming a grain the host cannot honour must not be rendered at a
        // neighbouring resolution in silence.
        "Now" -> NowBinding(o.optStr("grain", path)?.let { enumOf<TimeGrain>(it, "$path.grain") })
        "I18n" -> I18nBinding(o.req("key", path).str("$path.key"), o["args"]?.payloadMap("$path.args"))
        "Local" -> {
            // 3.3.3 — the buffer's own codec REPLACES the identity on both sides: `format` renders
            // through it, `parse` inverts it. The admitted set is therefore the NumberFormat cases
            // with a TOTAL, LOCALE-INDEPENDENT inverse, and today that is `Number` alone. Every
            // other case is refused with a stated reason rather than by omission — `Currency`
            // prepends a locale-chosen symbol, `Date`'s styles are locale renditions with no parse,
            // and `Percent` (the one that looks admissible) needs a x100 scale whose IEEE round trip
            // is not exact, so admitting it would mean specifying a rounding to the bit on every
            // host.
            val codec = o["codec"]?.let { c ->
                val decoded = decodeNumberFormat(c, "$path.codec")
                if (decoded !is NumberNumberFormat) {
                    throw FuaranDecodeException(
                        FuaranDecodeException.WRONG_TYPE,
                        "$path.codec",
                        "expected a Format with a total, locale-independent inverse \u2014 Number " +
                            "alone, since whatever the buffer renders it must also parse back from " +
                            "what the reader typed",
                    )
                }
                decoded
            }
            val hasOnCommit = o["onCommit"] != null
            val commitTo = o.optStr("commitTo", path)
            // Mutually exclusive, and a refusal rather than a precedence rule: the wire cannot carry
            // the closure — it is `"<closure>"` and nothing more — so a host honouring `onCommit`
            // and a host honouring `commitTo` would write to different places from identical bytes.
            if (hasOnCommit && commitTo != null) {
                throw FuaranDecodeException(
                    FuaranDecodeException.WRONG_TYPE,
                    "$path.commitTo",
                    "expected exactly one of 'onCommit' and 'commitTo' \u2014 the wire cannot carry " +
                        "the closure, so two hosts would write to different places from identical bytes",
                )
            }
            LocalBinding(
                flushOn = o["flushOn"]?.let { decodeLocalFlushTrigger(it, "$path.flushOn") } ?: OnBlur,
                initialFrom = decodeBinding(o.req("initialFrom", path), "$path.initialFrom"),
                codec = codec,
                commitTo = commitTo,
                hasOnCommit = hasOnCommit,
            )
        }
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
        // Phase 1534 (3.3.2) — ONE scalar expression evaluated to ONE value. The expression itself
        // is held as raw JSON for the reason a Transform pipeline is: the ColExpr algebra is owned
        // by the Fuaran.Core codec, and a render projection does not decompose content the host
        // does not own. The two REFUSALS still apply, because both are decidable by walking the
        // document — and both hold because an Expr HAS NO ROW. Left admitted, each would decode to
        // an expression whose evaluation could only ever fail, once per render, on every host.
        "Expr" -> {
            val expr = o.req("expr", path)
            val params = o["params"]?.let { decodeTransformParams(it, "$path.params") }
            val exprPath = "$path.expr"
            firstColReference(expr)?.let { offender ->
                throw FuaranDecodeException(
                    FuaranDecodeException.WRONG_TYPE,
                    exprPath,
                    "expected no column reference \u2014 a Binding.Expr evaluates against its params " +
                        "alone and has no frame for '$offender' to read; the remedy is a different " +
                        "BINDING, not a different spelling, and Binding.Transform is the case that " +
                        "supplies the frame",
                )
            }
            val bound = (params ?: emptyList()).map { it.name }.toSet()
            // Statically decidable HERE where it is not for a Transform, whose unbound filter params
            // are PRUNED under the deliberate "unset chip => no constraint" leniency: an Expr has no
            // step to prune and no rows to fall back on, so an unbound reference has no value it
            // could ever take.
            firstUnboundParam(expr, bound)?.let { unbound ->
                throw FuaranDecodeException(
                    FuaranDecodeException.WRONG_TYPE,
                    exprPath,
                    "expected every referenced param to be bound by the binding's own params list " +
                        "\u2014 '$unbound' is not",
                )
            }
            ExprBinding(expr = expr, params = params)
        }
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
 * `lenient/lenient-transform-source-state-rows`.
 *
 * A BARE `State` envelope - `{"$type":"State","key":k}`, carrying no payload member - is ACCEPTED:
 * WIRE_FORMAT.md 16 reads it as a LIVE source over the EMPTY initial snapshot, exactly as the
 * `"defaultValue": []` spelling beside it. It was refused here (the retired
 * `reject/reject-transform-source-empty-wrapper` was the pin), which was correct while nothing else
 * could fill the slot; under 24.4 a SIBLING reader's declaration fills it, so the refusal was
 * rejecting the most direct spelling of "I read this key and carry no data of my own" - the one
 * FUARAN106's remedy text tells an author to write.
 *
 * A `Static` or `Bound` envelope carrying no payload is a different thing entirely and is STILL
 * refused: neither names a live slot, so there is nothing for a sibling reader to seed and the
 * transform genuinely has no data - the grid would render empty with no indication that a source
 * was ever declared.
 *
 * The value is returned UNCHANGED - the canonical form keeps the envelope, so this validates
 * rather than rewrites.
 */
private fun unwrapTransformSource(value: JsonValue, path: String): JsonValue {
    val o = value as? JsonObject ?: return value
    val payloadKey =
        when ((o["\$type"] as? JsonString)?.value) {
            // 16 - the bare wrapper names the live slot, so it needs no payload.
            "State" -> null
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
        // Phase 1536 — the route is a TextSource, so a tree can name a destination it computes
        // from what the reader is looking at. The bare JSON string IS `Literal`'s canonical form,
        // so every document written before the widening decodes exactly as it did — aliases
        // included, since they resolve before the value is decoded. `target` is omitted at `Self`.
        "Navigate" -> {
            val v =
                o["route"] ?: o["href"] ?: o["url"] ?: o["to"]
                    ?: throw FuaranDecodeException(FuaranDecodeException.MISSING_FIELD, "$path.route", "required field absent")
            NavigateAction(
                route = decodeTextSource(v, "$path.route"),
                target = o.optStr("target", path)
                    ?.let { enumOf<NavigateTarget>(it, "$path.target") } ?: NavigateTarget.Self,
            )
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
        // Phase 1126 — the payload is a TextSource; the bare string IS `Literal`'s canonical form,
        // so the explicit envelope normalises down to it here as at every other text slot (16).
        // Never coerced from a non-text JSON value: a host reading the widening as "this member is
        // now open" would put a JSON literal on the reader's clipboard.
        "WriteToClipboard" -> WriteToClipboardAction(decodeTextSource(o.req("text", path), "$path.text"))
        // Phase 1124 — the payload-free print, and the ONE action arm strict about unrecognised
        // members. Everywhere else in this format an unknown member is one the reading host has not
        // learned yet, and dropping it is the forward-compatible answer; here there is nothing to
        // learn — page range, size, margins and copies are the host's page setup and the reader's
        // dialogue — so accepting `{"$type":"Print","pageRange":"1-3"}` would leave the emitter
        // believing it had constrained a printing it had not. The refusal names the offending
        // member's own path, taking the FIRST in sorted order so which member is named is
        // deterministic rather than a function of map iteration order.
        "Print" -> {
            val extras = o.members.keys.filter { it != "\u0024type" }.sorted()
            if (extras.isNotEmpty()) {
                throw FuaranDecodeException(
                    FuaranDecodeException.WRONG_TYPE,
                    "$path.${extras.first()}",
                    "expected no member beside \$type \u2014 Print takes no payload",
                )
            }
            PrintAction
        }
        // Phase 1537 — ask, then act. The DEPTH-ONE REFUSAL is the substance of this arm: a Confirm
        // reachable from either continuation is refused, and the check walks the DECODED
        // continuation rather than its immediate `$type`, so a nested confirm inside a Chain is
        // caught by the same line that catches a bare one. A dialogue that answers a dialogue is a
        // modal stack the reader cannot escape, and it says nothing one question does not.
        "Confirm" -> {
            val prompt = decodeTextSource(o.req("prompt", path), "$path.prompt")
            val onConfirm = decodeAction(o.req("onConfirm", path), "$path.onConfirm")
            refuseNestedConfirm(onConfirm, "$path.onConfirm")
            val onCancel = o["onCancel"]?.let {
                val decoded = decodeAction(it, "$path.onCancel")
                refuseNestedConfirm(decoded, "$path.onCancel")
                decoded
            }
            ConfirmAction(prompt = prompt, onConfirm = onConfirm, onCancel = onCancel)
        }
        // Phase 1537 — a bare node id, the CommitLocal shape. It addresses a node in THIS document,
        // so there is nothing for a binding to compute.
        "Focus" -> FocusAction(o.req("nodeId", path).str("$path.nodeId"))
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
        // 3.6.7 — column-fill. `cols` is REQUIRED and POSITIVE, on the 3.6.4 srcSet width-floor
        // pattern: `column-count: 0` is invalid CSS, so a container declaring it would fall back to
        // whatever the host stylesheet last said and the wire would be carrying a host-defined
        // layout.
        //
        // No auto-column leniency here, unlike `Grid` above, and the asymmetry is deliberate rather
        // than an omission: `Grid` canonicalises a column-less spec to `Auto` because the language
        // already owns that concept, whereas `Auto` is a ROW-fill mode — rewriting a masonry into it
        // would discard the author's intent rather than recover it.
        "Masonry" -> {
            val cols = o.reqAliased("cols", path, "columns").int("$path.cols")
            if (cols <= 0) {
                throw FuaranDecodeException(
                    FuaranDecodeException.WRONG_TYPE,
                    "$path.cols",
                    "expected a positive integer column count",
                )
            }
            MasonryLayout(cols = cols, gap = o.optInt("gap", path))
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
        // Phase 1533 — `unit` is OPTIONAL here and its absence is the auto-selection request, not a
        // default. Present-but-unreadable is still a refusal.
        "Since" -> SinceNumberFormat(o.optStr("unit", path)?.let { enumOf<RelativeTimeUnit>(it, "$path.unit") })
        "Duration" ->
            DurationNumberFormat(
                unit = enumOf<DurationUnit>(o.req("unit", path).str("$path.unit"), "$path.unit"),
                style = enumOf<DurationStyle>(o.req("style", path).str("$path.style"), "$path.style"),
            )
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
        // 3.6.9 (Phase 1113) — the searchable form of `Choice`, and its slots are `Choice`'s
        // DELIBERATELY: a document migrating between the two changes its `$type` and nothing else,
        // so a different value contract here would break exactly that migration. An async
        // suggestion feed needs no vocabulary — a `Query` in the ordinary `options` slot IS it.
        "Combobox" ->
            ComboboxField(
                options = decodeBinding(o.req("options", path), "$path.options"),
                value = valueOr(JsonNull),
                // Omits at false, and the polarity is load-bearing: the SHORTEST combobox document
                // is the CONSTRAINED one. A non-boolean is WRONG_TYPE and never coerced — `"yes"`,
                // `"no"` and `"false"` are all non-empty, so a truthiness read would widen the
                // field on two of the three.
                allowFreeText = o.optBool("allowFreeText", path) ?: false,
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
        // Phase 1121 — every member OPTIONAL, and `allowFreeText` omits at TRUE. The one decode
        // refusal is the control that CANNOT EXIST: free text denied and no suggestion source, so
        // no gesture could ever put a token in. It is refused at `allowFreeText` rather than at
        // `suggestions`, because the member that was WRITTEN is the one naming the impossible
        // state — an absent `suggestions` is the ordinary open token box.
        "Tokens" -> {
            val suggestions = o["suggestions"]?.let { decodeBinding(it, "$path.suggestions") }
            val allowFreeText = o.optBool("allowFreeText", path) ?: true
            if (!allowFreeText && suggestions == null) {
                throw FuaranDecodeException(
                    FuaranDecodeException.WRONG_TYPE,
                    "$path.allowFreeText",
                    "a Tokens field admitting no free text needs a suggestion source \u2014 with " +
                        "neither, no gesture could ever put a token into it",
                )
            }
            TokensField(
                value = valueOr(JsonArray(emptyList()), ::decodeBindingStringList),
                suggestions = suggestions,
                allowFreeText = allowFreeText,
            )
        }
        // Phase 1130 — `max` IS the scale, so it is required and a value below 1 is refused rather
        // than clamped. Note the asymmetry the corpus pins: the SCALE is refused here and the VALUE
        // is not, because a bound value is invisible to a decoder and a rule enforced only on
        // literals would be two rules wearing one name.
        "Rating" -> {
            val scale = o.req("max", path).int("$path.max")
            if (scale < 1) {
                throw FuaranDecodeException(
                    FuaranDecodeException.WRONG_TYPE,
                    "$path.max",
                    "a rating scale of at least 1 \u2014 a scale with no positions has nothing to " +
                        "draw and no keystroke that could change anything",
                )
            }
            RatingField(
                value = valueOr(JsonNumber("0"), ::decodeBindingFloat),
                max = scale,
                // Governs ENTRY granularity, never display: a host must not quantise a resolved
                // value to it.
                allowHalf = o.optBool("allowHalf", path) ?: false,
            )
        }
        // Phase 1130 — only the STATIC case is judged here, and the split is recorded rather than
        // hidden: a State / Query / Selection binding carries its text from outside the document,
        // where a decoder cannot see it.
        "Color" -> {
            val v = valueOr(JsonString("#000000"))
            val literal = (v as? StaticBinding)?.value as? JsonString
            if (literal != null && !isHexColour(literal.value)) {
                throw FuaranDecodeException(
                    FuaranDecodeException.WRONG_TYPE,
                    "$path.value",
                    "a `#rrggbb` colour \u2014 the one shape a native colour input can hold, so a " +
                        "literal outside it names a colour this control could never carry",
                )
            }
            ColorField(value = v)
        }
        else -> unknownCase(t, path, "FormFieldKind")
    }
}

/**
 * `#rrggbb` — six hexadecimal digits after a `#`, either case (3.6.17). Deliberately narrower than
 * CSS: it is the one shape a native colour input can hold or return, so `#fff`, `rebeccapurple`,
 * `rgb(0 0 0)` and an alpha channel all name a colour this control could never carry.
 */
private fun isHexColour(s: String): Boolean =
    s.length == 7 && s[0] == '#' && s.drop(1).all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

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

/**
 * One `srcSet` candidate (WIRE_FORMAT.md 3.6.4). Both members are required WITHIN the entry, and
 * `width` carries the schema's `minimum: 1` as a DECODE rule: a `0w` candidate is not a small
 * image, it is one a client can never select, so admitting it would let the wire state a rendition
 * no host can render. The refusal names the entry by index — the corpus's reject fixture puts a
 * well-formed entry first precisely so a host that reported the list rather than the element fails.
 */
private fun decodeSrcSetEntry(value: JsonValue, path: String): SrcSetEntry {
    val o = value.obj(path)
    return SrcSetEntry(
        src = decodeBindingString(o.req("src", path), "$path.src"),
        width = o.req("width", path).intAtLeast(1, "$path.width"),
    )
}

/**
 * One `TrackEntry` (WIRE_FORMAT.md 3.6.6, Phase 1110) — the strictest record on the wire: four of
 * its five members are REQUIRED and only `default` omits, at `false`.
 *
 * Every member is read through its own typed reader, at the entry's own indexed path. That is what
 * the corpus's two reject vectors measure and it is the class they were written for: a host
 * decoding ARRAY ELEMENTS with a looser walker than its records accepts a stringified `"true"` one
 * level further in than the record-level fixtures reach, and reports nothing. The array index in
 * the path is equally deliberate — a document with four tracks must name the one at fault.
 */
private fun decodeTrackEntry(value: JsonValue, path: String): TrackEntry {
    val o = value.obj(path)
    return TrackEntry(
        // A BARE TrackKind enum, so an unrecognised token reports here with no `.$type` suffix.
        kind = enumOf<TrackKind>(o.req("kind", path).str("$path.kind"), "$path.kind"),
        src = decodeBindingString(o.req("src", path), "$path.src"),
        // REQUIRED on every kind, where HTML makes it mandatory only on subtitles. There is no
        // value to default to that would not be an invented claim about someone else's recording.
        srcLang = o.req("srcLang", path).str("$path.srcLang"),
        label = decodeTextSource(o.req("label", path), "$path.label"),
        // Omitted at false; the stringified boolean is refused rather than coerced. Two hosts
        // ruling differently on truthiness would disagree about which caption track opens, which
        // is a difference the reader meets on the first frame.
        default = o.optBool("default", path) ?: false,
    )
}

/**
 * One `TreeItem` (WIRE_FORMAT.md 3.6.12, Phase 1120) — the recursive row record, and the walk that
 * bounds its own nesting axis.
 *
 * **The bound is on the ITEM axis (§21.5), counted separately from the node axis and from the
 * syntactic one.** A whole hierarchy lives inside one node, so [NodeWalk] cannot see it at all,
 * and at roughly two JSON levels per row the syntactic bound is nowhere near reached — which is
 * why an unbounded item walk would be a hole in an otherwise-total decoder rather than a
 * duplicate of a guard that already exists.
 *
 * [depth] is a plain parameter rather than a thread-local, deliberately and unlike [NodeWalk]:
 * that machinery exists because the node walk is spread across ~200 functions, whereas the item
 * recursion is contained in this one, so a parameter is correct by construction — no counter to
 * leave behind on a throw, and no shared state for two concurrent decodes to mis-bound each other
 * through. It starts at 1 for a root row, so the check fires on the row that BREACHES the bound
 * and the reported path names that row.
 *
 * The refusal is raised BEFORE the required-member reads, so a too-deep row is `LIMIT_EXCEEDED`
 * rather than whatever its contents happen to say — and after it, the SAME reader walks children
 * as walks roots, which is what the corpus's nested missing-`id` vector exists to measure: a host
 * whose child walker is looser than its root walker passes every top-level case.
 */
private fun decodeTreeItem(value: JsonValue, path: String, depth: Int): TreeItem {
    if (depth > WireLimits.MAX_TREE_ITEM_DEPTH) {
        throw FuaranDecodeException(
            FuaranDecodeException.LIMIT_EXCEEDED,
            path,
            "tree-item nesting deeper than the wire limit MAX_TREE_ITEM_DEPTH = " +
                "${WireLimits.MAX_TREE_ITEM_DEPTH}; expected a hierarchy nesting rows no more than " +
                "${WireLimits.MAX_TREE_ITEM_DEPTH} levels deep inside one node",
        )
    }
    val o = value.obj(path)
    return TreeItem(
        // REQUIRED: both State slots address rows BY id, so an id-less row can never be expanded,
        // selected or restored, and a synthesised positional id would move the reader's open
        // branches the moment a sibling was inserted.
        id = o.req("id", path).str("$path.id"),
        // A TextSource because a row label is CONTENT — authored, translated, bindable.
        label = decodeTextSource(o.req("label", path), "$path.label"),
        // Omits at the EMPTY list, which is why a leaf carries two keys and nothing else.
        children =
            o["children"]?.array("$path.children")?.mapIndexed { i, v ->
                decodeTreeItem(v, "$path.children[$i]", depth + 1)
            } ?: emptyList(),
        icon = o.optStr("icon", path),
    )
}

/**
 * The `MediaKind` variant (WIRE_FORMAT.md 3.6.6). `$type`-discriminated, so an unknown case reports
 * at `$path.$type` — the [Binding] / [TextSource] position, not the bare-enum one.
 *
 * [Audio] declares NO slots, which is the whole point: `autoplay` is unrepresentable on it rather
 * than defaulted off, so a document carrying `{"$type":"Audio","autoplay":true}` decodes to an
 * audio surface that does not autoplay because the value has nowhere to land.
 */
private fun decodeMediaKind(value: JsonValue, path: String): MediaKind {
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
        "Video" ->
            Video(
                autoplay = o.optBool("autoplay", path) ?: false,
                poster = o["poster"]?.let { decodeBindingString(it, "$path.poster") },
            )
        "Audio" -> Audio
        else -> unknownCase(t, path, "MediaKind")
    }
}

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

/**
 * Phase 1537 — fail when a Confirm is reachable from [a]. Confirmation is bounded at ONE question:
 * a dialogue that answers a dialogue is a modal stack the reader cannot escape, and it expresses no
 * intent a single question does not.
 *
 * It walks the DECODED action rather than raw JSON, and descends a chain, because a chain is
 * otherwise a hiding place — a check written against the continuation's immediate `$type` passes a
 * nested confirm one level down.
 */
private fun refuseNestedConfirm(a: Action, path: String) {
    when (a) {
        is ConfirmAction ->
            throw FuaranDecodeException(
                FuaranDecodeException.WRONG_TYPE,
                path,
                "expected no Confirm inside another Confirm's continuation \u2014 confirmation is " +
                    "bounded at one question",
            )
        is ChainAction -> a.ops.forEachIndexed { i, inner -> refuseNestedConfirm(inner, "$path.ops[$i]") }
        else -> Unit
    }
}

/**
 * Every direct sub-expression of a raw `ColExpr` document, so the two walks below share one
 * definition of the shape and cannot disagree about which members recurse.
 *
 * A STRUCTURAL walk rather than a typed one, because this surface holds the algebra as raw JSON
 * (the Transform posture): every member that is an object or an array of objects is a candidate
 * sub-expression, which is a superset of the real ones and therefore cannot miss a reference. The
 * cost of the superset is nil — a non-expression object carries neither a `col` discriminator nor a
 * `param` name, so it contributes nothing to either answer.
 */
private fun exprChildren(e: JsonValue): List<JsonValue> =
    when (e) {
        is JsonObject ->
            e.members.entries.filter { it.key != "\u0024type" }.flatMap {
                when (val v = it.value) {
                    is JsonObject -> listOf(v)
                    is JsonArray -> v.items.filterIsInstance<JsonObject>()
                    else -> emptyList()
                }
            }
        is JsonArray -> e.items.filterIsInstance<JsonObject>()
        else -> emptyList()
    }

private fun exprTag(e: JsonValue): String? = ((e as? JsonObject)?.get("\u0024type") as? JsonString)?.value

private fun exprName(e: JsonValue): String? = ((e as? JsonObject)?.get("name") as? JsonString)?.value

/** The first `col` reference reachable in [e], if any (3.3.2 refusal 1). */
private fun firstColReference(e: JsonValue): String? {
    if (exprTag(e) == "col") return exprName(e) ?: ""
    for (child in exprChildren(e)) firstColReference(child)?.let { return it }
    return null
}

/**
 * The first param name [e] references that [bound] does not carry, if any (3.3.2 refusal 2).
 *
 * The `in` arm's `param` member is a param too — it is the LIST spelling of the same reference, so
 * leaving it out would admit an unbound membership test through the one arm that reads a param
 * without being one.
 */
private fun firstUnboundParam(e: JsonValue, bound: Set<String>): String? {
    when (exprTag(e)) {
        "param" -> exprName(e)?.let { if (it !in bound) return it }
        "in" -> ((e as? JsonObject)?.get("param") as? JsonString)?.value?.let { if (it !in bound) return it }
        else -> Unit
    }
    for (child in exprChildren(e)) firstUnboundParam(child, bound)?.let { return it }
    return null
}

/**
 * `true` when [text] is a canonical ISO-8601 date the temporal axis can place — `YYYY-MM-DD`,
 * optionally followed by `T...` whose time-of-day is discarded.
 *
 * STRICT by shape AND by calendar: four digits, two, two, both hyphens, a month in 1-12 and a day
 * the month actually has. A locale spelling (`15/01/2026`) and an impossible day (`2026-13-05`) are
 * both refused, because an unreadable date is not a date drawn slightly wrong — it is one drawn at
 * the epoch, dragging the axis back with it.
 */
private fun isCanonicalIsoDay(text: String): Boolean {
    if (text.length < 10) return false
    if (text.length > 10 && text[10] != 'T') return false
    if (text[4] != '-' || text[7] != '-') return false
    val year = text.substring(0, 4).toIntOrNull() ?: return false
    val month = text.substring(5, 7).toIntOrNull() ?: return false
    val day = text.substring(8, 10).toIntOrNull() ?: return false
    if (month !in 1..12 || day < 1) return false
    val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
    val lengths = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    return day <= lengths[month - 1]
}

/** Phase 1491 (4l) — an annotation's x address. */
private fun decodeChartAnnotationX(value: JsonValue, path: String): ChartAnnotationX {
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
        "Category" -> CategoryAddress(o.req("key", path).str("$path.key"))
        "Date" -> {
            val iso = o.req("iso", path).str("$path.iso")
            if (!isCanonicalIsoDay(iso)) {
                throw FuaranDecodeException(
                    FuaranDecodeException.WRONG_TYPE,
                    "$path.iso",
                    "expected a canonical ISO-8601 date (YYYY-MM-DD, optionally followed by a time) " +
                        "naming a real calendar day \u2014 an event marker's date is the address it " +
                        "is drawn at, and an unreadable one would place the marker at 1970-01-01 " +
                        "and drag the axis back with it",
                )
            }
            DateAddress(iso)
        }
        else -> unknownCase(t, path, "ChartAnnotationX")
    }
}

/**
 * Phase 1492 (4l) — a range band's PAIR.
 *
 * TWO REFUSALS, and they are the pair rules the WIRE can decide by itself. A non-finite endpoint is
 * the reference line's narrowing at two slots instead of one, for its reason exactly: 4l rule 3 has
 * both ends enter the value domain, so a NaN takes the nice-domain, every gridline and every mark
 * with it. An UNORDERED pair is refused at the PAIR's own slot — the defect is the pair's, not
 * either end's — rather than silently swapped, because a band written backwards is an author's
 * mistake about their own data and swapping the ends would draw a picture they did not describe.
 *
 * A CATEGORY pair's order is NOT decided here: the order of two band keys is the ROWS' order, a
 * cross-reference rather than a local property of the address.
 */
private fun decodeChartAnnotationRange(value: JsonValue, path: String): ChartAnnotationRange {
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
        "ValueRange" -> {
            val from = o.req("from", path).double("$path.from")
            val to = o.req("to", path).double("$path.to")
            for ((slot, v) in listOf("from" to from, "to" to to)) {
                if (!v.isFinite()) {
                    throw FuaranDecodeException(
                        FuaranDecodeException.WRONG_TYPE,
                        "$path.$slot",
                        "expected a FINITE JSON number \u2014 a range band's end names a place on " +
                            "the value axis, and NaN / Infinity names none; give the value in the " +
                            "axis's own units, or drop the annotation",
                    )
                }
            }
            if (from > to) {
                throw FuaranDecodeException(
                    FuaranDecodeException.WRONG_TYPE,
                    path,
                    "expected an ORDERED pair \u2014 a range band runs from its lower value to its " +
                        "upper one, and this pair runs backwards; swapping the ends silently would " +
                        "draw a band the author did not describe",
                )
            }
            ValueRange(from, to)
        }
        "XRange" -> {
            val from = decodeChartAnnotationX(o.req("from", path), "$path.from")
            val to = decodeChartAnnotationX(o.req("to", path), "$path.to")
            // Both dates are already known canonical and calendar-valid (the address decoder
            // refused anything else), and a canonical `YYYY-MM-DD` sorts lexicographically exactly
            // as it sorts chronologically — so no calendar arithmetic is needed here.
            if (from is DateAddress && to is DateAddress && from.iso > to.iso) {
                throw FuaranDecodeException(
                    FuaranDecodeException.WRONG_TYPE,
                    path,
                    "expected an ORDERED pair \u2014 a range band runs from its earlier date to its " +
                        "later one, and this pair runs backwards; swapping the ends silently would " +
                        "draw a band the author did not describe",
                )
            }
            XRange(from, to)
        }
        else -> unknownCase(t, path, "ChartAnnotationRange")
    }
}

/**
 * Phase 1490 (4l) — a chart's data-addressed annotation.
 *
 * THE REFERENCE LINE'S VALUE MUST BE FINITE, and that is a slot-specific NARROWING of 7 rather than
 * a disagreement with it. 7 admits the quoted `"NaN"` / `"Infinity"` / `"-Infinity"` sentinels at
 * every float slot and the float reader honours them — that widening is deliberate and stays. But a
 * reference line addresses a place on the VALUE AXIS, and a non-finite value names no such place:
 * it would enter the domain computation and put every gridline, tick and mark at a NaN coordinate.
 * The picture is not merely wrong at the annotation, it is wrong everywhere.
 */
private fun decodeChartAnnotation(value: JsonValue, path: String): ChartAnnotation {
    val o = value.obj(path)
    val label = o["label"]?.let { decodeTextSource(it, "$path.label") }
    return when (val t = o.discriminator(path)) {
        "ReferenceLine" -> {
            val v = o.req("value", path).double("$path.value")
            if (!v.isFinite()) {
                throw FuaranDecodeException(
                    FuaranDecodeException.WRONG_TYPE,
                    "$path.value",
                    "expected a FINITE JSON number \u2014 a reference line names a place on the " +
                        "value axis, and NaN / Infinity names none; give the value in the axis's " +
                        "own units, or drop the annotation",
                )
            }
            ReferenceLine(v, label)
        }
        "EventMarker" -> EventMarker(decodeChartAnnotationX(o.req("at", path), "$path.at"), label)
        "RangeBand" -> RangeBand(decodeChartAnnotationRange(o.req("range", path), "$path.range"), label)
        else -> unknownCase(t, path, "ChartAnnotation")
    }
}

private fun unknownCase(discriminator: String, path: String, du: String): Nothing =
    throw FuaranDecodeException(
        FuaranDecodeException.UNKNOWN_DU_CASE,
        "$path.\$type",
        "'$discriminator' is not a recognised $du case",
    )
