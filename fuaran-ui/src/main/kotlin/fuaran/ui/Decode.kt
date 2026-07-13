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
                activeIndex = decodeBinding(o.req("activeIndex", path), "$path.activeIndex"),
                children = decodeNodeList(o.req("children", path), "$path.children"),
                orientation = enumOf<Orientation>(o.req("orientation", path).str("$path.orientation"), "$path.orientation"),
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
                source = decodeBinding(o.req("source", path), "$path.source"),
                format = decodeValueFormat(o.req("format", path), "$path.format"),
                emphasis = enumOf<Emphasis>(o.req("emphasis", path).str("$path.emphasis"), "$path.emphasis"),
                tone = enumOf<ToneVariant>(o.req("tone", path).str("$path.tone"), "$path.tone"),
                weight = enumOf<StyleWeight>(o.req("weight", path).str("$path.weight"), "$path.weight"),
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
                heading = decodeTextSource(o.req("heading", path), "$path.heading"),
                tone = enumOf<ToneVariant>(o.req("tone", path).str("$path.tone"), "$path.tone"),
                dismissable = o.req("dismissable", path).bool("$path.dismissable"),
                icon = o.optStr("icon", path),
            )
        "Progress" ->
            Progress(
                fraction = decodeBinding(o.req("fraction", path), "$path.fraction"),
                indeterminate = o.req("indeterminate", path).bool("$path.indeterminate"),
                label = decodeTextSource(o.req("label", path), "$path.label"),
                tone = enumOf<ToneVariant>(o.req("tone", path).str("$path.tone"), "$path.tone"),
            )
        "Skeleton" -> Skeleton(rows = o.req("rows", path).int("$path.rows"))
        "LabelValueRow" ->
            LabelValueRow(
                label = decodeTextSource(o.req("label", path), "$path.label"),
                source = decodeBinding(o.req("source", path), "$path.source"),
                format = decodeValueFormat(o.req("format", path), "$path.format"),
                emphasis = o.req("emphasis", path).bool("$path.emphasis"),
                help = o["help"]?.let { decodeTextSource(it, "$path.help") },
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
                dismissable = o.req("dismissable", path).bool("$path.dismissable"),
                message = decodeTextSource(o.req("message", path), "$path.message"),
                open = decodeBinding(o.req("open", path), "$path.open"),
                tone = enumOf<ToneVariant>(o.req("tone", path).str("$path.tone"), "$path.tone"),
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
                style = decodeDrawStyle(o.req("style", path), "$path.style"),
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
                editable = o.req("editable", path).bool("$path.editable"),
                source = decodeBinding(o.req("source", path), "$path.source"),
                rowKeyField = o.optStr("rowKeyField", path),
                staticRows = o["staticRows"]?.let { decodeStaticRows(it, "$path.staticRows") },
            )
        "Chart" ->
            Chart(
                kind = enumOf<ChartKind>(o.req("kind", path).str("$path.kind"), "$path.kind"),
                source = decodeBinding(o.req("source", path), "$path.source"),
                stacked = o.req("stacked", path).bool("$path.stacked"),
                xField = o.req("xField", path).str("$path.xField"),
                yFields = o.strList("yFields", path),
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
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
        "Static" -> StaticBinding(o.req("value", path))
        "State" -> StateBinding(o.req("key", path).str("$path.key"), o["defaultValue"])
        "Query" -> QueryBinding(o.req("name", path).str("$path.name"), o.optStrList("dependsOn", path))
        "Filter" -> FilterBinding(o.req("name", path).str("$path.name"))
        "Selection" -> SelectionBinding(o.optStr("name", path))
        "Computed" -> ComputedBinding
        "I18n" -> I18nBinding(o.req("key", path).str("$path.key"), o["args"])
        "Local" ->
            LocalBinding(
                flushOn = decodeLocalFlushTrigger(o.req("flushOn", path), "$path.flushOn"),
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
        "Notify" -> NotifyAction(channel = o.optStr("channel", path), payload = o["payload"])
        "Navigate" -> NavigateAction(href = o.optStr("href", path), payload = o["payload"])
        "SetState" -> SetStateAction(key = o.optStr("key", path), payload = o["payload"])
        "AiTool" -> AiToolAction(payload = o["payload"])
        "CommitLocal" -> CommitLocalAction
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

private fun decodeFormField(value: JsonValue, path: String): FormField {
    val o = value.obj(path)
    return FormField(
        id = o.req("id", path).str("$path.id"),
        kind = decodeFormFieldKind(o.req("kind", path), "$path.kind"),
        label = decodeTextSource(o.req("label", path), "$path.label"),
        required = o.req("required", path).bool("$path.required"),
        help = o["help"]?.let { decodeTextSource(it, "$path.help") },
    )
}

private fun decodeFormFieldKind(value: JsonValue, path: String): FormFieldKind {
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
        "Text" -> TextField(decodeBinding(o.req("value", path), "$path.value"))
        "Number" -> NumberField(decodeBinding(o.req("value", path), "$path.value"))
        "Checkbox" -> CheckboxField(decodeBinding(o.req("value", path), "$path.value"))
        "Choice" ->
            ChoiceField(
                options = decodeBinding(o.req("options", path), "$path.options"),
                value = decodeBinding(o.req("value", path), "$path.value"),
            )
        "TextArea" ->
            TextAreaField(
                value = decodeBinding(o.req("value", path), "$path.value"),
                rows = o.req("rows", path).int("$path.rows"),
            )
        "SegmentedChoice" ->
            SegmentedChoiceField(
                options = decodeBinding(o.req("options", path), "$path.options"),
                value = decodeBinding(o.req("value", path), "$path.value"),
                orientation = enumOf<Orientation>(o.req("orientation", path).str("$path.orientation"), "$path.orientation"),
            )
        "RangedNumber" ->
            RangedNumberField(
                value = decodeBinding(o.req("value", path), "$path.value"),
                min = o.optDouble("min", path),
                max = o.optDouble("max", path),
                step = o.optDouble("step", path),
            )
        "Date" ->
            DateField(
                value = decodeBinding(o.req("value", path), "$path.value"),
                variant = enumOf<DateFieldVariant>(o.req("variant", path).str("$path.variant"), "$path.variant"),
                min = o.optStr("min", path),
                max = o.optStr("max", path),
                step = o.optDouble("step", path),
            )
        else -> unknownCase(t, path, "FormFieldKind")
    }
}

private fun decodeFilterItem(value: JsonValue, path: String): FilterItem {
    val o = value.obj(path)
    return FilterItem(
        name = o.req("name", path).str("$path.name"),
        label = decodeTextSource(o.req("label", path), "$path.label"),
        kind = decodeFilterKind(o.req("kind", path), "$path.kind"),
    )
}

private fun decodeFilterKind(value: JsonValue, path: String): FilterKind {
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
        "TextFilter" -> TextFilter(decodeBinding(o.req("value", path), "$path.value"))
        "ChoiceFilter" ->
            ChoiceFilter(
                options = decodeBinding(o.req("options", path), "$path.options"),
                value = decodeBinding(o.req("value", path), "$path.value"),
            )
        "RangeFilter" -> {
            val rv = o.req("value", path).obj("$path.value")
            RangeFilter(RangeValue(rv.req("min", "$path.value").double("$path.value.min"), rv.req("max", "$path.value").double("$path.value.max")))
        }
        "SegmentedFilter" ->
            SegmentedFilter(
                options = decodeBinding(o.req("options", path), "$path.options"),
                value = decodeBinding(o.req("value", path), "$path.value"),
                orientation = enumOf<Orientation>(o.req("orientation", path).str("$path.orientation"), "$path.orientation"),
            )
        else -> unknownCase(t, path, "FilterKind")
    }
}

private fun decodeLocalFlushTrigger(value: JsonValue, path: String): LocalFlushTrigger {
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
        "OnBlur" -> OnBlur
        "OnEnter" -> OnEnter
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
        format = decodeValueFormat(o.req("format", path), "$path.format"),
        width = decodeColumnWidth(o.req("width", path), "$path.width"),
        field = o.optStr("field", path),
    )
}

private fun decodeCellKind(value: JsonValue, path: String): CellKind {
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
        "Text" -> TextCell
        else -> unknownCase(t, path, "CellKind")
    }
}

private fun decodeColumnWidth(value: JsonValue, path: String): ColumnWidth {
    val o = value.obj(path)
    return when (val t = o.discriminator(path)) {
        "Auto" -> AutoWidth
        "Fixed" -> FixedWidth(o.req("pixels", path).int("$path.pixels"))
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
    val style = decodeDrawStyle(o.req("style", path), "$path.style")
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
