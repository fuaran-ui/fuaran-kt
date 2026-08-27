// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
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
        // Layout
        is Box -> NodeCategory.Layout
        is SplitPanel -> NodeCategory.Layout
        is Tabs -> NodeCategory.Layout
        is Stepper -> NodeCategory.Layout
        is SummaryList -> NodeCategory.Layout
        is Disclosure -> NodeCategory.Layout
        is Modal -> NodeCategory.Layout
        is ScrollArea -> NodeCategory.Layout
        is Mount -> NodeCategory.Layout
        is Switch -> NodeCategory.Layout
        // Display
        is Heading -> NodeCategory.Display
        is Markdown -> NodeCategory.Display
        is Metric -> NodeCategory.Display
        is Badge -> NodeCategory.Display
        is Sparkline -> NodeCategory.Display
        is Callout -> NodeCategory.Display
        is Progress -> NodeCategory.Display
        is Skeleton -> NodeCategory.Display
        is Icon -> NodeCategory.Display
        is LabelValueRow -> NodeCategory.Display
        is Fact -> NodeCategory.Display
        is Link -> NodeCategory.Display
        is Image -> NodeCategory.Display
        is Media -> NodeCategory.Display
        is ListNode -> NodeCategory.Display
        is Toast -> NodeCategory.Display
        is CodeBlock -> NodeCategory.Display
        is Math -> NodeCategory.Display
        is Drawing -> NodeCategory.Display
        // Input
        is Form -> NodeCategory.Input
        is Button -> NodeCategory.Input
        is FileUpload -> NodeCategory.Input
        is Select -> NodeCategory.Input
        is Filters -> NodeCategory.Input
        // Visualisation
        is DataGrid -> NodeCategory.Visualisation
        is Chart -> NodeCategory.Visualisation
        is MapNode -> NodeCategory.Visualisation
        // Structural
        is Custom -> NodeCategory.Structural
        is ErrorBoundary -> NodeCategory.Structural
        is FragmentDecl -> NodeCategory.Structural
        is FragmentRef -> NodeCategory.Structural
    }

/** The wire discriminator name for a decoded [NodeKind] — used by the corpus coverage report. */
fun NodeKind.discriminator(): String =
    when (this) {
        is Box -> "Box"
        is SplitPanel -> "SplitPanel"
        is Tabs -> "Tabs"
        is Stepper -> "Stepper"
        is SummaryList -> "SummaryList"
        is Disclosure -> "Disclosure"
        is Modal -> "Modal"
        is ScrollArea -> "ScrollArea"
        is Mount -> "Mount"
        is Switch -> "Switch"
        is Heading -> "Heading"
        is Markdown -> "Markdown"
        is Metric -> "Metric"
        is Badge -> "Badge"
        is Sparkline -> "Sparkline"
        is Callout -> "Callout"
        is Progress -> "Progress"
        is Skeleton -> "Skeleton"
        is Icon -> "Icon"
        is LabelValueRow -> "LabelValueRow"
        is Fact -> "Fact"
        is Link -> "Link"
        is Image -> "Image"
        is Media -> "Media"
        is ListNode -> "List"
        is Toast -> "Toast"
        is CodeBlock -> "CodeBlock"
        is Math -> "Math"
        is Drawing -> "Drawing"
        is Form -> "Form"
        is Button -> "Button"
        is FileUpload -> "FileUpload"
        is Select -> "Select"
        is Filters -> "Filters"
        is DataGrid -> "DataGrid"
        is Chart -> "Chart"
        is MapNode -> "Map"
        is Custom -> "Custom"
        is ErrorBoundary -> "ErrorBoundary"
        is FragmentDecl -> "FragmentDecl"
        is FragmentRef -> "FragmentRef"
    }
