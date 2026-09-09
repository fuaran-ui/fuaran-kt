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

/** A UI tree node: a non-empty [id], its [kind], and the optional style/state/hint facets. */
data class Node(
    val id: String,
    val kind: NodeKind,
    val style: SemanticStyle? = null,
    val state: StateBehaviour? = null,
    val accessibility: Accessibility? = null,
    /**
     * Whether the node is PRESENT in the rendered output at all (WIRE_FORMAT.md 3.6, Phase 1535).
     *
     * A node-level trait for the reason the trait tier exists: "should this be here at all" is
     * uniform across every kind, and forty-odd per-spec spellings of it would be forty-odd
     * independently driftable decisions about one concept.
     *
     * It is NOT [Accessibility.hidden], and the two have opposite polarity: a resolved `false` here
     * emits NOTHING — no element, no layout, no accessibility-tree entry — where `hidden` is
     * `aria-hidden` over a node that IS rendered. Content the reader should not have now takes
     * this; decoration the reader should never hear takes `hidden`.
     *
     * Any outcome other than a resolved `false` renders the node, unresolved and errored included:
     * hiding on absence is the one failure a reader cannot see, cannot report and cannot work
     * around.
     */
    val visible: Binding? = null,
    /**
     * The supplementary hint (WIRE_FORMAT.md 3.1, "the tooltip trait" — Phase 1112).
     *
     * **A node-level TRAIT, not a field of any kind.** "A short supplementary description of this
     * thing" does not vary with whether the thing is a button or a metric, so it sits on the
     * envelope beside [accessibility]. `ButtonSpec`'s legacy host-only `tooltip` slot (§10.1) is
     * NOT a second spelling: a `tooltip` inside a `kind` object is an unknown key, tolerated and
     * ignored under rule 2, and this decoder never reads one from there.
     *
     * **It is a DESCRIPTION, never a NAME.** [Accessibility.label] names the element; this
     * supplements a name that already exists. A consumer projects it as a description
     * (`aria-describedby` on an HTML host) and never as a label — an icon-only control needs both
     * slots saying different things, and conflating them leaves such a control with two competing
     * names and no description.
     *
     * The canonical encoding of a literal hint is a BARE STRING; the `{"$type":"Literal"}`
     * envelope is decode-accepted, exactly as at every other [TextSource] slot. A non-string
     * scalar is refused rather than stringified — a number one lenient `toString` away from
     * minting hint text out of a JSON type is a defect no downstream check could ever catch.
     */
    val tooltip: TextSource? = null,
)

/** Semantic style facet (WIRE_FORMAT.md 3.1). `role` / `voice` are held as raw tokens (render-only). */
data class SemanticStyle(
    val emphasis: Emphasis = Emphasis.Normal,
    val tone: ToneVariant = ToneVariant.Default,
    val weight: StyleWeight = StyleWeight.Standard,
    val role: String? = null,
    val voice: String? = null,
    /**
     * Phase 1472 — the one member of this record that is not presentational: it declares the text
     * DIRECTION, which is a fact about the content rather than a style choice. Omitted at `auto`
     * on both boundaries, like its siblings; neither a non-string nor an unrecognised token falls
     * back, because a document declaring a direction the host cannot read must not be rendered in
     * the opposite one in silence.
     */
    val direction: TextDirection = TextDirection.auto,
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
    /**
     * Phase 1473 — the print-break controls. Both omit at `false`, and both are declarations about
     * PAGINATED media alone: a screen host reads them and emits nothing.
     */
    val breakBefore: Boolean = false,
    val keepTogether: Boolean = false,
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
    /**
     * WIRE_FORMAT.md 3.6.11 — omitted at [ModalityKind.Modal], which is the blocking modality every
     * pre-modality document meant. A present value outside the two is `UNKNOWN_DU_CASE` and a
     * non-string is `WRONG_TYPE`; neither falls back, because a document asking for a popover and
     * getting a blocking modal has been answered with a different affordance.
     */
    val modality: ModalityKind = ModalityKind.Modal,
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
    /**
     * Phase 1122 — the timed carousel: advance to the next case every this-many milliseconds,
     * omitted at absence. It declares the one fact a host cannot recover from the tree — every
     * other half of a carousel was already composable, and nothing in any arrangement of those
     * says a timer exists.
     *
     * A DURATION, never a flag. Non-positive and fractional values are REFUSED rather than
     * canonicalised: `0` is what an emitter reaches for to mean "off" and the language already has
     * a spelling for off, an absent key.
     */
    val autoAdvanceMs: Int? = null,
) : NodeKind

