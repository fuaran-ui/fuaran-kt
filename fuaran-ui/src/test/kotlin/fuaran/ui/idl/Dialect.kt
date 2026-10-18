// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.ui.idl

/**
 * This surface's dialect (Phase 2299): every place the public Kotlin model diverges from what the
 * generator's uniform rules (`ModelGenerator.kt`) make of `idl.json`, one entry each,
 * each with its reason. The vocabulary of entries is [DialectEntry].
 *
 * **What belongs here.** Host REPRESENTATION choices: a public name that predates the IDL, a
 * constructor order consumers already call positionally, a slot this render projection holds raw
 * rather than typed, a closure slot carried as a presence fact. A DATA field cannot be dropped here:
 * the vocabulary has no entry for it (Phase 2331), so every wire field the rules reach is carried.
 * **What never belongs here — nor in the IDL** — is a change to what the wire means: a host
 * representation quirk stays on this side of the boundary, and the IDL stays the language's
 * statement of the vocabulary.
 *
 * **Why so many order entries.** A Kotlin data class's positional constructor and its `componentN`
 * functions are public API, so the order a type shipped with is kept even where the IDL lists the
 * same fields differently; generating IDL order instead would silently re-bind every positional
 * call site. Most of these orders put required fields first, which is how the model was written by
 * hand before the IDL existed.
 *
 * Every entry is refused at generation if it names something the IDL no longer has, or restates
 * what the rules already produce — see [DialectEntry].
 */
private const val ORDER =
    "constructor order is public API (positional calls and componentN); the shipped order predates the IDL's"

private const val ENUM_ORDER = "constant order is public API (ordinal, entries); the shipped order"

private const val BUTTON_GROUP = "each item's closure is dropped, so the group is its labels"

private const val SHIPPED_NAME = "the shipped public name, which predates the IDL"

private const val NO_CASE_SUFFIX = "the shipped case classes carry the bare case name, which reads as the concept itself"

private const val AUTO_BOUND =
    "the decoder always resolves the control's value — absent, it is the field's auto-binding (State under the " +
        "field id, or Filter under a chip's name) — so the projection hands out a value, never a null"

private const val RAW_STATIC_PAIR =
    "a Static range pair rides as a bare object with no \$type, so the projection holds it as the Binding the " +
        "decoder builds around the raw pair rather than typing the pair"

