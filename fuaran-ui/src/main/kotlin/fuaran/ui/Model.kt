// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.ui

/**
 * The native typed tree — the Kotlin surface of the Fuaran UI wire vocabulary, over
 * the Rust reference core.
 *
 * Every closed wire DU is a `sealed` hierarchy so a per-kind `when` is exhaustive at
 * compile time: a new [NodeKind] (or [Binding] / [Action] / [Shape] / ...) that lands
 * without its dispatch arm is a build error, not a silent runtime fallback. This is a
 * **render projection** — the model is decode-oriented (populated by [decodeNode] from
 * the tree JSON a session hands back); it carries no canonical encoder, and
 * closure-typed and host-opaque slots are intentionally dropped or held as raw
 * [JsonValue] rather than reconstructed.
 */

// --------------------------------------------------------------------------- //
// Node envelope
// --------------------------------------------------------------------------- //

/** A UI tree node: a non-empty [id], its [kind], and the optional style/state facets. */
data class Node(
    val id: String,
    val kind: NodeKind,
    val style: SemanticStyle? = null,
    val state: StateBehaviour? = null,
    val accessibility: Accessibility? = null,
)

/** Semantic style facet (WIRE_FORMAT.md 3.1). `role` / `voice` are held as raw tokens (render-only). */
data class SemanticStyle(
    val emphasis: Emphasis = Emphasis.Normal,
    val tone: ToneVariant = ToneVariant.Default,
    val weight: StyleWeight = StyleWeight.Standard,
    val role: String? = null,
    val voice: String? = null,
)

/** Optional load/empty/error surfaces. `onError` is an unobservable closure — presence only. */
data class StateBehaviour(
    val onLoading: Node? = null,
    val onEmpty: Node? = null,
    val hasOnError: Boolean = false,
)

/** Accessibility facet — carried best-effort for the render projection. */
data class Accessibility(
    val label: Binding? = null,
    val labelledBy: String? = null,
    val describedBy: String? = null,
    val role: String? = null,
    val liveRegion: String? = null,
    val hidden: Binding? = null,
)

// --------------------------------------------------------------------------- //
// NodeKind — the flat primitive discriminator (WIRE_FORMAT.md 3.2)
// --------------------------------------------------------------------------- //

/**
 * The node's primitive discriminator. The four behavioural categories
 * (Layout / Display / Input / Visualisation) are a host-side classification recovered
 * on decode ([NodeCategory]), not a level of wire nesting.
 */
sealed interface NodeKind

// --- Layout ---------------------------------------------------------------- //

data class Box(
    val children: List<Node>,
    val layout: BoxLayout,
    val role: BoxRole,
    val heading: TextSource? = null,
) : NodeKind

data class SplitPanel(val children: List<Node>, val weight: Double) : NodeKind

data class Tabs(
    val activeIndex: Binding,
    val children: List<Node>,
    val orientation: Orientation,
    val activeTag: Binding? = null,
    val tabTags: List<String>? = null,
    val tabHeaders: List<TabHeader>? = null,
) : NodeKind

data class Stepper(val activeStep: Binding, val children: List<Node>) : NodeKind

data class SummaryList(val children: List<Node>, val heading: TextSource? = null) : NodeKind

data class Disclosure(
    val children: List<Node>,
    val heading: TextSource,
    val open: Binding,
    val defaultOpen: Boolean,
) : NodeKind

data class Modal(
    val children: List<Node>,
    val dismissable: Boolean,
    val open: Binding,
    val heading: TextSource? = null,
    val onDismiss: Action? = null,
) : NodeKind

data class ScrollArea(
    val children: List<Node>,
    val orientation: ScrollOrientation,
    val maxHeight: Int? = null,
    val maxWidth: Int? = null,
) : NodeKind

data class Mount(
    val scopeId: String,
    val capabilities: List<String>,
    val channel: MountChannel,
    val inputs: Map<String, FragmentArg>? = null,
) : NodeKind

