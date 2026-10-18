// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
//
// GENERATED from the wire corpus's idl.json — do not edit.
//   generator: fuaran-ui/src/test/kotlin/fuaran/ui/idl/ModelGenerator.kt
//   idl.json sha256: 857888fc68ecc881b6a11e7f141e32f04ba07fd5d21d6ff47ed16538bad2d2d2
// Regenerate with FUARAN_IDL_REGENERATE=1 (see IdlModelCheck.kt); the drift check fails while this is stale.
@file:JvmName("NodeCategoryKt")

package fuaran.ui

/**
 * The four behavioural categories the wire keeps flat but a host recovers on decode
 * (WIRE_FORMAT.md 3.2).
 */
enum class NodeCategory { Layout, Display, Input, Visualisation, Structural }

/**
 * The render projection's load-bearing dispatch spine: an **exhaustive** `when` over the
 * sealed [NodeKind] hierarchy with **no `else` arm**. Adding a new node kind without
 * classifying it here is a compile error — the same "a new kind is a build error"
 * guarantee the native Rust host gets from `match`. The Compose renderer (a later phase)
 * extends this same spine; it must stay `else`-free.
 */
fun NodeKind.category(): NodeCategory =
    when (this) {
        is Badge -> NodeCategory.Display
        is Box -> NodeCategory.Layout
        is Button -> NodeCategory.Input
        is Callout -> NodeCategory.Display
        is Chart -> NodeCategory.Visualisation
        is CodeBlock -> NodeCategory.Display
        is Custom -> NodeCategory.Structural
        is DataGrid -> NodeCategory.Visualisation
        is Disclosure -> NodeCategory.Layout
        is Drawing -> NodeCategory.Display
        is Embed -> NodeCategory.Display
        is ErrorBoundary -> NodeCategory.Structural
        is Fact -> NodeCategory.Display
        is FileUpload -> NodeCategory.Input
        is Filters -> NodeCategory.Input
        is Form -> NodeCategory.Input
        is FragmentDecl -> NodeCategory.Structural
        is FragmentRef -> NodeCategory.Structural
        is Heading -> NodeCategory.Display
        is Icon -> NodeCategory.Display
        is Image -> NodeCategory.Display
        is LabelValueRow -> NodeCategory.Display
        is Link -> NodeCategory.Display
        is ListNode -> NodeCategory.Display
        is MapNode -> NodeCategory.Visualisation
        is Markdown -> NodeCategory.Display
        is Math -> NodeCategory.Display
        is Media -> NodeCategory.Display
        is Metric -> NodeCategory.Display
        is Modal -> NodeCategory.Layout
        is Mount -> NodeCategory.Structural
        is Progress -> NodeCategory.Display
        is ScrollArea -> NodeCategory.Layout
        is Select -> NodeCategory.Input
        is Skeleton -> NodeCategory.Display
        is Sparkline -> NodeCategory.Display
        is SplitPanel -> NodeCategory.Layout
        is Stepper -> NodeCategory.Layout
        is SummaryList -> NodeCategory.Layout
        is Switch -> NodeCategory.Structural
        is Tabs -> NodeCategory.Layout
        is Toast -> NodeCategory.Display
        is Tree -> NodeCategory.Display
    }

/** The wire discriminator name for a decoded [NodeKind] — used by the corpus coverage report. */
fun NodeKind.discriminator(): String =
    when (this) {
        is Badge -> "Badge"
        is Box -> "Box"
        is Button -> "Button"
        is Callout -> "Callout"
        is Chart -> "Chart"
        is CodeBlock -> "CodeBlock"
        is Custom -> "Custom"
        is DataGrid -> "DataGrid"
        is Disclosure -> "Disclosure"
        is Drawing -> "Drawing"
        is Embed -> "Embed"
        is ErrorBoundary -> "ErrorBoundary"
        is Fact -> "Fact"
        is FileUpload -> "FileUpload"
        is Filters -> "Filters"
        is Form -> "Form"
        is FragmentDecl -> "FragmentDecl"
        is FragmentRef -> "FragmentRef"
        is Heading -> "Heading"
        is Icon -> "Icon"
        is Image -> "Image"
        is LabelValueRow -> "LabelValueRow"
        is Link -> "Link"
        is ListNode -> "List"
        is MapNode -> "Map"
        is Markdown -> "Markdown"
        is Math -> "Math"
        is Media -> "Media"
        is Metric -> "Metric"
        is Modal -> "Modal"
        is Mount -> "Mount"
        is Progress -> "Progress"
        is ScrollArea -> "ScrollArea"
        is Select -> "Select"
        is Skeleton -> "Skeleton"
        is Sparkline -> "Sparkline"
        is SplitPanel -> "SplitPanel"
        is Stepper -> "Stepper"
        is SummaryList -> "SummaryList"
        is Switch -> "Switch"
        is Tabs -> "Tabs"
        is Toast -> "Toast"
        is Tree -> "Tree"
    }

/**
 * The wire kind tags, one constant per IDL kind row — what the decoder dispatches a node's `kind.$type` on.
 * Its `when` over this set has no `else`, so a kind added to the IDL and regenerated is a compile
 * error in the decoder until its arm is written.
 */
internal enum class NodeKindTag(val wire: String) {
    Badge("Badge"),
    Box("Box"),
    Button("Button"),
    Callout("Callout"),
    Chart("Chart"),
    CodeBlock("CodeBlock"),
    Custom("Custom"),
    DataGrid("DataGrid"),
    Disclosure("Disclosure"),
    Drawing("Drawing"),
    Embed("Embed"),
    ErrorBoundary("ErrorBoundary"),
    Fact("Fact"),
    FileUpload("FileUpload"),
    Filters("Filters"),
    Form("Form"),
    FragmentDecl("FragmentDecl"),
    FragmentRef("FragmentRef"),
    Heading("Heading"),
    Icon("Icon"),
    Image("Image"),
    LabelValueRow("LabelValueRow"),
    Link("Link"),
    List("List"),
    Map("Map"),
    Markdown("Markdown"),
    Math("Math"),
    Media("Media"),
    Metric("Metric"),
    Modal("Modal"),
    Mount("Mount"),
    Progress("Progress"),
    ScrollArea("ScrollArea"),
    Select("Select"),
    Skeleton("Skeleton"),
    Sparkline("Sparkline"),
    SplitPanel("SplitPanel"),
    Stepper("Stepper"),
    SummaryList("SummaryList"),
    Switch("Switch"),
    Tabs("Tabs"),
    Toast("Toast"),
    Tree("Tree"),
    ;

    companion object {
        private val BY_WIRE = entries.associateBy { it.wire }

        /** The kind a wire tag names, or null when the vocabulary has no such kind. */
        fun ofWire(tag: String): NodeKindTag? = BY_WIRE[tag]
    }
}

/** `FragmentArg` is hand-written; every IDL case maps onto one Kotlin case. */
@Suppress("unused")
private fun idlPinFragmentArg(v: FragmentArg): Unit =
    when (v) {
        is ScalarArg -> Unit // Int, Float, Bool, Str
        is SlotArg -> Unit // SlotArg
    }

/** `SwitchCase` is hand-written; the IDL fields child, match, when land in child, condition. */
@Suppress("unused")
private fun idlPinSwitchCase(v: SwitchCase): List<Any?> = listOf(v.child, v.condition)