/**
 * Phase 1535 — the two spellings of a case's condition. They interleave freely in one ordered
 * `cases` array, and first-match-wins runs over the array in AUTHORED order: a host evaluates case
 * *n* fully before considering case *n+1*, and must not batch all the matches ahead of all the
 * predicates.
 */
sealed interface SwitchCondition

/** Compares the switch's resolved selector against a literal string. */
data class MatchCondition(val value: String) : SwitchCondition

/**
 * Evaluates a `Binding<bool>` and takes the case on a RESOLVED `true` only — a resolved `false`, an
 * unresolved binding and an errored one all fall through. There is no truthiness rule: `0`, `""`
 * and `"false"` are refused by the coercion rather than read as `false`.
 *
 * It consults no selector at all, so a switch whose cases are ALL predicates needs no `on` and has
 * no state key for anything to write.
 */
data class WhenCondition(val binding: Binding) : SwitchCondition

/**
 * EXACTLY ONE of `match` and `when` is present (3.6) — both together and neither at all are decode
 * errors, the same shape and the same reasoning as `SetState`'s `value` / `valueFrom` pair.
 *
 * A case naming no condition is not a case that never matches; it is a document whose author meant
 * something the wire cannot say, and a host that silently skipped it would render the `default` and
 * report nothing. A precedence rule for "both" would have to be specified, agreed on every host and
 * remembered by every author, for a document nobody meant to write.
 */
data class SwitchCase(val condition: SwitchCondition, val child: Node)

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
    /**
     * 3.6.1 — which way the quantity IMPROVES. TOTAL, not nullable, and decoded independently of
     * [trend]: the wire's "absent means `HigherIsBetter`" is a DEFAULT rather than a third state,
     * so modelling it as `null` would push the decision back out to every reader. A polarity
     * declared with no [trend] is inert (legal per clause 4) and is kept rather than dropped —
     * dropping it would silently rewrite the author's document because this surface judged the
     * declaration pointless.
     */
    val trendPolarity: TrendPolarity = TrendPolarity.HigherIsBetter,
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

/**
 * One alternate rendition of the SAME picture at a declared intrinsic pixel width
 * (WIRE_FORMAT.md 3.6.4). Both members are required within the entry, and [width] is the `w`
 * descriptor a client selects on — hence positive, enforced at decode rather than left to a
 * renderer to discover, since a `0w` candidate is one no client can ever select.
 */
data class SrcSetEntry(val src: Binding, val width: Int)

/**
 * WIRE_FORMAT.md 3.6.2–3.6.5. The three presentation slots are identity-defaulted (absent means
 * [ImageFit.Natural] / [ImageAspect.Natural] / [ImageLoading.Eager]), so a document written before
 * they existed decodes to today's behaviour.
 *
 * [srcSet] is the missing-list-field decode class: **absent MEANS the empty list**, never null. A
 * present `"srcSet":null` is refused (`WRONG_TYPE`) because absence already has a spelling, and the
 * authored ORDER is preserved — the wire is ordered data, and a surface that sorted it would be
 * viewing a document its author did not write. Presentation order (ascending by width) is a
 * renderer's business, not this projection's.
 *
 * [expandable] is the only slot here that declares an INTERACTION rather than a picture: the
 * full-size asset is reachable from the rendered image. It carries no `Action`, reaches no
 * closure-bearing position, and is `false` by default.
 */
data class Image(
    val alt: TextSource,
    val src: Binding,
    val variant: ImageVariant,
    val fit: ImageFit = ImageFit.Natural,
    val aspectRatio: ImageAspect = ImageAspect.Natural,
    val loading: ImageLoading = ImageLoading.Eager,
    /** CONTENT, not an identity default — a full [TextSource], so a caption is i18n-capable. */
    val caption: TextSource? = null,
    val srcSet: List<SrcSetEntry> = emptyList(),
    val expandable: Boolean = false,
) : NodeKind