data class Switch(
    val stateKey: String,
    val cases: List<SwitchCase>,
    val default: Node,
) : NodeKind

data class SwitchCase(val match: String, val child: Node)

data class TabHeader(val label: TextSource, val icon: String? = null, val disabled: Binding? = null)

data class MountChannel(val direction: MountDirection, val messageShape: String? = null)

// --- Display --------------------------------------------------------------- //

data class Heading(val level: Int, val text: TextSource, val variant: HeadingVariant) : NodeKind

data class Markdown(val text: TextSource) : NodeKind

data class Metric(
    val label: TextSource,
    val source: Binding,
    val format: ValueFormat,
    val emphasis: Emphasis,
    val tone: ToneVariant,
    val weight: StyleWeight,
    val icon: String? = null,
    val subtext: TextSource? = null,
    val trend: Binding? = null,
    val trendFormat: ValueFormat? = null,
) : NodeKind

data class Badge(val label: TextSource, val variant: BadgeVariant) : NodeKind

data class Sparkline(val source: Binding) : NodeKind

data class Callout(
    val body: TextSource,
    val heading: TextSource,
    val tone: ToneVariant,
    val dismissable: Boolean,
    val icon: String? = null,
) : NodeKind

data class Progress(
    val fraction: Binding,
    val indeterminate: Boolean,
    val label: TextSource,
    val tone: ToneVariant,
) : NodeKind

data class Skeleton(val rows: Int) : NodeKind

data class LabelValueRow(
    val label: TextSource,
    val source: Binding,
    val format: ValueFormat,
    val emphasis: Boolean,
    val help: TextSource? = null,
) : NodeKind

data class Link(
    val href: Binding,
    val label: TextSource,
    val download: Boolean,
    val rel: String? = null,
    val target: String? = null,
) : NodeKind

data class Image(val alt: TextSource, val src: Binding, val variant: ImageVariant) : NodeKind

data class ListNode(val items: List<TextSource>, val ordered: Boolean) : NodeKind

data class Toast(
    val dismissable: Boolean,
    val message: TextSource,
    val open: Binding,
    val tone: ToneVariant,
) : NodeKind

data class CodeBlock(
    val code: String,
    val copyable: Boolean,
    val highlightLines: List<Int>,
    val language: String,
    val lineNumbers: Boolean,
) : NodeKind

data class Math(val display: MathDisplay, val source: String) : NodeKind

data class Drawing(
    val shapes: List<Shape>,
    val style: DrawStyle,
    val viewBox: ViewBox,
    val title: TextSource? = null,
    val description: TextSource? = null,
) : NodeKind

// --- Input ----------------------------------------------------------------- //

data class Form(
    val fields: List<FormField>,
    val onSubmit: Action,
    val submitLabel: TextSource,
    val disabled: Binding? = null,
) : NodeKind

data class FormField(
    val id: String,
    val kind: FormFieldKind,
    val label: TextSource,
    val required: Boolean,
    val help: TextSource? = null,
)

data class Button(
    val label: TextSource,
    val onClick: Action,
    val variant: ButtonVariant,
    val disabled: Binding? = null,
    val icon: String? = null,
) : NodeKind

data class FileUpload(
    val accept: List<String>,
    val label: TextSource,
    val multiple: Boolean,
    val disabled: Binding? = null,
) : NodeKind

data class Select(
    val label: TextSource,
    val source: Binding,
    val multiple: Boolean = false,
    val value: Binding? = null,
    val values: Binding? = null,
    val placeholder: TextSource? = null,
    val disabled: Binding? = null,
) : NodeKind

data class Filters(val items: List<FilterItem>) : NodeKind

data class FilterItem(val name: String, val label: TextSource, val kind: FilterKind)

// --- Visualisation --------------------------------------------------------- //

