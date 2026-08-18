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
    /** 0.2.0 — omitted on the wire at the default (`Horizontal`). */
    val orientation: Orientation = Orientation.Horizontal,
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

/**
 * The declarative selector. The branch follows a **state key** or — since the 0.2.x widening —
 * any [Binding], so a `Selection` makes the branch follow the clicked row. The schema requires
 * at least one of the two (`anyOf`), which [decodeNode] enforces; both present is legitimate and
 * `on` wins, being the more specific declaration.
 */
data class Switch(
    val stateKey: String?,
    val cases: List<SwitchCase>,
    val default: Node,
    val on: Binding? = null,
) : NodeKind

data class SwitchCase(val match: String, val child: Node)

data class TabHeader(val label: TextSource, val icon: String? = null, val disabled: Binding? = null)

data class MountChannel(val direction: MountDirection, val messageShape: String? = null)

// --- Display --------------------------------------------------------------- //

data class Heading(val level: Int, val text: TextSource, val variant: HeadingVariant) : NodeKind

data class Markdown(val text: TextSource) : NodeKind

data class Metric(
    val label: TextSource,
    /**
     * 0.2.0 rename law — a *scalar displayed value* is named `value` on the wire
     * (`source` is reserved for collection feeds); the retired `Metric.source`
     * spelling is a hard decode error in the reference core.
     */
    val value: Binding,
    /** 0.2.x — the stylistic fields are omitted on the wire at their defaults. */
    val format: ValueFormat = NoValueFormat,
    val emphasis: Emphasis = Emphasis.Normal,
    val tone: ToneVariant = ToneVariant.Default,
    val weight: StyleWeight = StyleWeight.Standard,
    val icon: String? = null,
    val subtext: TextSource? = null,
    val trend: Binding? = null,
    val trendFormat: ValueFormat? = null,
) : NodeKind

data class Badge(val label: TextSource, val variant: BadgeVariant) : NodeKind

data class Sparkline(val source: Binding) : NodeKind

data class Callout(
    val body: TextSource,
    /** 0.2.0 — omitted on the wire when `false`. */
    val dismissable: Boolean = false,
    val tone: ToneVariant = ToneVariant.Default,
    val heading: TextSource? = null,
    val icon: String? = null,
) : NodeKind

data class Progress(
    val fraction: Binding,
    /** 0.2.0 — omitted on the wire when `false`. */
    val indeterminate: Boolean = false,
    val tone: ToneVariant = ToneVariant.Default,
    val label: TextSource? = null,
    val caveat: TextSource? = null,
) : NodeKind

data class Skeleton(val rows: Int) : NodeKind

/** A named glyph from the host's icon set. `icon` is the name; the host owns the mapping. */
data class Icon(
    val icon: String,
    /** Absent ⇒ decorative: the a11y layer hides it. Present ⇒ a labelled `img` role. */
    val label: String? = null,
    val size: IconSize = IconSize.Medium,
    val tone: ToneVariant = ToneVariant.Default,
) : NodeKind

data class LabelValueRow(
    val label: TextSource,
    /** 0.2.0 rename law — scalar displayed value ⇒ `value` (see [Metric.value]). */
    val value: Binding,
    val format: ValueFormat = NoValueFormat,
    /** The behavioural bool (not the [Emphasis] style DU); 0.2.2 — omitted when `false`. */
    val emphasis: Boolean = false,
    val help: TextSource? = null,
) : NodeKind

/**
 * The labeled TEXT fact — [Metric]'s complementary kind (0.2.x). `value` is a
 * [TextSource] (the same vocabulary the labels use); `emphasis` is the behavioural
 * bool (omit-when-false), `tone` omit-when-default; `help` / `icon` optional.
 */
data class Fact(
    val label: TextSource,
    val value: TextSource,
    val emphasis: Boolean = false,
    val tone: ToneVariant = ToneVariant.Default,
    val help: TextSource? = null,
    val icon: String? = null,
) : NodeKind

data class Link(
    val href: Binding,
    val label: TextSource,
    val download: Boolean,
    val rel: String? = null,
    val target: String? = null,
    val protection: LinkProtection? = null,
) : NodeKind

data class Image(val alt: TextSource, val src: Binding, val variant: ImageVariant) : NodeKind

data class ListNode(val items: List<TextSource>, val ordered: Boolean) : NodeKind