/**
 * The playback surface (WIRE_FORMAT.md 3.6.6) — **ONE kind with two variants, never two kinds**.
 * Everything a video and an audio surface share is stated once here; only what genuinely differs
 * lives in [kind].
 *
 * [label] is REQUIRED and has no default: a media element is a TRANSPORT, never decorative, and
 * there is no value to fall back to that would not be a fabricated name for someone else's
 * recording. A host emits the resolved label as the element's accessible name, always.
 *
 * [controls] is omitted on the wire at **true** (the inverted polarity [Toast.dismissable] also
 * takes): a transport a keyboard user cannot reach is the deviation, so it is what costs a key.
 */
data class Media(
    val label: TextSource,
    val src: Binding,
    val kind: MediaKind,
    /** Omitted on the wire when `true` — the accessible setting is what a document gets for free. */
    val controls: Boolean = true,
    val loop: Boolean = false,
    /**
     * The element's timed-text tracks (3.6.6, Phase 1110), in the AUTHORED order the wire carries.
     *
     * Omitted on the wire when EMPTY — an absent list and an empty one denote the same document —
     * so this restores `[]` and never a null. **Nothing sorts it**, and that is the opposite of
     * [Image.srcSet]'s rule rather than an inconsistency with it: a browser picks ONE candidate
     * from a srcset by an algorithm, so ordering it is canonicalisation, while a reader picks a
     * track from a menu the user agent builds in DOCUMENT order, so ordering it would be rewriting
     * someone else's menu.
     */
    val tracks: List<TrackEntry> = emptyList(),
    /**
     * The text alternative (3.6.6, Phase 1110). On the SPEC rather than on [Video], deliberately:
     * a transcript is the affordance an AUDIO surface needs most, because a recording with no
     * visual channel has nowhere else to put its words.
     *
     * An ordinary optional — absent means the document offers no transcript, which is a different
     * statement from offering an empty one.
     */
    val transcript: TextSource? = null,
) : NodeKind

/**
 * One timed-text track on a [Media] element (WIRE_FORMAT.md 3.6.6, Phase 1110).
 *
 * **Four of the five members are REQUIRED, which makes it the strictest record on the wire.**
 * [default] is the one omitted-at-`false` slot.
 *
 * [srcLang] is required on EVERY kind, where HTML makes it mandatory only on a subtitles track.
 * The extra strictness costs an author one value and buys a menu a user agent can order, a speech
 * engine can pronounce and a reader can tell apart; a track with no language is one nothing
 * downstream can route, and there is no value to default to that would not be an invented claim
 * about someone else's recording.
 *
 * [label] is required for the same reason [Media.label] is: it is the entry a user agent puts in
 * its track menu and the only thing distinguishing one track from another there.
 *
 * [src] is fetched by the browser with no user act, exactly as [Media.src] is, so it carries the
 * §19 render-time obligation — see [fuaran.ui.sanitizedSrc] on this record. The remedy is the
 * POSTER's rather than the source's: an element must have a source but it need not have this
 * track, so a refused track is DROPPED. A `<track>` pointing at the refusal URL is a menu entry
 * that opens onto nothing.
 */
data class TrackEntry(
    val kind: TrackKind,
    val src: Binding,
    val srcLang: String,
    val label: TextSource,
    /**
     * Whether the entry elects itself the default of its kind. Legal bytes elect two — the decoder
     * does not refuse it, because a lenient host would render it anyway and HTML leaves the case
     * undefined — so the RENDERER resolves it, and every host resolves it the same way: the FIRST
     * election of a kind is honoured and a later one loses only its claim on the menu. The
     * election is per kind, so a captions default and a subtitles default coexist.
     */
    val default: Boolean = false,
)

/**
 * Which playback surface a [Media] node is. `$type`-discriminated at `kind.kind`, so an unknown
 * case reports at `…kind.kind.$type` rather than at the bare slot (WIRE_FORMAT.md 6).
 *
 * The set is CLOSED at [Video] | [Audio]: a third surface is an admission to the vocabulary, not a
 * spelling a decoder may guess at.
 */