data class DataGrid(
    val columns: List<GridColumn>,
    val editable: Boolean,
    val source: Binding,
    val rowKeyField: String? = null,
    val staticRows: StaticRows? = null,
) : NodeKind

data class GridColumn(
    val label: String,
    val kind: CellKind,
    val format: ValueFormat,
    val width: ColumnWidth,
    val field: String? = null,
)

data class StaticRows(val headers: List<TextSource>, val rows: List<List<TextSource>>)

data class Chart(
    val kind: ChartKind,
    val source: Binding,
    val stacked: Boolean,
    val xField: String,
    val yFields: List<String>,
    val title: TextSource? = null,
) : NodeKind

data class MapNode(
    val centreLatitude: Double,
    val centreLongitude: Double,
    val source: Binding,
    val zoom: Int,
) : NodeKind

// --- Structural ------------------------------------------------------------ //

data class Custom(
    val moduleId: String,
    val componentId: String,
    val props: JsonValue,
    val contentHash: ContentHash? = null,
    val exposedNodeIds: List<String>? = null,
) : NodeKind

data class ContentHash(val algorithm: String, val hash: String, val strictness: HashStrictness)

data class ErrorBoundary(val child: Node, val fallback: Node) : NodeKind

data class FragmentDecl(
    val name: String,
    val body: Node,
    val holes: List<HoleDecl>? = null,
    val effect: EffectClass? = null,
) : NodeKind

data class FragmentRef(val name: String, val args: Map<String, FragmentArg>? = null) : NodeKind

// --------------------------------------------------------------------------- //
// Box layout
// --------------------------------------------------------------------------- //

sealed interface BoxLayout

data class FlexLayout(val direction: Orientation, val wrap: Boolean, val gap: Int? = null) : BoxLayout

data class GridLayout(val cols: Int, val gap: Int? = null, val templateColumns: String? = null) : BoxLayout

data object AutoLayout : BoxLayout

// --------------------------------------------------------------------------- //
// TextSource
// --------------------------------------------------------------------------- //

sealed interface TextSource

data class LiteralText(val text: String) : TextSource

data class BoundText(val binding: Binding) : TextSource

data class I18nText(val key: String, val args: JsonValue? = null) : TextSource

// --------------------------------------------------------------------------- //
// Binding
// --------------------------------------------------------------------------- //

/**
 * A reactive value source. Closure-bearing accessors (`Computed.fn`, `Query.accessor`)
 * do not survive the wire — the render projection keeps the declarative skeleton (names,
 * dependency edges) and drops the closure.
 */
sealed interface Binding

/** A literal payload. `value` is the raw JSON — a typed slot or the host schema decomposes it. */
data class StaticBinding(val value: JsonValue) : Binding

data class StateBinding(val key: String, val defaultValue: JsonValue? = null) : Binding

data class QueryBinding(val name: String, val dependsOn: List<String>? = null) : Binding

data class FilterBinding(val name: String) : Binding

data class SelectionBinding(val name: String? = null) : Binding

data object ComputedBinding : Binding

data class I18nBinding(val key: String, val args: JsonValue? = null) : Binding

data class LocalBinding(val flushOn: LocalFlushTrigger, val initialFrom: Binding) : Binding

data class FormatBinding(
    val format: NumberFormat,
    val locale: LocaleSource,
    val source: Binding,
) : Binding

/**
 * A declarative dataframe transform. `source` (columnar table) and `pipeline` (ordered
 * steps) are owned by the `Fuaran.Core` codec, so the render projection holds them as
 * raw [JsonValue] — the sanctioned "don't decompose content the host doesn't own"
 * posture (WIRE_FORMAT.md 3.3). `params` binds pipeline params to scalar sources.
 */
data class TransformBinding(
    val source: JsonValue,
    val pipeline: JsonValue,
    val params: List<TransformParam>? = null,
) : Binding

data class TransformParam(val name: String, val from: Binding)

data class InvokeBinding(val capabilityId: String, val args: List<InvokeArg>) : Binding

