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
    val root =
        try {
            Json.parse(json)
        } catch (e: JsonSyntaxException) {
            throw FuaranDecodeException(FuaranDecodeException.INVALID_JSON, "$", e.message ?: "invalid JSON")
        }
    return decodeNode(root, "$")
}

// --------------------------------------------------------------------------- //
// JSON accessor helpers
// --------------------------------------------------------------------------- //

private fun JsonValue.obj(path: String): JsonObject =
    this as? JsonObject
        ?: throw FuaranDecodeException(FuaranDecodeException.WRONG_TYPE, path, "expected object")

private fun JsonValue.str(path: String): String =
    (this as? JsonString)?.value
        ?: throw FuaranDecodeException(FuaranDecodeException.WRONG_TYPE, path, "expected string")

private fun JsonValue.int(path: String): Int =
    (this as? JsonNumber)?.toInt()
        ?: throw FuaranDecodeException(FuaranDecodeException.WRONG_TYPE, path, "expected number")

private fun JsonValue.double(path: String): Double =
    (this as? JsonNumber)?.toDouble()
        ?: throw FuaranDecodeException(FuaranDecodeException.WRONG_TYPE, path, "expected number")

private fun JsonValue.bool(path: String): Boolean =
    (this as? JsonBool)?.value
        ?: throw FuaranDecodeException(FuaranDecodeException.WRONG_TYPE, path, "expected boolean")

private fun JsonValue.array(path: String): List<JsonValue> =
    (this as? JsonArray)?.items
        ?: throw FuaranDecodeException(FuaranDecodeException.WRONG_TYPE, path, "expected array")

private fun JsonObject.req(key: String, path: String): JsonValue =
    this[key] ?: throw FuaranDecodeException(FuaranDecodeException.MISSING_FIELD, "$path.$key", "required field absent")

/** A required field with one accepted decode-side alias; the error names the canonical key. */
private fun JsonObject.reqAliased(key: String, alias: String, path: String): JsonValue =
    this[key] ?: this[alias]
        ?: throw FuaranDecodeException(FuaranDecodeException.MISSING_FIELD, "$path.$key", "required field absent")

private fun JsonObject.discriminator(path: String): String =
    (req("\$type", path) as? JsonString)?.value
        ?: throw FuaranDecodeException(FuaranDecodeException.WRONG_TYPE, "$path.\$type", "expected string discriminator")

private fun JsonObject.optStr(key: String, path: String): String? = this[key]?.str("$path.$key")

private fun JsonObject.optInt(key: String, path: String): Int? = this[key]?.int("$path.$key")

private fun JsonObject.optDouble(key: String, path: String): Double? = this[key]?.double("$path.$key")

private fun JsonObject.optBool(key: String, path: String): Boolean? = this[key]?.bool("$path.$key")

private fun JsonObject.strList(key: String, path: String): List<String> =
    req(key, path).array("$path.$key").mapIndexed { i, v -> v.str("$path.$key[$i]") }

private fun JsonObject.optStrList(key: String, path: String): List<String>? =
    this[key]?.array("$path.$key")?.mapIndexed { i, v -> v.str("$path.$key[$i]") }

// --------------------------------------------------------------------------- //
// Node envelope
// --------------------------------------------------------------------------- //

private fun decodeNode(value: JsonValue, path: String): Node {
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
}