sealed interface MediaKind

/**
 * The video surface. [autoplay] is a declaration whose rendering is constrained: a host that
 * honours it MUST emit it together with a muted attribute, and MUST NOT mute where it is absent.
 * There is deliberately no `muted` slot — it would be a second knob free to disagree with the
 * first, and the only combination it would add is the one no host may render.
 */
data class Video(val autoplay: Boolean = false, val poster: Binding? = null) : MediaKind

/**
 * The audio surface, whose payload is the discriminator alone.
 *
 * **There is NO autoplay pathway here — in the type, on the wire, or in a render arm.** That is
 * stronger than a default of `false`: a slot defaulting to off is one a document can switch on, and
 * there is no document this format wants to be able to state in which a page begins making sound
 * unbidden. `{"$type":"Audio","autoplay":true}` decodes to an audio surface that does not autoplay,
 * because the value has nowhere to land — an unknown key, tolerated by rule 2 like any other.
 */
data object Audio : MediaKind

/**
 * The sandboxed third-party embed (WIRE_FORMAT.md 3.6.8, Phase 1111) — a Display kind carrying a
 * document URL, a mandatory accessible title, an optional declared aspect ratio, and a closed list
 * of sandbox relaxations that is EMPTY by default.
 *
 * **A KIND, not a [Mount] variant and not a [Media] one.** `Mount` composes a COOPERATING guest —
 * a scope id, a declared message channel, a capability request list, a host-side loader that
 * produced the guest tree — and a third-party page has none of those and cannot acquire them;
 * widening `Mount` to admit an uncooperative third party would weaken every guarantee it makes.
 * `Media` fetches an asset and DISPLAYS it, decoded by the user agent's own codec into no scripting
 * context, where an embed fetches a document and lets it EXECUTE. That difference is why [src]
 * takes its own, stricter egress class (§19.1 — `https` and nothing else) rather than reusing the
 * §19 accept set: see [fuaran.ui.sanitizedSrc] on this record.
 *
 * [title] is REQUIRED, on [Media.label]'s argument one kind over: a frame is a focus container a
 * reader tabs INTO, so there is no decorative embed, and a frame with no accessible name is
 * announced as "frame" and nothing else.
 *
 * [aspectRatio] REUSES [ImageAspect]. The cases are pure layout ratios with nothing image-specific
 * in them and the wire carries bare strings, so the type name reaches no document; minting a
 * parallel enum with identical cases would create two closed sets that must be kept in step.
 */
data class Embed(
    val src: Binding,
    val title: TextSource,
    val aspectRatio: ImageAspect = ImageAspect.Natural,
    /** Omitted on the wire at the EMPTY list, and empty means TOTAL DENIAL — see [EmbedPermission]. */
    val permissions: List<EmbedPermission> = emptyList(),
) : NodeKind

/**
 * Recursive disclosure with tree semantics (WIRE_FORMAT.md 3.6.12, Phase 1120) — a hierarchy of
 * ROWS and, optionally, the names of the two State slots a reader opens rows and selects one
 * through.
 *
 * Its rows are [TreeItem] records rather than [Node]s, and [TreeItem.children] is a list of the
 * same record, which makes this the format's first **self-referential** shape. Item nesting is
 * therefore bounded on its OWN axis (§21.5): the whole hierarchy lives inside one node and consumes
 * no node depth at all.
 *
 * **This kind carries no `expandable` and no `selectable` boolean, and none is coming.** A
 * behaviour the reader drives is declared as a named State key the host both writes and reads; a
 * flag with no key behind it is a decorative control writing state nothing reads.
 *
 * The two slot shapes are fixed by the specification, because a host reading them must not guess:
 * [expandedStateKey] names a JSON **array of row ids** (the rows currently open) and
 * [selectionStateKey] a bare **row-id string**. A value of any other shape reads as *empty* /
 * *none* rather than as an error — this is a host's own state slot, not a wire document, so there
 * is nothing here to refuse, and refusing would blank a tree over a value the reader never
 * authored.
 *
 * **A tree naming no [expandedStateKey] renders FULLY EXPANDED and does not toggle**, which is the
 * only reading under which such a tree shows its content at all.
 *
 * `onSelect` is a closure and is DROPPED here, as every other handler slot in this projection is
 * ([Select] carries no `onChange`, [Disclosure] no `onToggle`).
 */