val DIALECT: List<DialectEntry> =
    listOf(
        // ------------------------------------------------------------------------------------- //
        // Type names
        // ------------------------------------------------------------------------------------- //
        TypeName("List", "ListNode", "a kind named List would shadow kotlin.collections.List in every file of the package"),
        TypeName("Map", "MapNode", "a kind named Map would shadow kotlin.collections.Map in every file of the package"),
        TypeName("GuestChannel", "MountChannel", "$SHIPPED_NAME; the record is reached only from Mount.channel"),
        TypeName("ChannelDirection", "MountDirection", SHIPPED_NAME),
        TypeName("ColumnErased", "GridColumn", "$SHIPPED_NAME; `Erased` describes the reference host's typing, not the column"),
        TypeName("CellKindErased", "CellKind", "$SHIPPED_NAME; `Erased` describes the reference host's typing, not the cell"),
        TypeName("CellFormat", "ValueFormat", "$SHIPPED_NAME, kept apart from NumberFormat because the two DUs share case names"),
        TypeName("Format", "NumberFormat", "$SHIPPED_NAME, kept apart from ValueFormat because the two DUs share case names"),
        TypeName("CallResultTarget", "CallTarget", SHIPPED_NAME),
        TypeName("FilterSpec", "FilterItem", SHIPPED_NAME),
        TypeName("DeterminismSource", "Determinism", SHIPPED_NAME),
        TypeName("DateTimeVariant", "DateTimeFieldVariant", "$SHIPPED_NAME (the breadth of a DateTime / DateTimeRange field)"),
        // ------------------------------------------------------------------------------------- //
        // Union case class names
        // ------------------------------------------------------------------------------------- //
        CaseNames("TextSource", "{case}Text", "the shipped case classes: LiteralText, BoundText, I18nText"),
        CaseNames("BoxLayout", "{case}Layout", "the shipped case classes: FlexLayout, GridLayout, MasonryLayout, AutoLayout"),
        CaseNames("CallResultTarget", "CallInto{case}", "the shipped case classes: CallIntoState, CallIntoQuery"),
        CaseNames("CellKindErased", "{case}Cell", "the shipped case classes: TextCell, ButtonCell, TonedPillCell, ..."),
        CaseNames("ColumnWidth", "{case}Width", "the shipped case classes: AutoWidth, FixedWidth, FlexWidth"),
        CaseNames("ChartAnnotation", "{case}", NO_CASE_SUFFIX),
        CaseNames("ChartAnnotationRange", "{case}", NO_CASE_SUFFIX),
        CaseNames("ChartAnnotationX", "{case}Address", "the shipped case classes: CategoryAddress, DateAddress"),
        CaseNames("CurveCommand", "{case}", NO_CASE_SUFFIX),
        CaseNames("FormFieldKind", "{case}Field", "the shipped case classes: TextField, ChoiceField, TokensField, ..."),
        CaseNames("HoleDecl", "{case}Hole", "the shipped case classes: ValueHole, SlotHole, RepeatHole"),
        CaseNames("HoleValueSpace", "{case}Space", "the shipped case classes: IntRangeSpace, ..., AnyStringSpace"),
        CaseNames("LocalFlushTrigger", "{case}", NO_CASE_SUFFIX),
        CaseNames("LocaleSource", "{case}Locale", "the shipped case classes: AmbientLocale, ExplicitLocale"),
        CaseNames("MediaKind", "{case}", NO_CASE_SUFFIX),
        CaseName("CellFormat", "None", "NoValueFormat", "`NoneValueFormat` would misread; the shipped object is NoValueFormat"),
        // ------------------------------------------------------------------------------------- //
        // Types kept by hand, and types not modelled
        // ------------------------------------------------------------------------------------- //
        HandWritten(
            "FragmentArg",
            mapOf("Int" to "ScalarArg", "Float" to "ScalarArg", "Bool" to "ScalarArg", "Str" to "ScalarArg", "SlotArg" to "SlotArg"),
            "the four scalar cases share Scalar's decoding, so the projection folds them into ScalarArg(scalar) — a " +
                "shape no rule derives from four sibling cases",
        ),
        HandWritten(
            "SwitchCase",
            mapOf("child" to "child", "match" to "condition", "when" to "condition"),
            "`match` and `when` are EXACTLY ONE of two (3.6), which the projection types as the SwitchCondition union " +
                "rather than as two optionals whose both-or-neither states would be representable",
        ),
        TypeNotModelled(
            "ButtonGroupItem",
            "a group item's only wire-surviving member is its label; see CellKindErased.ButtonGroup.buttons",
        ),
        TypeNotModelled("DateTimeRangePair", RAW_STATIC_PAIR),
        TypeNotModelled("RangePair", RAW_STATIC_PAIR),
        TypeNotModelled("SelectOption", "an option set arrives through a Binding the projection does not type element-wise"),
        TypeNotModelled("MapMarker", "a marker set arrives through a Binding the projection does not type element-wise"),
        TypeNotModelled("StyleDelta", "a style delta is an op payload, and a render projection decodes no op"),
        TypeNotModelled("FontVoice", "SemanticStyle.voice is held as its raw token (render-only)"),
        TypeNotModelled("StyleRole", "SemanticStyle.role is held as its raw token (render-only)"),
        TypeNotModelled("TextAnchor", "DrawStyle.textAnchor is held as its raw token (render-only)"),
        TypeNotModelled("LiveRegionKind", "Accessibility.liveRegion is held as its checked lower-case token"),
        TypeNotModelled("Motion", "the motion trait is host-only and carries nothing on the wire"),
        // ------------------------------------------------------------------------------------- //
        // Enums
        // ------------------------------------------------------------------------------------- //
        EnumOrder("BoxRole", listOf("Group", "Card", "Dashboard", "Separator"), ENUM_ORDER),
        ExtraEnumCase(
            "ChannelDirection",
            "InOnly",
            "the shipped constant, kept only until Phase 2303's breaking cut (removing a public enum constant is a " +
                "reshape). The IDL has no InOnly, so the decoder refuses it (UNKNOWN_DU_CASE): it admits only the " +
                "constants the IDL's enum declares",
        ),
        EnumOrder("ChannelDirection", listOf("OutOnly", "InOnly", "TwoWay"), ENUM_ORDER),
        WireTokenConstants(
            "TextDirection",
            "the shipped constants are the HTML `dir` tokens themselves (auto, ltr, rtl), read by enumOf, not by a WireEnum mapping",
        ),
        // ------------------------------------------------------------------------------------- //
        // Categories
        // ------------------------------------------------------------------------------------- //
        CategoryName("Meta", "Structural", "the shipped NodeCategory constant for the structural kinds"),
        CategoryOrder(listOf("Layout", "Display", "Input", "Visualisation", "Structural"), ENUM_ORDER),
        // ------------------------------------------------------------------------------------- //
        // The node envelope and the records
        // ------------------------------------------------------------------------------------- //
        FieldOrder("Node", listOf("id", "kind", "style", "state", "accessibility", "visible", "tooltip", "fallback"), ORDER),
        FieldType("SemanticStyle", "role", "String", "held as the raw token (render-only)"),
        Optionality("SemanticStyle", "role", true, "null", "a raw token that is absent is null; the identity default is the renderer's"),
        FieldType("SemanticStyle", "voice", "String", "held as the raw token (render-only)"),
        Optionality("SemanticStyle", "voice", true, "null", "a raw token that is absent is null; the identity default is the renderer's"),
        FieldOrder("SemanticStyle", listOf("emphasis", "tone", "weight", "role", "voice", "direction"), ORDER),
        PresenceFlag("StateBehaviour", "onError", "hasOnError", "the closure is unobservable; that one was declared is the readable fact"),
        FieldOrder("StateBehaviour", listOf("onLoading", "onEmpty", "hasOnError"), ORDER),
        FieldType("Accessibility", "liveRegion", "String", "held as its checked lower-case token"),
        FieldType("Accessibility", "role", "String", "the ARIA role token, held raw (the reference host's AriaRole is host-declared)"),
        FieldOrder("Accessibility", listOf("label", "labelledBy", "describedBy", "role", "liveRegion", "hidden", "speak"), ORDER),
        FieldOrder("ColumnErased", listOf("label", "kind", "format", "width", "field", "sortable", "editable"), ORDER),
        FieldOrder("CompareRule", listOf("op", "against"), ORDER),
        FieldType("DrawStyle", "emphasis", "String", "held as the raw token (render-only)"),
        FieldType("DrawStyle", "textAnchor", "String", "held as the raw token (render-only)"),
        FieldOrder(
            "DrawStyle",
            listOf(
                "fill", "opacity", "stroke", "strokeWidth", "emphasis", "fontFamily", "fontSize", "textAnchor", "markId", "rotation",
                "tip",
            ),
            ORDER,
        ),
        FieldOrder("EffectClass", listOf("hostEffect", "determinism"), ORDER),
        FieldOrder("FieldRule", listOf("format", "pattern", "minLength", "maxLength", "compare", "message"), ORDER),
        FieldOrder("FilterSpec", listOf("name", "label", "kind"), ORDER),
        FieldOrder("StaticRows", listOf("headers", "rows", "defaultSort", "sortable"), ORDER),
        FieldOrder("TrackEntry", listOf("kind", "src", "srcLang", "label", "default"), ORDER),
        FieldOrder("TransformParam", listOf("name", "from"), ORDER),
        FieldOrder("TreeItem", listOf("id", "label", "children", "icon"), ORDER),
        FieldOrder("ViewBox", listOf("minX", "minY", "width", "height"), ORDER),
        // ------------------------------------------------------------------------------------- //
        // Kinds
        // ------------------------------------------------------------------------------------- //
        FieldOrder("Box", listOf("children", "layout", "role", "heading", "breakBefore", "keepTogether"), ORDER),
        FieldOrder("Button", listOf("label", "onClick", "variant", "disabled", "icon"), ORDER),
        FieldOrder(
            "Chart",
            listOf(
                "kind", "source", "xField", "yFields", "stacked", "title", "annotations", "valueFormat", "xTitle", "yTitle",
                "subtitle", "legendPosition", "dataLabels", "xScale",
            ),
            ORDER,
        ),
        FieldType(
            "Custom",
            "props",
            "JsonValue",
            "a guest component's props are held as the raw object; the host does not own their shape",
        ),
        PresenceFlag(
            "DataGrid",
            "onRowClick",
            "rowActionDeclared",
            "3.6.24: the sentinel's one readable fact — a row action is declared — decides whether a row is marked interactive",
        ),
        FieldOrder(
            "DataGrid",
            listOf(
                "columns", "source", "editable", "rowKeyField", "staticRows", "sortStateKey", "pageStateKey", "editStateKey",
                "pageSize", "defaultSort", "keepRowsTogether", "repeatHeader", "exportable", "transferInKey", "transferOutKey",
                "rowActionDeclared", "reorderable", "windowStateKey", "rowTotal",
            ),
            ORDER,
        ),
        FieldOrder("Disclosure", listOf("children", "heading", "open", "defaultOpen"), ORDER),
        FieldOrder("Drawing", listOf("shapes", "style", "viewBox", "title", "description"), ORDER),
        FieldOrder("Embed", listOf("src", "title", "aspectRatio", "permissions"), ORDER),
        FieldOrder("Fact", listOf("label", "value", "emphasis", "tone", "help", "icon"), ORDER),
        FieldOrder(
            "FileUpload",
            listOf(
                "accept", "label", "multiple", "disabled", "dropTarget", "acceptPaste", "capture", "destination", "maxBytes",
                "maxFiles",
            ),
            ORDER,
        ),
        Optionality("FragmentDecl", "holes", true, "null", "the shipped shape distinguishes an absent hole list from an empty one"),
        Optionality(
            "FragmentDecl",
            "effect",
            true,
            "null",
            "the shipped shape distinguishes an absent effect class from the declared (Deterministic, Pure) default",
        ),
        FieldOrder("FragmentDecl", listOf("name", "body", "holes", "effect"), ORDER),
        FieldOrder("Icon", listOf("icon", "label", "size", "tone"), ORDER),
        FieldOrder("Image", listOf("alt", "src", "variant", "fit", "aspectRatio", "loading", "caption", "srcSet", "expandable"), ORDER),
        FieldOrder("LabelValueRow", listOf("label", "value", "format", "emphasis", "help"), ORDER),
        FieldOrder("Math", listOf("display", "source"), ORDER),
        FieldOrder("Media", listOf("label", "src", "kind", "controls", "loop", "tracks", "transcript"), ORDER),
        FieldOrder(
            "Metric",
            listOf("label", "value", "format", "emphasis", "tone", "weight", "icon", "subtext", "trend", "trendFormat", "trendPolarity"),
            ORDER,
        ),
        FieldOrder("Modal", listOf("children", "dismissable", "open", "heading", "onDismiss", "modality", "anchor"), ORDER),
        FieldOrder("Mount", listOf("scopeId", "capabilities", "channel", "inputs"), ORDER),
        Optionality(
            "Select",
            "multiple",
            false,
            "false",
            "absent reads as single-select; the projection hands out the resolved flag rather than a third state",
        ),
        FieldOrder("Select", listOf("label", "source", "multiple", "value", "values", "placeholder", "disabled"), ORDER),
        Optionality(
            "Stepper",
            "activeStep",
            false,
            null,
            "the decoder restores the declared Static 0 itself (Phase 2300, the Tabs.activeIndex shape), so the shipped shape takes the binding without a default",
        ),
        Optionality(
            "Switch",
            "stateKey",
            true,
            null,
            "the shipped shape takes the selector positionally; the decoder refuses a Switch carrying neither stateKey nor on",
        ),
        FieldOrder("Switch", listOf("stateKey", "cases", "default", "on", "autoAdvanceMs"), ORDER),
        Optionality(
            "Tabs",
            "activeIndex",
            false,
            null,
            "the decoder restores the declared Static 0 itself, so the shipped shape takes the binding without a default",
        ),
        FieldOrder("Tabs", listOf("activeIndex", "children", "orientation", "activeTag", "tabTags", "tabHeaders"), ORDER),
        FieldOrder("Toast", listOf("message", "open", "tone", "dismissable"), ORDER),
        FieldOrder("Tree", listOf("items", "expandedStateKey", "selectionStateKey"), ORDER),
        // ------------------------------------------------------------------------------------- //
        // Union cases
        // ------------------------------------------------------------------------------------- //
        Optionality("Binding.Static", "value", false, null, "a Static binding always carries its payload; the decoder refuses one without"),
        PresenceFlag(
            "Binding.Local",
            "onCommit",
            "hasOnCommit",
            "the closure is unobservable; that one was declared is what makes commitTo exclusive",
        ),
        FieldOrder("Binding.Local", listOf("flushOn", "initialFrom", "codec", "commitTo", "hasOnCommit"), ORDER),
        FieldOrder("Binding.Format", listOf("format", "locale", "source"), ORDER),
        FieldType("Binding.I18n", "args", "JsonValue", "the argument map is held as the raw object the decoder checked"),
        FieldType("Binding.Transform", "pipeline", "JsonValue", "the pipeline is held as the raw array the core codec owns"),
        FieldOrder("BoxLayout.Grid", listOf("cols", "gap", "templateColumns"), ORDER),
        FieldName("CellKindErased.ButtonGroup", "buttons", "labels", BUTTON_GROUP),
        FieldType("CellKindErased.ButtonGroup", "buttons", "List<TextSource>", BUTTON_GROUP),
        FieldName("CellKindErased.TonedPill", "default", "defaultTone", "the shipped property name"),
        FieldOrder("FormFieldKind.Combobox", listOf("options", "value", "allowFreeText"), ORDER),
        FieldOrder("FormFieldKind.Rating", listOf("value", "max", "allowHalf"), ORDER),
        FieldOrder("FormFieldKind.Tokens", listOf("value", "suggestions", "allowFreeText"), ORDER),
        FieldType("FormFieldKind.Range", "value", "Binding", RAW_STATIC_PAIR),
        FieldType("FormFieldKind.DateTimeRange", "value", "Binding", RAW_STATIC_PAIR),
        Optionality("FormFieldKind.Text", "value", false, null, AUTO_BOUND),
        Optionality("FormFieldKind.Number", "value", false, null, AUTO_BOUND),
        Optionality("FormFieldKind.Checkbox", "value", false, null, AUTO_BOUND),
        Optionality("FormFieldKind.Toggle", "value", false, null, AUTO_BOUND),
        Optionality("FormFieldKind.Choice", "value", false, null, AUTO_BOUND),
        Optionality("FormFieldKind.TextArea", "value", false, null, AUTO_BOUND),
        Optionality("FormFieldKind.RangedNumber", "value", false, null, AUTO_BOUND),
        Optionality("FormFieldKind.Range", "value", false, null, AUTO_BOUND),
        Optionality("FormFieldKind.SegmentedChoice", "value", false, null, AUTO_BOUND),
        Optionality("FormFieldKind.DateTime", "value", false, null, AUTO_BOUND),
        Optionality("FormFieldKind.DateTimeRange", "value", false, null, AUTO_BOUND),
        Optionality("FormFieldKind.Combobox", "value", false, null, AUTO_BOUND),
        Optionality("FormFieldKind.Rating", "value", false, null, AUTO_BOUND),
        Optionality("FormFieldKind.Color", "value", false, null, AUTO_BOUND),
        Optionality("FormFieldKind.Tokens", "value", false, null, AUTO_BOUND),
        FieldOrder("Shape.Rectangle", listOf("x", "y", "width", "height", "style", "cornerRadius"), ORDER),
        FieldType("TextSource.I18n", "args", "JsonValue", "the argument map is held as the raw object the decoder checked"),
        Optionality("TextSource.I18n", "args", true, "null", "the shipped shape takes an argument-free key without an empty map"),
    )
