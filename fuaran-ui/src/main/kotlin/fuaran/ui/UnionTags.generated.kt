// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
//
// GENERATED from the wire corpus's idl.json — do not edit.
//   generator: fuaran-ui/src/test/kotlin/fuaran/ui/idl/ModelGenerator.kt
//   idl.json sha256: 857888fc68ecc881b6a11e7f141e32f04ba07fd5d21d6ff47ed16538bad2d2d2
// Regenerate with FUARAN_IDL_REGENERATE=1 (see IdlModelCheck.kt); the drift check fails while this is stale.
package fuaran.ui

/**
 * The wire case tags of the IDL union `Action`, in IDL case order — what the decoder
 * dispatches a `$type` on. Its `when` over this set has no `else`, so a case added to the IDL and
 * regenerated is a compile error in the decoder until its arm is written.
 */
internal enum class ActionTag(override val wire: String) : WireEnum {
    Chain("Chain"),
    WriteToClipboard("WriteToClipboard"),
    Dispatch("Dispatch"),
    Invoke("Invoke"),
    ReadFileBody("ReadFileBody"),
    Call("Call"),
    Navigate("Navigate"),
    CommitLocal("CommitLocal"),
    Notify("Notify"),
    SetState("SetState"),
    AiTool("AiTool"),
    Print("Print"),
    Confirm("Confirm"),
    Focus("Focus"),
}

/**
 * The wire case tags of the IDL union `Binding`, in IDL case order — what the decoder
 * dispatches a `$type` on. Its `when` over this set has no `else`, so a case added to the IDL and
 * regenerated is a compile error in the decoder until its arm is written.
 */
internal enum class BindingTag(override val wire: String) : WireEnum {
    Static("Static"),
    Query("Query"),
    Filter("Filter"),
    Selection("Selection"),
    State("State"),
    Now("Now"),
    Computed("Computed"),
    Local("Local"),
    Format("Format"),
    I18n("I18n"),
    Transform("Transform"),
    Expr("Expr"),
    Invoke("Invoke"),
}

/**
 * The wire case tags of the IDL union `BoxLayout`, in IDL case order — what the decoder
 * dispatches a `$type` on. Its `when` over this set has no `else`, so a case added to the IDL and
 * regenerated is a compile error in the decoder until its arm is written.
 */
internal enum class BoxLayoutTag(override val wire: String) : WireEnum {
    Auto("Auto"),
    Flex("Flex"),
    Grid("Grid"),
    Masonry("Masonry"),
}

/**
 * The wire case tags of the IDL union `CallResultTarget` (Kotlin `CallTarget`), in IDL case order — what the decoder
 * dispatches a `$type` on. Its `when` over this set has no `else`, so a case added to the IDL and
 * regenerated is a compile error in the decoder until its arm is written.
 */
internal enum class CallTargetTag(override val wire: String) : WireEnum {
    State("State"),
    Query("Query"),
}

/**
 * The wire case tags of the IDL union `CellFormat` (Kotlin `ValueFormat`), in IDL case order — what the decoder
 * dispatches a `$type` on. Its `when` over this set has no `else`, so a case added to the IDL and
 * regenerated is a compile error in the decoder until its arm is written.
 */
internal enum class ValueFormatTag(override val wire: String) : WireEnum {
    None("None"),
    Number("Number"),
    Currency("Currency"),
    Percent("Percent"),
    SignificantDigits("SignificantDigits"),
    DateTime("DateTime"),
    Duration("Duration"),
    RelativeTime("RelativeTime"),
    Custom("Custom"),
}

/**
 * The wire case tags of the IDL union `CellKindErased` (Kotlin `CellKind`), in IDL case order — what the decoder
 * dispatches a `$type` on. Its `when` over this set has no `else`, so a case added to the IDL and
 * regenerated is a compile error in the decoder until its arm is written.
 */