private fun decodeStyle(value: JsonValue, path: String): SemanticStyle {
    val o = value.obj(path)
    return SemanticStyle(
        emphasis = o.optStr("emphasis", path)?.let { enumOf<Emphasis>(it, "$path.emphasis") } ?: Emphasis.Normal,
        tone = o.optStr("tone", path)?.let { enumOf<ToneVariant>(it, "$path.tone") } ?: ToneVariant.Default,
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

private fun decodeAccessibility(value: JsonValue, path: String): Accessibility {
    val o = value.obj(path)
    return Accessibility(
        label = o["label"]?.let { decodeBinding(it, "$path.label") },
        labelledBy = o.optStr("labelledBy", path),
        describedBy = o.optStr("describedBy", path),
        role = o.optStr("role", path),
        liveRegion = o.optStr("liveRegion", path),
        hidden = o["hidden"]?.let { decodeBinding(it, "$path.hidden") },
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
                heading = o["heading"]?.let { decodeTextSource(it, "$path.heading") },
            )
        "SplitPanel" ->
            SplitPanel(
                children = decodeNodeList(o.req("children", path), "$path.children"),
                weight = o.req("weight", path).double("$path.weight"),
            )
        "Tabs" ->
            Tabs(
                // `activeIndex` round-trips; absent (legacy wire) defaults to Static 0.
                activeIndex = o["activeIndex"]?.let { decodeBinding(it, "$path.activeIndex") }
                    ?: StaticBinding(JsonNumber("0")),
                children = decodeNodeList(o.req("children", path), "$path.children"),
                // 0.2.0 — omitted-when-default (Horizontal).
                orientation = o.optStr("orientation", path)?.let { enumOf<Orientation>(it, "$path.orientation") }
                    ?: Orientation.Horizontal,
                activeTag = o["activeTag"]?.let { decodeBinding(it, "$path.activeTag") },
                tabTags = o.optStrList("tabTags", path),
                tabHeaders = o["tabHeaders"]?.array("$path.tabHeaders")?.mapIndexed { i, v ->
                    decodeTabHeader(v, "$path.tabHeaders[$i]")
                },
            )
        "Stepper" ->
            Stepper(
                activeStep = decodeBinding(o.req("activeStep", path), "$path.activeStep"),
                children = decodeNodeList(o.req("children", path), "$path.children"),
            )
        "SummaryList" ->
            SummaryList(
                children = decodeNodeList(o.req("children", path), "$path.children"),
                heading = o["heading"]?.let { decodeTextSource(it, "$path.heading") },
            )
        "Disclosure" ->
            Disclosure(
                children = decodeNodeList(o.req("children", path), "$path.children"),
                heading = decodeTextSource(o.req("heading", path), "$path.heading"),
                open = decodeBinding(o.req("open", path), "$path.open"),
                defaultOpen = o.req("defaultOpen", path).bool("$path.defaultOpen"),
            )
        "Modal" ->
            Modal(
                children = decodeNodeList(o.req("children", path), "$path.children"),
                dismissable = o.req("dismissable", path).bool("$path.dismissable"),
                open = decodeBinding(o.req("open", path), "$path.open"),
                heading = o["heading"]?.let { decodeTextSource(it, "$path.heading") },
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
        "Switch" ->
            Switch(
                stateKey = o.req("stateKey", path).str("$path.stateKey"),
                cases = o.req("cases", path).array("$path.cases").mapIndexed { i, v ->
                    val c = v.obj("$path.cases[$i]")
                    SwitchCase(
                        match = c.req("match", "$path.cases[$i]").str("$path.cases[$i].match"),
                        child = decodeNode(c.req("child", "$path.cases[$i]"), "$path.cases[$i].child"),
                    )
                },
                default = decodeNode(o.req("default", path), "$path.default"),
            )
        // Display
        "Heading" ->
            Heading(
                level = o.req("level", path).int("$path.level"),
                text = decodeTextSource(o.req("text", path), "$path.text"),
                variant = enumOf<HeadingVariant>(o.req("variant", path).str("$path.variant"), "$path.variant"),
            )
        "Markdown" -> Markdown(text = decodeTextSource(o.req("text", path), "$path.text"))
        "Metric" ->
            Metric(
                label = decodeTextSource(o.req("label", path), "$path.label"),
                // 0.2.0 rename law — scalar displayed value ⇒ `value` (`data` alias kept;
                // the retired `source` spelling is a hard MISSING_FIELD, mirroring the core).
                value = decodeBinding(o.reqAliased("value", "data", path), "$path.value"),
                // 0.2.x — stylistic fields omitted-when-default.
                format = o["format"]?.let { decodeValueFormat(it, "$path.format") } ?: NoValueFormat,
                emphasis = o.optStr("emphasis", path)?.let { enumOf<Emphasis>(it, "$path.emphasis") } ?: Emphasis.Normal,
                tone = o.optStr("tone", path)?.let { enumOf<ToneVariant>(it, "$path.tone") } ?: ToneVariant.Default,
                weight = o.optStr("weight", path)?.let { enumOf<StyleWeight>(it, "$path.weight") } ?: StyleWeight.Standard,
                icon = o.optStr("icon", path),
                subtext = o["subtext"]?.let { decodeTextSource(it, "$path.subtext") },
                trend = o["trend"]?.let { decodeBinding(it, "$path.trend") },
                trendFormat = o["trendFormat"]?.let { decodeValueFormat(it, "$path.trendFormat") },
            )
        "Badge" ->
            Badge(
                label = decodeTextSource(o.req("label", path), "$path.label"),
                variant = enumOf<BadgeVariant>(o.req("variant", path).str("$path.variant"), "$path.variant"),
            )
        "Sparkline" -> Sparkline(source = decodeBinding(o.req("source", path), "$path.source"))
        "Callout" ->
            Callout(
                body = decodeTextSource(o.req("body", path), "$path.body"),
                // 0.2.0 — omitted-when-false; heading is optional.
                dismissable = o.optBool("dismissable", path) ?: false,
                tone = o.optStr("tone", path)?.let { enumOf<ToneVariant>(it, "$path.tone") } ?: ToneVariant.Default,
                heading = o["heading"]?.let { decodeTextSource(it, "$path.heading") },
                icon = o.optStr("icon", path),
            )
        "Progress" ->
            Progress(
                fraction = decodeBinding(o.req("fraction", path), "$path.fraction"),
                // 0.2.0 — omitted-when-false; label is optional; `caveat` added.
                indeterminate = o.optBool("indeterminate", path) ?: false,
                tone = o.optStr("tone", path)?.let { enumOf<ToneVariant>(it, "$path.tone") } ?: ToneVariant.Default,
                label = o["label"]?.let { decodeTextSource(it, "$path.label") },
                caveat = o["caveat"]?.let { decodeTextSource(it, "$path.caveat") },
            )
        "Skeleton" -> Skeleton(rows = o.req("rows", path).int("$path.rows"))
        "LabelValueRow" ->
            LabelValueRow(
                label = decodeTextSource(o.req("label", path), "$path.label"),
                // 0.2.0 rename law — scalar displayed value ⇒ `value` (`data` alias kept).
                value = decodeBinding(o.reqAliased("value", "data", path), "$path.value"),
                format = o["format"]?.let { decodeValueFormat(it, "$path.format") } ?: NoValueFormat,
                // The behavioural bool; 0.2.2 — omitted-when-false.
                emphasis = o.optBool("emphasis", path) ?: false,
                help = o["help"]?.let { decodeTextSource(it, "$path.help") },
            )
        "Fact" ->
            Fact(
                label = decodeTextSource(o.req("label", path), "$path.label"),
                value = decodeTextSource(o.req("value", path), "$path.value"),
                emphasis = o.optBool("emphasis", path) ?: false,
                tone = o.optStr("tone", path)?.let { enumOf<ToneVariant>(it, "$path.tone") } ?: ToneVariant.Default,
                help = o["help"]?.let { decodeTextSource(it, "$path.help") },
                icon = o.optStr("icon", path),
            )
        "Link" ->
            Link(
                href = decodeBinding(o.req("href", path), "$path.href"),
                label = decodeTextSource(o.req("label", path), "$path.label"),
                download = o.req("download", path).bool("$path.download"),
                rel = o.optStr("rel", path),
                target = o.optStr("target", path),
            )
        "Image" ->
            Image(
                alt = decodeTextSource(o.req("alt", path), "$path.alt"),
                src = decodeBinding(o.req("src", path), "$path.src"),
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
                open = decodeBinding(o.req("open", path), "$path.open"),
                tone = o.optStr("tone", path)?.let { enumOf<ToneVariant>(it, "$path.tone") } ?: ToneVariant.Default,
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
                disabled = o["disabled"]?.let { decodeBinding(it, "$path.disabled") },
            )
        "Button" ->
            Button(
                label = decodeTextSource(o.req("label", path), "$path.label"),
                onClick = decodeAction(o.req("onClick", path), "$path.onClick"),
                variant = enumOf<ButtonVariant>(o.req("variant", path).str("$path.variant"), "$path.variant"),
                disabled = o["disabled"]?.let { decodeBinding(it, "$path.disabled") },
                icon = o.optStr("icon", path),
            )
        "FileUpload" ->
            FileUpload(
                accept = o.strList("accept", path),
                label = decodeTextSource(o.req("label", path), "$path.label"),
                multiple = o.req("multiple", path).bool("$path.multiple"),
                disabled = o["disabled"]?.let { decodeBinding(it, "$path.disabled") },
            )
        "Select" ->
            Select(
                label = decodeTextSource(o.req("label", path), "$path.label"),
                source = decodeBinding(o.req("source", path), "$path.source"),
                multiple = o.optBool("multiple", path) ?: false,
                value = o["value"]?.let { decodeBinding(it, "$path.value") },
                values = o["values"]?.let { decodeBinding(it, "$path.values") },
                placeholder = o["placeholder"]?.let { decodeTextSource(it, "$path.placeholder") },
                disabled = o["disabled"]?.let { decodeBinding(it, "$path.disabled") },
            )
        "Filters" ->
            Filters(
                items = o.req("items", path).array("$path.items").mapIndexed { i, v -> decodeFilterItem(v, "$path.items[$i]") },
            )
        // Visualisation
        "DataGrid" ->
            DataGrid(
                columns = o.req("columns", path).array("$path.columns").mapIndexed { i, v -> decodeGridColumn(v, "$path.columns[$i]") },
                source = decodeBinding(o.req("source", path), "$path.source"),
                // 0.2.0 — omitted-when-false.
                editable = o.optBool("editable", path) ?: false,
                rowKeyField = o.optStr("rowKeyField", path),
                staticRows = o["staticRows"]?.let { decodeStaticRows(it, "$path.staticRows") },
            )
        "Chart" ->
            Chart(
                kind = enumOf<ChartKind>(o.req("kind", path).str("$path.kind"), "$path.kind"),
                source = decodeBinding(o.req("source", path), "$path.source"),
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
                source = decodeBinding(o.req("source", path), "$path.source"),
                zoom = o.req("zoom", path).int("$path.zoom"),
            )
        // Structural
        "Custom" ->
            Custom(
                moduleId = o.req("moduleId", path).str("$path.moduleId"),
                componentId = o.req("componentId", path).str("$path.componentId"),
                props = o.req("props", path),
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
        disabled = o["disabled"]?.let { decodeBinding(it, "$path.disabled") },
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
        "I18n" -> I18nText(o.req("key", path).str("$path.key"), o["args"])
        else -> unknownCase(t, path, "TextSource")
    }
}

// --------------------------------------------------------------------------- //
// Binding
// --------------------------------------------------------------------------- //

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
        "State" -> StateBinding(o.req("key", path).str("$path.key"), o["defaultValue"])
        "Query" -> QueryBinding(o.req("name", path).str("$path.name"), o.optStrList("dependsOn", path))
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
        "I18n" -> I18nBinding(o.req("key", path).str("$path.key"), o["args"])
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
                source = o.req("source", path),
                pipeline = o.req("pipeline", path),
                params = o["params"]?.array("$path.params")?.mapIndexed { i, v ->
                    val p = v.obj("$path.params[$i]")
                    TransformParam(
                        name = p.req("name", "$path.params[$i]").str("$path.params[$i].name"),
                        from = decodeBinding(p.req("from", "$path.params[$i]"), "$path.params[$i].from"),
                    )
                },
            )
        "Invoke" -> InvokeBinding(o.req("capabilityId", path).str("$path.capabilityId"), decodeInvokeArgs(o, path))
        // Lenient: the `TextSource.Bound` wrapper transferred to a bare-Binding slot —
        // one payload field, so the unwrap is one-to-one (decode-only).
        "Bound" -> decodeBinding(o.req("binding", path), "$path.binding")
        else -> unknownCase(t, path, "Binding")
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
        "Call" ->
            CallAction(
                endpoint = o.req("endpoint", path).str("$path.endpoint"),
                into = o["into"]?.let { decodeCallTarget(it, "$path.into") },
            )
        "Notify" ->
            NotifyAction(
                channel = o.req("channel", path).str("$path.channel"),
                payload = o.req("payload", path),
            )
        // Canonical field is `route`; the web-prior spellings decode as aliases.
        "Navigate" -> {
            val v =
                o["route"] ?: o["href"] ?: o["url"] ?: o["to"]
                    ?: throw FuaranDecodeException(FuaranDecodeException.MISSING_FIELD, "$path.route", "required field absent")
            NavigateAction(route = v.str("$path.route"))
        }
        "SetState" ->
            SetStateAction(
                key = o.req("key", path).str("$path.key"),
                value = o.req("value", path),
            )
        "AiTool" ->
            AiToolAction(
                toolName = o.req("toolName", path).str("$path.toolName"),
                args = o.req("args", path),
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
                direction = enumOf<Orientation>(o.req("direction", path).str("$path.direction"), "$path.direction"),
                wrap = o.req("wrap", path).bool("$path.wrap"),
                gap = o.optInt("gap", path),
            )
        "Grid" ->
            GridLayout(
                cols = o.req("cols", path).int("$path.cols"),
                gap = o.optInt("gap", path),
                templateColumns = o.optStr("templateColumns", path),
            )
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

private fun decodeFormField(value: JsonValue, path: String): FormField {
    val o = value.obj(path)
    // Field alias: name → id. Id decodes first so the auto-bind can use it.
    val id = o.reqAliased("id", "name", path).str("$path.id")
    return FormField(
        id = id,
        kind = decodeFormFieldKind(o.req("kind", path), "$path.kind", ControlAutoBind.FormFieldId(id)),
        label = decodeTextSource(o.req("label", path), "$path.label"),
        required = o.req("required", path).bool("$path.required"),
        help = o["help"]?.let { decodeTextSource(it, "$path.help") },
    )
}

private fun decodeFormFieldKind(value: JsonValue, path: String, autoBind: ControlAutoBind): FormFieldKind {
    val o = value.obj(path)
    // Value slot: present ⇒ decode; absent ⇒ the context's auto-binding (never an error).
    fun valueOr(placeholder: JsonValue): Binding =
        o["value"]?.let { decodeBinding(it, "$path.value") } ?: autoBind.autoBinding(placeholder)
    return when (val t = o.discriminator(path)) {
        "Text" -> TextField(valueOr(JsonString("")))
        "Number" -> NumberField(valueOr(JsonNumber("0")))
        "Checkbox" -> CheckboxField(valueOr(JsonBool(false)))
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
                orientation = o.optStr("orientation", path)?.let { enumOf<Orientation>(it, "$path.orientation") }
                    ?: Orientation.Horizontal,
            )
        "RangedNumber" ->
            RangedNumberField(
                value = valueOr(JsonNumber("0")),
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
    return GridColumn(
        label = o.req("label", path).str("$path.label"),
        kind = decodeCellKind(o.req("kind", path), "$path.kind"),
        // 0.2.x — format/width omitted-when-default; `value` is a closure sentinel (dropped).
        format = o["format"]?.let { decodeValueFormat(it, "$path.format") } ?: NoValueFormat,
        width = o["width"]?.let { decodeColumnWidth(it, "$path.width") } ?: AutoWidth,
        field = o.optStr("field", path),
    )
}

private fun decodeCellKind(value: JsonValue, path: String): CellKind {
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
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
        fill = o["fill"]?.let { decodeBinding(it, "$path.fill") },
        opacity = o["opacity"]?.let { decodeBinding(it, "$path.opacity") },
        stroke = o["stroke"]?.let { decodeBinding(it, "$path.stroke") },
        strokeWidth = o["strokeWidth"]?.let { decodeBinding(it, "$path.strokeWidth") },
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