data class Tree(
    val items: List<TreeItem>,
    val expandedStateKey: String? = null,
    val selectionStateKey: String? = null,
) : NodeKind

/**
 * One row of a [Tree] (3.6.12).
 *
 * [id] and [label] are required; [children] omits at the EMPTY LIST and [icon] when absent — so a
 * leaf carries two keys and nothing else, which is most of a real hierarchy.
 *
 * [id] is required because it is what the two State slots NAME: an id-less row can never be
 * expanded, selected or restored, and a synthesised positional id would move the reader's open
 * branches the moment a sibling was inserted. [label] is a [TextSource] because it is content —
 * authored, translated, bindable.
 *
 * **Row ids MUST be unique within one tree, and that is an EMIT-side obligation rather than a
 * decode refusal** (§8.1's position for `NodeId`, transferred for §8.1's own reason): duplicate
 * detection is a whole-tree property, a decoder streaming a document is not required to carry the
 * id set, and there is no error code for it. A decoder that accepts a duplicate is still
 * conformant, and this one does.
 */
data class TreeItem(
    val id: String,
    val label: TextSource,
    val children: List<TreeItem> = emptyList(),
    val icon: String? = null,
)

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

/**
 * The cross-field operand. `against` is a `Binding`, and that IS the cross-field mechanism
 * rather than an accident of typing: any read slot may take a Binding, and the auto-bind rule
 * already puts every form field's value in State under the field's own id, so
 * `{"$type":"State","key":"<sibling id>"}` reads the sibling with no coordination vocabulary.
 */
data class CompareRule(
    val op: CompareOp,
    val against: Binding,
)

/**
 * A field's declared constraint — the ACCEPTED SET, where `FormFieldKind` names the control.
 * Every slot is optional structurally; the two well-formedness refusals (a rule that constrains
 * nothing; `minLength` above `maxLength`) are relations BETWEEN slots and so live in the
 * decoder's policy layer rather than in this shape.
 */
data class FieldRule(
    val format: TextFormat? = null,
    val pattern: String? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val compare: CompareRule? = null,
    val message: TextSource? = null,
)