internal enum class CellKindTag(override val wire: String) : WireEnum {
    Text("Text"),
    Numeric("Numeric"),
    Date("Date"),
    Editable("Editable"),
    Checkbox("Checkbox"),
    Button("Button"),
    ButtonGroup("ButtonGroup"),
    Link("Link"),
    Pill("Pill"),
    TonedPill("TonedPill"),
    Progress("Progress"),
    Custom("Custom"),
}

/**
 * The wire case tags of the IDL union `ChartAnnotation`, in IDL case order — what the decoder
 * dispatches a `$type` on. Its `when` over this set has no `else`, so a case added to the IDL and
 * regenerated is a compile error in the decoder until its arm is written.
 */
internal enum class ChartAnnotationTag(override val wire: String) : WireEnum {
    ReferenceLine("ReferenceLine"),
    EventMarker("EventMarker"),
    RangeBand("RangeBand"),
}

/**
 * The wire case tags of the IDL union `ChartAnnotationRange`, in IDL case order — what the decoder
 * dispatches a `$type` on. Its `when` over this set has no `else`, so a case added to the IDL and
 * regenerated is a compile error in the decoder until its arm is written.
 */
internal enum class ChartAnnotationRangeTag(override val wire: String) : WireEnum {
    ValueRange("ValueRange"),
    XRange("XRange"),
}

/**
 * The wire case tags of the IDL union `ChartAnnotationX`, in IDL case order — what the decoder
 * dispatches a `$type` on. Its `when` over this set has no `else`, so a case added to the IDL and
 * regenerated is a compile error in the decoder until its arm is written.
 */
internal enum class ChartAnnotationXTag(override val wire: String) : WireEnum {
    Category("Category"),
    Date("Date"),
}

/**
 * The wire case tags of the IDL union `ColumnWidth`, in IDL case order — what the decoder
 * dispatches a `$type` on. Its `when` over this set has no `else`, so a case added to the IDL and
 * regenerated is a compile error in the decoder until its arm is written.
 */
internal enum class ColumnWidthTag(override val wire: String) : WireEnum {
    Auto("Auto"),
    Fixed("Fixed"),
    Flex("Flex"),
}

/**
 * The wire case tags of the IDL union `CurveCommand`, in IDL case order — what the decoder
 * dispatches a `$type` on. Its `when` over this set has no `else`, so a case added to the IDL and
 * regenerated is a compile error in the decoder until its arm is written.
 */
internal enum class CurveCommandTag(override val wire: String) : WireEnum {
    MoveTo("MoveTo"),
    LineTo("LineTo"),
    CubicTo("CubicTo"),
    QuadraticTo("QuadraticTo"),
    Close("Close"),
}

/**
 * The wire case tags of the IDL union `FormFieldKind`, in IDL case order — what the decoder
 * dispatches a `$type` on. Its `when` over this set has no `else`, so a case added to the IDL and
 * regenerated is a compile error in the decoder until its arm is written.
 */
internal enum class FormFieldKindTag(override val wire: String) : WireEnum {
    Text("Text"),
    Number("Number"),
    Checkbox("Checkbox"),
    Toggle("Toggle"),
    Choice("Choice"),
    TextArea("TextArea"),
    RangedNumber("RangedNumber"),
    Range("Range"),
    SegmentedChoice("SegmentedChoice"),
    DateTime("DateTime"),
    DateTimeRange("DateTimeRange"),
    Combobox("Combobox"),
    Rating("Rating"),
    Color("Color"),
    Tokens("Tokens"),
}

/**
 * The wire case tags of the IDL union `Format` (Kotlin `NumberFormat`), in IDL case order — what the decoder
 * dispatches a `$type` on. Its `when` over this set has no `else`, so a case added to the IDL and
 * regenerated is a compile error in the decoder until its arm is written.
 */
internal enum class NumberFormatTag(override val wire: String) : WireEnum {
    Number("Number"),
    Currency("Currency"),
    Percent("Percent"),
    DateTime("DateTime"),
    RelativeTime("RelativeTime"),
    Duration("Duration"),
    Since("Since"),
}

/**
 * The wire case tags of the IDL union `FragmentArg`, in IDL case order — what the decoder
 * dispatches a `$type` on. Its `when` over this set has no `else`, so a case added to the IDL and
 * regenerated is a compile error in the decoder until its arm is written.
 */