data class Toast(
    val message: TextSource,
    val open: Binding,
    val tone: ToneVariant = ToneVariant.Default,
    /** 0.2.0 — omitted on the wire when `true` (the one inverted default). */
    val dismissable: Boolean = true,
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

/**
 * 0.2.0 filters-unification: a filter chip's control is an ordinary [FormFieldKind] —
 * the parallel `FilterKind` DU (`TextFilter` / `ChoiceFilter` / `RangeFilter` /
 * `SegmentedFilter`) is retired; its discriminators are a hard `UNKNOWN_DU_CASE`.
 * A chip control with no `value` key decodes to the auto binding `Filter(<name>)`.
 */
data class FilterItem(val name: String, val label: TextSource, val kind: FormFieldKind)

// --- Visualisation --------------------------------------------------------- //

/**
 * The row-feed grid.
 *
 * The sort / page / edit positions are addressed through STATE keys rather than literal values —
 * `sortStateKey`, `pageStateKey`, `editStateKey` — so a control can move them; `defaultSort` and
 * `pageSize` are the initial configuration. The projection decodes all six faithfully (it is a
 * view of the wire, so dropping a declaration would misreport the tree); acting on them is the
 * renderer's business, not the decoder's.
 */
data class DataGrid(
    val columns: List<GridColumn>,
    val source: Binding,
    /** 0.2.0 — omitted on the wire when `false`. */
    val editable: Boolean = false,
    val rowKeyField: String? = null,
    val staticRows: StaticRows? = null,
    val sortStateKey: String? = null,
    val pageStateKey: String? = null,
    val editStateKey: String? = null,
    /** Rows per page. The schema pins `minimum: 1` — a zero page size paginates nothing. */
    val pageSize: Int? = null,
    val defaultSort: DefaultSort? = null,
) : NodeKind

data class GridColumn(
    val label: String,
    val kind: CellKind,
    /** 0.2.x — `format` / `width` omitted on the wire at their defaults. */
    val format: ValueFormat = NoValueFormat,
    val width: ColumnWidth = AutoWidth,
    /** The declarative row-field projection; `value` (a closure) is presence-only and dropped. */
    val field: String? = null,
)

data class StaticRows(
    val headers: List<TextSource>,
    val rows: List<List<TextSource>>,
    val defaultSort: DefaultSort? = null,
    val sortable: Boolean? = null,
)

/** An initial sort: a zero-based header index (the schema pins `minimum: 0`) plus a direction. */
data class DefaultSort(val column: Int, val direction: SortDirection)

data class Chart(
    val kind: ChartKind,
    val source: Binding,
    val xField: String,
    val yFields: List<String>,
    /** Round-trips when present; absent (the legacy wire) defaults to `false`. */
    val stacked: Boolean = false,
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

/** 0.2.0 — optional `defaultValue`: yielded (raw) before the filter is first written. */
data class FilterBinding(val name: String, val defaultValue: JsonValue? = null) : Binding

/**
 * A row-selection source over the grid at [nodeId]. 0.2.9 — optional `defaultValue`
 * (the `Filter.defaultValue` convention); 0.2.10 — optional `field` (the declarative
 * row-field projection: present ⇒ the accessor projects that field off the clicked row).
 */
data class SelectionBinding(
    val nodeId: String,
    val defaultValue: JsonValue? = null,
    val field: String? = null,
) : Binding

data object ComputedBinding : Binding

/**
 * The host-furnished instant. Carries no payload: the value is supplied at resolve time by the
 * host clock, which is why it is a `Clock`-determinism source rather than wire data.
 */
data object NowBinding : Binding

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

data class NotifyAction(val channel: String, val payload: JsonValue) : Action

data class NavigateAction(val route: String) : Action

/**
 * Write a state slot. Exactly ONE of [value] (a literal payload) and [valueFrom] (a binding
 * resolved at dispatch time) is present — the schema states it as a `oneOf`, so both-present is
 * a reject rather than a precedence question.
 */
data class SetStateAction(
    val key: String,
    val value: JsonValue? = null,
    val valueFrom: Binding? = null,
) : Action

data class AiToolAction(val toolName: String, val args: JsonValue) : Action

data class CommitLocalAction(val nodeId: String) : Action

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

data class SignificantDigitsValueFormat(val digits: Int) : ValueFormat

data class DateValueFormat(val format: String) : ValueFormat

/** An elapsed-time format: the unit the raw value counts in, plus the rendering style. */
data class DurationValueFormat(val unit: DurationUnit, val style: DurationStyle) : ValueFormat

/**
 * A relative-time format ("3 minutes ago"). Distinct from [RelativeTimeNumberFormat], which is the
 * same vocabulary in `Binding.Format`'s DU - the wire carries two format DUs and a case name in
 * one is not a case in the other.
 */
data class RelativeTimeValueFormat(val unit: RelativeTimeUnit) : ValueFormat

/** The formatter is a closure — presence only. */
data object CustomValueFormat : ValueFormat

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

/** The switch affordance: the same boolean slot as [CheckboxField], a different control. */
data class ToggleField(val value: Binding) : FormFieldKind

data class ChoiceField(val options: Binding, val value: Binding) : FormFieldKind

data class TextAreaField(val value: Binding, val rows: Int) : FormFieldKind

data class SegmentedChoiceField(
    val options: Binding,
    val value: Binding,
    /** Decode-optional (0.2.0) — absent restores the language default `Horizontal`. */
    val orientation: Orientation = Orientation.Horizontal,
) : FormFieldKind

data class RangedNumberField(
    val value: Binding,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
) : FormFieldKind

/**
 * 0.2.0 — the dual-thumb numeric range control (absorbed the retired
 * `FilterKind.RangeFilter`). A `Static` pair rides as the bare `{"max":…,"min":…}`
 * object (no `$type`); optional bounds omitted when absent.
 */
data class RangeField(
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

/**
 * 0.7.0 — the single-control date range: `RangeField`'s pair mechanics with
 * `DateField`'s value conventions (an identical field list to `DateField`,
 * reusing `DateFieldVariant`). A `Static` pair rides as the bare
 * `{"from":…,"to":…}` object (no `$type`); `min`/`max` (ISO strings) and `step`
 * (seconds) bound BOTH ends and are omitted when absent. In a filter context the
 * pair binds ONE filter param, not two — the reason the case exists rather than
 * two coordinated `DateField`s.
 */
data class DateRangeField(
    val value: Binding,
    val variant: DateFieldVariant,
    val min: String? = null,
    val max: String? = null,
    val step: Double? = null,
) : FormFieldKind

// --------------------------------------------------------------------------- //
// Local flush trigger
// --------------------------------------------------------------------------- //

sealed interface LocalFlushTrigger

data object OnBlur : LocalFlushTrigger

data object OnSubmit : LocalFlushTrigger

data object OnCommitAction : LocalFlushTrigger

data class OnDebounce(val milliseconds: Int) : LocalFlushTrigger

// --------------------------------------------------------------------------- //
// Grid cell kind + column width
// --------------------------------------------------------------------------- //

sealed interface CellKind

data object TextCell : CellKind

data object NumericCell : CellKind

data object DateCell : CellKind

/** `onEdit` closure — always emitted; presence only. */
data object EditableCell : CellKind

/** `get` + `onToggle` closures — always emitted; presence only. */
data object CheckboxCell : CellKind

/** The `onClick` closure is always emitted; only the label survives the wire. */
data class ButtonCell(val label: TextSource) : CellKind

data class ButtonGroupCell(val labels: List<TextSource>) : CellKind

data object LinkCell : CellKind

data object PillCell : CellKind

/**
 * A value-conditional pill (Phase 750) — the declarative twin of [PillCell], and the ONE
 * cell kind holding no closure, which is exactly why it survives the wire.
 *
 * [field] names the row property that is both the pill's label and the map key; [map]
 * carries value → tone; [defaultTone] covers a value the map does not mention and is
 * omitted on the wire at [ToneVariant.Default].
 *
 * It is the first cell kind this projection carries a PAYLOAD for. Every other one is
 * defined by a closure, which never rides the wire, so the case name was the whole of the
 * information — a `data object` said everything there was to say. A declared tone rule is
 * data, so it has to be carried.
 */
data class TonedPillCell(
    val field: String,
    val map: Map<String, ToneVariant>,
    val defaultTone: ToneVariant = ToneVariant.Default,
) : CellKind

data object ProgressCell : CellKind

data object CustomCell : CellKind

sealed interface ColumnWidth

data object AutoWidth : ColumnWidth

data class FixedWidth(val pixels: Int) : ColumnWidth

data class FlexWidth(val weight: Double) : ColumnWidth

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
    /**
     * Phase 642 — keyed mark identity: a data-bearing shape's derivation-based id
     * (`series-field|category-key`), stable under row reorder and data refresh
     * (object constancy). Omitted when absent; chrome shapes stay unstamped.
     */
    val markId: String? = null,
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