data class InvokeArg(val addr: String, val value: String)

// --------------------------------------------------------------------------- //
// Action
// --------------------------------------------------------------------------- //

/** A wire-survivable action. Closure payloads (`Dispatch.msg`) collapse to a placeholder. */
sealed interface Action

data class ChainAction(val ops: List<Action>) : Action

data object DispatchAction : Action

data class CallAction(val endpoint: String, val into: CallTarget? = null) : Action

data class NotifyAction(val channel: String? = null, val payload: JsonValue? = null) : Action

data class NavigateAction(val href: String? = null, val payload: JsonValue? = null) : Action

data class SetStateAction(val key: String? = null, val payload: JsonValue? = null) : Action

data class AiToolAction(val payload: JsonValue? = null) : Action

data object CommitLocalAction : Action

data class WriteToClipboardAction(val text: String) : Action

data class ReadFileBodyAction(val fileRef: String, val encoding: FileReadEncoding) : Action

data class InvokeAction(val capabilityId: String, val args: List<InvokeArg>) : Action

sealed interface CallTarget

data class CallIntoState(val key: String) : CallTarget

data class CallIntoQuery(val name: String) : CallTarget

// --------------------------------------------------------------------------- //
// Formats + locale
// --------------------------------------------------------------------------- //

/** The display format on a Metric / LabelValueRow / grid column (`code`-carrying currency). */
sealed interface ValueFormat

data object NoValueFormat : ValueFormat

data class NumberValueFormat(val decimals: Int? = null) : ValueFormat

data class CurrencyValueFormat(val code: String) : ValueFormat

data class PercentValueFormat(val decimals: Int? = null) : ValueFormat

/** The `Binding.Format` numeric format DU (`isoCode`-carrying currency; adds Date / RelativeTime). */
sealed interface NumberFormat

data class NumberNumberFormat(val decimals: Int? = null) : NumberFormat

data class CurrencyNumberFormat(val isoCode: String) : NumberFormat

data class PercentNumberFormat(val decimals: Int? = null) : NumberFormat

data class DateNumberFormat(val dateStyle: DateStyle) : NumberFormat

data class RelativeTimeNumberFormat(val unit: RelativeTimeUnit) : NumberFormat

sealed interface LocaleSource

data object AmbientLocale : LocaleSource

data class ExplicitLocale(val tag: String) : LocaleSource

// --------------------------------------------------------------------------- //
// Form field kinds
// --------------------------------------------------------------------------- //

sealed interface FormFieldKind

data class TextField(val value: Binding) : FormFieldKind

data class NumberField(val value: Binding) : FormFieldKind

data class CheckboxField(val value: Binding) : FormFieldKind

data class ChoiceField(val options: Binding, val value: Binding) : FormFieldKind

data class TextAreaField(val value: Binding, val rows: Int) : FormFieldKind

data class SegmentedChoiceField(
    val options: Binding,
    val value: Binding,
    val orientation: Orientation,
) : FormFieldKind

data class RangedNumberField(
    val value: Binding,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
) : FormFieldKind

data class DateField(
    val value: Binding,
    val variant: DateFieldVariant,
    val min: String? = null,
    val max: String? = null,
    val step: Double? = null,
) : FormFieldKind

// --------------------------------------------------------------------------- //
// Filter kinds
// --------------------------------------------------------------------------- //

sealed interface FilterKind

data class TextFilter(val value: Binding) : FilterKind

data class ChoiceFilter(val options: Binding, val value: Binding) : FilterKind

data class RangeFilter(val value: RangeValue) : FilterKind

data class SegmentedFilter(
    val options: Binding,
    val value: Binding,
    val orientation: Orientation,
) : FilterKind

data class RangeValue(val min: Double, val max: Double)

// --------------------------------------------------------------------------- //
// Local flush trigger
// --------------------------------------------------------------------------- //

sealed interface LocalFlushTrigger