internal enum class FragmentArgTag(override val wire: String) : WireEnum {
    Int("Int"),
    Float("Float"),
    Bool("Bool"),
    Str("Str"),
    SlotArg("SlotArg"),
}

/**
 * The wire case tags of the IDL union `HoleDecl`, in IDL case order — what the decoder
 * dispatches a `$type` on. Its `when` over this set has no `else`, so a case added to the IDL and
 * regenerated is a compile error in the decoder until its arm is written.
 */
internal enum class HoleDeclTag(override val wire: String) : WireEnum {
    Value("Value"),
    Slot("Slot"),
    Repeat("Repeat"),
}

/**
 * The wire case tags of the IDL union `HoleValueSpace`, in IDL case order — what the decoder
 * dispatches a `$type` on. Its `when` over this set has no `else`, so a case added to the IDL and
 * regenerated is a compile error in the decoder until its arm is written.
 */
internal enum class HoleValueSpaceTag(override val wire: String) : WireEnum {
    IntRange("IntRange"),
    FloatRange("FloatRange"),
    StringLen("StringLen"),
    Enum("Enum"),
    AnyString("AnyString"),
}

/**
 * The wire case tags of the IDL union `LocalFlushTrigger`, in IDL case order — what the decoder
 * dispatches a `$type` on. Its `when` over this set has no `else`, so a case added to the IDL and
 * regenerated is a compile error in the decoder until its arm is written.
 */
internal enum class LocalFlushTriggerTag(override val wire: String) : WireEnum {
    OnBlur("OnBlur"),
    OnSubmit("OnSubmit"),
    OnDebounce("OnDebounce"),
    OnCommitAction("OnCommitAction"),
}

/**
 * The wire case tags of the IDL union `LocaleSource`, in IDL case order — what the decoder
 * dispatches a `$type` on. Its `when` over this set has no `else`, so a case added to the IDL and
 * regenerated is a compile error in the decoder until its arm is written.
 */
internal enum class LocaleSourceTag(override val wire: String) : WireEnum {
    Ambient("Ambient"),
    Explicit("Explicit"),
}

/**
 * The wire case tags of the IDL union `MediaKind`, in IDL case order — what the decoder
 * dispatches a `$type` on. Its `when` over this set has no `else`, so a case added to the IDL and
 * regenerated is a compile error in the decoder until its arm is written.
 */
internal enum class MediaKindTag(override val wire: String) : WireEnum {
    Video("Video"),
    Audio("Audio"),
}

/**
 * The wire case tags of the IDL union `Scalar`, in IDL case order — what the decoder
 * dispatches a `$type` on. Its `when` over this set has no `else`, so a case added to the IDL and
 * regenerated is a compile error in the decoder until its arm is written.
 */
internal enum class ScalarTag(override val wire: String) : WireEnum {
    Int("Int"),
    Float("Float"),
    Bool("Bool"),
    Str("Str"),
}

/**
 * The wire case tags of the IDL union `Shape`, in IDL case order — what the decoder
 * dispatches a `$type` on. Its `when` over this set has no `else`, so a case added to the IDL and
 * regenerated is a compile error in the decoder until its arm is written.
 */
internal enum class ShapeTag(override val wire: String) : WireEnum {
    Group("Group"),
    Rectangle("Rectangle"),
    Line("Line"),
    Polyline("Polyline"),
    Polygon("Polygon"),
    Curve("Curve"),
    Circle("Circle"),
    Ellipse("Ellipse"),
    Label("Label"),
}

/**
 * The wire case tags of the IDL union `TextSource`, in IDL case order — what the decoder
 * dispatches a `$type` on. Its `when` over this set has no `else`, so a case added to the IDL and
 * regenerated is a compile error in the decoder until its arm is written.
 */
internal enum class TextSourceTag(override val wire: String) : WireEnum {
    Literal("Literal"),
    Bound("Bound"),
    I18n("I18n"),
}