data class FormField(
    val id: String,
    val kind: FormFieldKind,
    val label: TextSource,
    val required: Boolean,
    val help: TextSource? = null,
    val rule: FieldRule? = null,
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
    /**
     * Phase 1115 — two ADDITIONAL ingress routes, never replacements for the picker. Both omit at
     * `false`, and the polarity is load-bearing: the shortest upload document is the plain picker,
     * which is what every document written before that revision says.
     */
    val dropTarget: Boolean = false,
    val acceptPaste: Boolean = false,
    /**
     * Phase 1116 (3.6.18) — WHICH of the reader's own recording devices the platform should open in
     * place of the file browser. OPTIONAL rather than omit-at-default: "say nothing" is a state of
     * its own, because an upload naming no device is asking for the file browser, which is not one
     * of the two devices wearing a default.
     */
    val capture: CaptureSource? = null,
    /**
     * Phase 1117 (3.6.20) — the host-registered destination an upload streams to. A NAME, and a
     * name because it must never be an ADDRESS: a wire document comes from an arbitrary emitter,
     * and a URL here would let that emitter choose where a reader's file goes. The empty string is
     * REFUSED rather than read as absence, which would silently turn an upload the author meant to
     * stream into a client-only one.
     */
    val destination: String? = null,
    /**
     * Phase 1548 (3.6.23) — the declared ceilings: how large a file, and how many of them, this
     * control accepts.
     *
     * [maxBytes] is PER FILE rather than per selection. That is what makes it meaningful on a
     * single-file upload and what makes it the quantity a body read can be measured against; a
     * control bounding a whole multiple selection states both members and the total it declares is
     * `maxBytes x maxFiles`. A per-selection sum would be a different quantity wearing the same
     * name.
     *
     * [maxFiles] is meaningful only alongside `multiple`, and beside `"multiple":false` it is INERT
     * — carried, not refused, because the bytes describe a control every host renders identically
     * with or without it, so there is nothing for a decoder to be right about.
     *
     * Both are OPTIONAL and POSITIVE. Absent declares no ceiling, which is every pre-1548 document
     * exactly; `0` and below are refused at decode, because a ceiling of zero is not a small ceiling
     * but a control that can accept nothing. `Int` here is 7.1's signed 32-bit slot, bounded by the
     * strict integer reader rather than by this type.
     */
    val maxBytes: Int? = null,
    val maxFiles: Int? = null,
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
    /** Phase 1473 — the paginated-media pair, on [Box]'s terms. */
    val keepRowsTogether: Boolean = false,
    val repeatHeader: Boolean = false,
    /**
     * Phase 1123 — this grid's rows may be taken out of the page as a file. Omitted at `false`.
     */
    val exportable: Boolean = false,
    /**
     * Phase 1125 — the two sides of ONE shared State key. A grid declaring [transferOutKey] K may
     * RELEASE rows onto K; one declaring [transferInKey] K ACCEPTS rows arriving on it. Separate
     * decoder arms, and the corpus vectors them separately for that reason.
     */
    val transferInKey: String? = null,
    val transferOutKey: String? = null,
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

/**
 * Phase 1491 (4l) — an annotation's x address: a CATEGORY key read under the categorical scale, and
 * an ISO-8601 `Date` read under `Temporal`. Its own type rather than two inline fields, because a
 * range band addresses an x-axis interval with a PAIR of these.
 */
sealed interface ChartAnnotationX

data class CategoryAddress(val key: String) : ChartAnnotationX

data class DateAddress(val iso: String) : ChartAnnotationX

/**
 * Phase 1492 (4l) — a range band's PAIR: two of the same address form, on one axis.
 *
 * THE AXIS IS THE CASE. 4l requires a band to declare which axis it sits on; carrying that as a
 * separate flag beside an untyped pair would admit a document declaring the value axis and
 * addressing it with two category keys. The union tag declares the axis AND types the pair with it,
 * so that document cannot be written by any conformant emitter.
 */
sealed interface ChartAnnotationRange

data class ValueRange(val from: Double, val to: Double) : ChartAnnotationRange

data class XRange(val from: ChartAnnotationX, val to: ChartAnnotationX) : ChartAnnotationRange

/**
 * Phase 1490 (4l) — a chart's data-addressed annotation. An annotation names a place in the DATA's
 * coordinates and, optionally, a label; it carries no geometry and no style at all, which is what
 * makes it survive a data change, a theme flip, a restyle and a resize.
 */
sealed interface ChartAnnotation

/** A horizontal line at [value] in the VALUE axis's own units. */
data class ReferenceLine(val value: Double, val label: TextSource? = null) : ChartAnnotation

/** A VERTICAL line at an x address — the reference line mirrored across the axes. */
data class EventMarker(val at: ChartAnnotationX, val label: TextSource? = null) : ChartAnnotation

/** A shaded interval on either axis, and the one member that draws BEHIND every series. */
data class RangeBand(
    val range: ChartAnnotationRange,
    val label: TextSource? = null,
) : ChartAnnotation

data class Chart(
    val kind: ChartKind,
    val source: Binding,
    val xField: String,
    val yFields: List<String>,
    /** Round-trips when present; absent (the legacy wire) defaults to `false`. */
    val stacked: Boolean = false,
    val title: TextSource? = null,
    /** Phase 1490 — omitted when the chart declares none. */
    val annotations: List<ChartAnnotation>? = null,
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

/**
 * WIRE_FORMAT 3.6.7 (Phase 1082) — the column-fill mode. `cols` is REQUIRED and POSITIVE; there is
 * deliberately no `templateColumns` twin, because the multi-column model realising masonry has no
 * track list for one to name.
 */
data class MasonryLayout(val cols: Int, val gap: Int? = null) : BoxLayout

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
/**
 * The host-furnished instant. Phase 1533 — the declared [grain] truncates the instant BEFORE it is
 * read. Absent is `Second`, which is the identity; a present value outside the four is refused
 * rather than silently read at a neighbouring resolution.
 */
data class NowBinding(val grain: TimeGrain? = null) : Binding

data class I18nBinding(val key: String, val args: JsonValue? = null) : Binding

/**
 * The controlled input's edit buffer (3.3.3). [codec] declares the buffer's OWN codec and replaces
 * the identity on both sides; [commitTo] declares the State key the flush writes to.
 *
 * [commitTo] and the `onCommit` closure are mutually exclusive, because the wire cannot carry the
 * closure and two hosts would otherwise write to different places from identical bytes.
 */
data class LocalBinding(
    val flushOn: LocalFlushTrigger,
    val initialFrom: Binding,
    val codec: NumberFormat? = null,
    val commitTo: String? = null,
    val hasOnCommit: Boolean = false,
) : Binding

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

/**
 * Phase 1534 (3.3.2) — ONE scalar expression evaluated to ONE value over the SAME `ColExpr` algebra
 * a [TransformBinding] pipeline step carries. The case introduces no operator and no expression
 * language of its own, so an expression means here exactly what it means inside a `derive`.
 *
 * [expr] is held as raw [JsonValue] on this surface for the reason [TransformBinding]'s pipeline is:
 * the algebra is owned by the `Fuaran.Core` codec, and a render projection does not decompose
 * content the host does not own.
 *
 * Two DECODE refusals nonetheless apply, both because an expression HAS NO ROW: a `col` reference
 * anywhere in it, and a `param` the binding's own list does not bind. Left admitted, each would
 * decode to an expression whose evaluation could only ever fail, once per render, on every host.
 */
data class ExprBinding(val expr: JsonValue, val params: List<TransformParam>? = null) : Binding

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

/**
 * Phase 1536 — [route] is a [TextSource], not a bare string, so a tree can name a destination it
 * computes from what the reader is looking at. The bare JSON string IS `Literal`'s canonical form,
 * so every document written before the widening decodes exactly as it did.
 *
 * [target] is the CLOSED `Self | Blank` enum, omitted at `Self`, and deliberately not HTML's
 * `target` attribute: that vocabulary also carries `_parent` and `_top`, which are frame-busting
 * gestures a hosted tree must not be able to ask for.
 */
data class NavigateAction(
    val route: TextSource,
    val target: NavigateTarget = NavigateTarget.Self,
) : Action

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

/**
 * Phase 1126 — the payload is a [TextSource]. A `text` that is neither a string nor a
 * `$type`-tagged text source is `WRONG_TYPE` and is never coerced: a host reading the widening as
 * "this member is now open" would put a JSON literal on the reader's clipboard, and a clipboard is
 * a channel the reader later pastes somewhere with authority.
 */
data class WriteToClipboardAction(val text: TextSource) : Action

/**
 * Phase 1124 — the payload-free print, and the ONE action case strict about unrecognised members:
 * page range, size, margins and copies are the host's page setup and the reader's dialogue, so
 * accepting a member here would leave the emitter believing it had constrained a printing it had
 * not, with no error anywhere saying otherwise.
 */
data object PrintAction : Action

/**
 * Phase 1537 — ask, then act. Confirmation is bounded at ONE question: a confirm reachable from
 * either continuation is refused, and the check walks the DECODED continuation so a chain cannot
 * hide the nesting. An absent [onCancel] means "nothing happens", which an absent action already
 * expresses.
 */
data class ConfirmAction(
    val prompt: TextSource,
    val onConfirm: Action,
    val onCancel: Action? = null,
) : Action

/**
 * Phase 1537 — a bare node id, the [CommitLocalAction] shape. It addresses a node in THIS document,
 * so there is nothing for a binding to compute and no [TextSource] here.
 */
data class FocusAction(val nodeId: String) : Action

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

/**
 * Phase 1533 — the elapsed-time rendition. Distinct from [RelativeTimeNumberFormat]: this one is a
 * function of the HOST instant as well as of its source, so a host with no instant renders nothing
 * rather than an invented delta. [unit] is OPTIONAL and its absence is the auto-selection request,
 * not a default.
 */
data class SinceNumberFormat(val unit: RelativeTimeUnit? = null) : NumberFormat

/** Phase 819 — the numeric source counts [unit]s, rendered per the bounded [style]. */
data class DurationNumberFormat(val unit: DurationUnit, val style: DurationStyle) : NumberFormat

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

/**
 * The typeahead / autocomplete field (WIRE_FORMAT.md 3.6.9, Phase 1113) — the searchable form of
 * [ChoiceField].
 *
 * **The line an emitter and a host both have to hold is one sentence:** a BOUNDED KNOWN set the
 * reader scans is a `Choice`; a LARGE, SEARCHABLE or ASYNCHRONOUS set — or one that admits a value
 * not on the list — is a `Combobox`. The failure this distinction prevents is not an invalid
 * document but a valid one: a `Choice` over two hundred options parses, validates and renders
 * everywhere, and is unusable.
 *
 * [options] and [value] are `Choice`'s slots **deliberately and normatively**: the constrained
 * combobox IS a searchable select, so a document migrating between the two changes its `$type` and
 * nothing else, and a host implementing a different value contract here would break exactly that
 * migration. An asynchronous suggestion source needs no vocabulary of its own — a `Query` binding
 * in the ordinary [options] slot IS the async feed, with `dependsOn` giving it the dependency edge.
 *
 * [allowFreeText] omits at `false`, and the polarity is load-bearing: the SHORTEST combobox
 * document is the CONSTRAINED one, so an emitter that says nothing gets the shape a select would
 * have had, and admitting off-list values is the thing it has to ask for. A present member of any
 * type other than boolean is `WRONG_TYPE` and is never coerced — a lenient truthiness read would
 * widen the field on `"no"` and `"false"` alike.
 *
 * **`allowFreeText = false` is not enforceable by any static host and must not be claimed as if it
 * were.** Per §22, client validation is not a trust boundary: a host that accepts submissions
 * re-checks membership server-side, exactly as it re-checks every other declared constraint.
 */
data class ComboboxField(
    val options: Binding,
    val value: Binding,
    val allowFreeText: Boolean = false,
) : FormFieldKind

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

/**
 * Phase 1121 (3.6.19) — SEVERAL values accumulated as removable chips, over a suggestion set that
 * may be open, searchable, asynchronous, or absent entirely.
 *
 * Every member is optional, so `{"$type":"Tokens"}` is a complete document. [allowFreeText] omits
 * at **`true`** — the OPPOSITE polarity to [ComboboxField], and the one thing about this case a
 * host is most likely to get wrong: a combobox's option source is REQUIRED so "constrained" is its
 * resting state, where [suggestions] is optional so "open" is this one's. The default follows the
 * required-ness of the set.
 *
 * [value] is a `Binding<string list>` and the list is ORDERED — chips appear where the reader added
 * them, so a host must not sort or de-duplicate it.
 */
data class TokensField(
    val value: Binding,
    val suggestions: Binding? = null,
    val allowFreeText: Boolean = true,
) : FormFieldKind

/**
 * Phase 1130 (3.6.17) — a subjective score on a small ordinal scale. The line against
 * [RangedNumberField] is who the number belongs to: a rating is a judgement a person GIVES, a
 * ranged number a measurement they REPORT.
 *
 * [max] is the case's only required member and is refused below 1 — a scale with no positions has
 * nothing to draw and no keystroke that could change anything. [value] is a float even where
 * nothing can type a fraction, because the commonest rating a reader sees is an AVERAGE arriving
 * through a query. [allowHalf] governs ENTRY, never display.
 */
data class RatingField(
    val value: Binding,
    val max: Int,
    val allowHalf: Boolean = false,
) : FormFieldKind

/**
 * Phase 1130 (3.6.17) — the platform's own colour picker. A CONTROL, not a `rule.format`: a swatch
 * that opens the operating system's picker, which no format on a text field can produce.
 *
 * Both members optional. The value is `#rrggbb` and nothing else — the one form a native colour
 * input can hold or return — and case is PRESERVED rather than normalised.
 */
data class ColorField(val value: Binding) : FormFieldKind

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