data object OnBlur : LocalFlushTrigger

data object OnEnter : LocalFlushTrigger

data class OnDebounce(val milliseconds: Int) : LocalFlushTrigger

// --------------------------------------------------------------------------- //
// Grid cell kind + column width
// --------------------------------------------------------------------------- //

sealed interface CellKind

data object TextCell : CellKind

sealed interface ColumnWidth

data object AutoWidth : ColumnWidth

data class FixedWidth(val pixels: Int) : ColumnWidth

// --------------------------------------------------------------------------- //
// Drawing
// --------------------------------------------------------------------------- //

data class ViewBox(val minX: Double, val minY: Double, val width: Double, val height: Double)

data class DrawPoint(val x: Double, val y: Double)

/**
 * A drawing style. The four [Binding] paint fields cover shape styling; label glyphs
 * carry additional text fields, held as optional raw tokens (render-only).
 */
data class DrawStyle(
    val fill: Binding? = null,
    val opacity: Binding? = null,
    val stroke: Binding? = null,
    val strokeWidth: Binding? = null,
    val emphasis: String? = null,
    val fontFamily: String? = null,
    val fontSize: Double? = null,
    val textAnchor: String? = null,
)

sealed interface Shape

data class GroupShape(val children: List<Shape>, val style: DrawStyle) : Shape

data class RectangleShape(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val style: DrawStyle,
    val cornerRadius: Double? = null,
) : Shape

data class LineShape(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    val style: DrawStyle,
) : Shape

data class PolylineShape(val points: List<DrawPoint>, val style: DrawStyle) : Shape

data class PolygonShape(val points: List<DrawPoint>, val style: DrawStyle) : Shape

data class CurveShape(val commands: List<CurveCommand>, val style: DrawStyle) : Shape

data class CircleShape(val cx: Double, val cy: Double, val r: Double, val style: DrawStyle) : Shape

data class EllipseShape(
    val cx: Double,
    val cy: Double,
    val rx: Double,
    val ry: Double,
    val style: DrawStyle,
) : Shape

data class LabelShape(
    val x: Double,
    val y: Double,
    val text: TextSource,
    val style: DrawStyle,
) : Shape

sealed interface CurveCommand

data class MoveTo(val to: DrawPoint) : CurveCommand

data class LineTo(val to: DrawPoint) : CurveCommand

data class CubicTo(val control1: DrawPoint, val control2: DrawPoint, val to: DrawPoint) : CurveCommand

data class QuadraticTo(val control: DrawPoint, val to: DrawPoint) : CurveCommand

data object Close : CurveCommand

// --------------------------------------------------------------------------- //
// Fragments (holes / scalars / effects)
// --------------------------------------------------------------------------- //

sealed interface HoleDecl

data class ValueHole(val name: String, val space: HoleValueSpace, val default: Scalar? = null) : HoleDecl

data class SlotHole(val name: String, val kindConstraint: String? = null) : HoleDecl

data class RepeatHole(val name: String, val countSpace: HoleValueSpace) : HoleDecl

sealed interface HoleValueSpace

data class IntRangeSpace(val min: Int, val max: Int) : HoleValueSpace

data class FloatRangeSpace(val min: Double, val max: Double) : HoleValueSpace

data class StringLenSpace(val minLen: Int, val maxLen: Int) : HoleValueSpace

data class EnumSpace(val choices: List<String>) : HoleValueSpace

data object AnyStringSpace : HoleValueSpace

sealed interface Scalar

data class IntScalar(val value: Int) : Scalar

data class FloatScalar(val value: Double) : Scalar

data class BoolScalar(val value: Boolean) : Scalar

data class StrScalar(val value: String) : Scalar

sealed interface FragmentArg

data class ScalarArg(val scalar: Scalar) : FragmentArg

data class SlotArg(val tree: Node) : FragmentArg

data class EffectClass(val hostEffect: HostEffect, val determinism: Determinism)
