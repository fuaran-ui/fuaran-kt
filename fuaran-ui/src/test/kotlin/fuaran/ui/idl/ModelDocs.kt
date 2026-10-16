// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.ui.idl

/**
 * The public model's KDoc (Phase 2299), keyed by Kotlin declaration name (`Box`) or by
 * declaration and property (`Box.breakBefore`); `category` and `discriminator` are the two spine
 * functions. The generator emits each entry above the declaration it names, so the generated model
 * reads exactly as the hand-written one did.
 *
 * This is PROSE, not a divergence, which is why it is not in [DIALECT]: it says why a slot is
 * shaped as it is, and the IDL has nowhere to say that. It is orphan-refusing all the same — an
 * entry naming a declaration the generator no longer emits fails generation, so a doc cannot
 * outlive the field it explains.
 */
val MODEL_DOCS: Map<String, String> =
    mapOf(
        "Node" to
            """
            A UI tree node: a non-empty [id], its [kind], and the optional style/state/hint facets.
            """.trimIndent(),
        "Node.visible" to
            """
            Whether the node is PRESENT in the rendered output at all (WIRE_FORMAT.md 3.6, Phase 1535).

            A node-level trait for the reason the trait tier exists: "should this be here at all" is
            uniform across every kind, and forty-odd per-spec spellings of it would be forty-odd
            independently driftable decisions about one concept.

            It is NOT [Accessibility.hidden], and the two have opposite polarity: a resolved `false` here
            emits NOTHING — no element, no layout, no accessibility-tree entry — where `hidden` is
            `aria-hidden` over a node that IS rendered. Content the reader should not have now takes
            this; decoration the reader should never hear takes `hidden`.

            Any outcome other than a resolved `false` renders the node, unresolved and errored included:
            hiding on absence is the one failure a reader cannot see, cannot report and cannot work
            around.
            """.trimIndent(),
        "Node.tooltip" to
            """
            The supplementary hint (WIRE_FORMAT.md 3.1, "the tooltip trait" — Phase 1112).

            **A node-level TRAIT, not a field of any kind.** "A short supplementary description of this
            thing" does not vary with whether the thing is a button or a metric, so it sits on the
            envelope beside [accessibility]. `ButtonSpec`'s legacy host-only `tooltip` slot (§10.1) is
            NOT a second spelling: a `tooltip` inside a `kind` object is an unknown key, tolerated and
            ignored under rule 2, and this decoder never reads one from there.

            **It is a DESCRIPTION, never a NAME.** [Accessibility.label] names the element; this
            supplements a name that already exists. A consumer projects it as a description
            (`aria-describedby` on an HTML host) and never as a label — an icon-only control needs both
            slots saying different things, and conflating them leaves such a control with two competing
            names and no description.

            The canonical encoding of a literal hint is a BARE STRING; the `{"${'$'}type":"Literal"}`
            envelope is decode-accepted, exactly as at every other [TextSource] slot. A non-string
            scalar is refused rather than stringified — a number one lenient `toString` away from
            minting hint text out of a JSON type is a defect no downstream check could ever catch.
            """.trimIndent(),
        "Node.fallback" to
            """
            Phase 1812 — the author-declared degraded rendering: a full node a BEHIND reader (one that
            meets this node's kind as a transport-only `Unknown`) renders in place of its placeholder. A
            current reader decodes and preserves it and never renders it. Omitted when absent.
            """.trimIndent(),
        "SemanticStyle" to
            """
            Semantic style facet (WIRE_FORMAT.md 3.1). `role` / `voice` are held as raw tokens (render-only).
            """.trimIndent(),
        "SemanticStyle.direction" to
            """
            Phase 1472 — the one member of this record that is not presentational: it declares the text
            DIRECTION, which is a fact about the content rather than a style choice. Omitted at `auto`
            on both boundaries, like its siblings; neither a non-string nor an unrecognised token falls
            back, because a document declaring a direction the host cannot read must not be rendered in
            the opposite one in silence.
            """.trimIndent(),
        "StateBehaviour" to
            """
            Optional load/empty/error surfaces. `onError` is an unobservable closure — presence only.
            """.trimIndent(),
        "Accessibility" to
            """
            Accessibility facet — carried best-effort for the render projection.
            """.trimIndent(),
        "Accessibility.speak" to
            """
            Phase 1812 — the node's SPOKEN rendering for a voice surface: an ordinary [TextSource]
            (bare-string canonical for a literal, as `tooltip`). Inert to every visual renderer: it MUST
            reach no attribute and no visible text.
            """.trimIndent(),
        "NodeKind" to
            """
            The node's primitive discriminator. The four behavioural categories
            (Layout / Display / Input / Visualisation) are a host-side classification recovered
            on decode ([NodeCategory]), not a level of wire nesting.
            """.trimIndent(),
        "Box.breakBefore" to
            """
            Phase 1473 — the print-break controls. Both omit at `false`, and both are declarations about
            PAGINATED media alone: a screen host reads them and emits nothing.
            """.trimIndent(),
        "Tabs.orientation" to
            """
            0.2.0 — omitted on the wire at the default (`Horizontal`).
            """.trimIndent(),
        "Modal.modality" to
            """
            WIRE_FORMAT.md 3.6.11 — omitted at [ModalityKind.Modal], which is the blocking modality every
            pre-modality document meant. A present value outside the two is `UNKNOWN_DU_CASE` and a
            non-string is `WRONG_TYPE`; neither falls back, because a document asking for a popover and
            getting a blocking modal has been answered with a different affordance.
            """.trimIndent(),
        "Modal.anchor" to
            """
            WIRE_FORMAT.md 3.6.11 — the **NodeId** the surface belongs to, meaningful for
            [ModalityKind.Popover] only. It is carried rather than acted on: nothing in 3.6.11 names a
            pixel, and where a popover is PLACED is the renderer's under the affordance→op rule. What the
            model owes is that the declaration survives decode, so a host that can measure its window can
            read it; a host that cannot still renders the surface in flow at the node's own document
            position (rule 7), which is the honest floor rather than a gap.

            A non-string is `WRONG_TYPE` at `.anchor`. Absence is `null` — an anchorless popover is a
            legal document, and this decoder does not invent a target for one.
            """.trimIndent(),
        "Switch" to
            """
            The declarative selector. The branch follows a **state key** or — since the 0.2.x widening —
            any [Binding], so a `Selection` makes the branch follow the clicked row. The schema requires
            at least one of the two (`anyOf`), which [decodeNode] enforces; both present is legitimate and
            `on` wins, being the more specific declaration.
            """.trimIndent(),
        "Switch.autoAdvanceMs" to
            """
            Phase 1122 — the timed carousel: advance to the next case every this-many milliseconds,
            omitted at absence. It declares the one fact a host cannot recover from the tree — every
            other half of a carousel was already composable, and nothing in any arrangement of those
            says a timer exists.

            A DURATION, never a flag. Non-positive and fractional values are REFUSED rather than
            canonicalised: `0` is what an emitter reaches for to mean "off" and the language already has
            a spelling for off, an absent key.
            """.trimIndent(),
        "Metric.value" to
            """
            0.2.0 rename law — a *scalar displayed value* is named `value` on the wire
            (`source` is reserved for collection feeds); the retired `Metric.source`
            spelling is a hard decode error in the reference core.
            """.trimIndent(),
        "Metric.format" to
            """
            0.2.x — the stylistic fields are omitted on the wire at their defaults.
            """.trimIndent(),
        "Metric.trendPolarity" to
            """
            3.6.1 — which way the quantity IMPROVES. TOTAL, not nullable, and decoded independently of
            [trend]: the wire's "absent means `HigherIsBetter`" is a DEFAULT rather than a third state,
            so modelling it as `null` would push the decision back out to every reader. A polarity
            declared with no [trend] is inert (legal per clause 4) and is kept rather than dropped —
            dropping it would silently rewrite the author's document because this surface judged the
            declaration pointless.
            """.trimIndent(),
        "Callout.dismissable" to
            """
            0.2.0 — omitted on the wire when `false`.
            """.trimIndent(),
        "Progress.indeterminate" to
            """
            0.2.0 — omitted on the wire when `false`.
            """.trimIndent(),
        "Icon" to
            """
            A named glyph from the host's icon set. `icon` is the name; the host owns the mapping.
            """.trimIndent(),
        "Icon.label" to
            """
            Absent ⇒ decorative: the a11y layer hides it. Present ⇒ a labelled `img` role.
            """.trimIndent(),
        "LabelValueRow.value" to
            """
            0.2.0 rename law — scalar displayed value ⇒ `value` (see [Metric.value]).
            """.trimIndent(),
        "LabelValueRow.emphasis" to
            """
            The behavioural bool (not the [Emphasis] style DU); 0.2.2 — omitted when `false`.
            """.trimIndent(),
        "Fact" to
            """
            The labeled TEXT fact — [Metric]'s complementary kind (0.2.x). `value` is a
            [TextSource] (the same vocabulary the labels use); `emphasis` is the behavioural
            bool (omit-when-false), `tone` omit-when-default; `help` / `icon` optional.
            """.trimIndent(),
        "SrcSetEntry" to
            """
            One alternate rendition of the SAME picture at a declared intrinsic pixel width
            (WIRE_FORMAT.md 3.6.4). Both members are required within the entry, and [width] is the `w`
            descriptor a client selects on — hence positive, enforced at decode rather than left to a
            renderer to discover, since a `0w` candidate is one no client can ever select.
            """.trimIndent(),
        "Image" to
            """
            WIRE_FORMAT.md 3.6.2–3.6.5. The three presentation slots are identity-defaulted (absent means
            [ImageFit.Natural] / [ImageAspect.Natural] / [ImageLoading.Eager]), so a document written before
            they existed decodes to today's behaviour.

            [srcSet] is the missing-list-field decode class: **absent MEANS the empty list**, never null. A
            present `"srcSet":null` is refused (`WRONG_TYPE`) because absence already has a spelling, and the
            authored ORDER is preserved — the wire is ordered data, and a surface that sorted it would be
            viewing a document its author did not write. Presentation order (ascending by width) is a
            renderer's business, not this projection's.

            [expandable] is the only slot here that declares an INTERACTION rather than a picture: the
            full-size asset is reachable from the rendered image. It carries no `Action`, reaches no
            closure-bearing position, and is `false` by default.
            """.trimIndent(),
        "Image.caption" to
            """
            CONTENT, not an identity default — a full [TextSource], so a caption is i18n-capable.
            """.trimIndent(),
        "Media" to
            """
            The playback surface (WIRE_FORMAT.md 3.6.6) — **ONE kind with two variants, never two kinds**.
            Everything a video and an audio surface share is stated once here; only what genuinely differs
            lives in [kind].

            [label] is REQUIRED and has no default: a media element is a TRANSPORT, never decorative, and
            there is no value to fall back to that would not be a fabricated name for someone else's
            recording. A host emits the resolved label as the element's accessible name, always.

            [controls] is omitted on the wire at **true** (the inverted polarity [Toast.dismissable] also
            takes): a transport a keyboard user cannot reach is the deviation, so it is what costs a key.
            """.trimIndent(),
        "Media.controls" to
            """
            Omitted on the wire when `true` — the accessible setting is what a document gets for free.
            """.trimIndent(),
        "Media.tracks" to
            """
            The element's timed-text tracks (3.6.6, Phase 1110), in the AUTHORED order the wire carries.

            Omitted on the wire when EMPTY — an absent list and an empty one denote the same document —
            so this restores `[]` and never a null. **Nothing sorts it**, and that is the opposite of
            [Image.srcSet]'s rule rather than an inconsistency with it: a browser picks ONE candidate
            from a srcset by an algorithm, so ordering it is canonicalisation, while a reader picks a
            track from a menu the user agent builds in DOCUMENT order, so ordering it would be rewriting
            someone else's menu.
            """.trimIndent(),
        "Media.transcript" to
            """
            The text alternative (3.6.6, Phase 1110). On the SPEC rather than on [Video], deliberately:
            a transcript is the affordance an AUDIO surface needs most, because a recording with no
            visual channel has nowhere else to put its words.

            An ordinary optional — absent means the document offers no transcript, which is a different
            statement from offering an empty one.
            """.trimIndent(),
        "TrackEntry" to
            """
            One timed-text track on a [Media] element (WIRE_FORMAT.md 3.6.6, Phase 1110).

            **Four of the five members are REQUIRED, which makes it the strictest record on the wire.**
            [default] is the one omitted-at-`false` slot.

            [srcLang] is required on EVERY kind, where HTML makes it mandatory only on a subtitles track.
            The extra strictness costs an author one value and buys a menu a user agent can order, a speech
            engine can pronounce and a reader can tell apart; a track with no language is one nothing
            downstream can route, and there is no value to default to that would not be an invented claim
            about someone else's recording.

            [label] is required for the same reason [Media.label] is: it is the entry a user agent puts in
            its track menu and the only thing distinguishing one track from another there.

            [src] is fetched by the browser with no user act, exactly as [Media.src] is, so it carries the
            §19 render-time obligation — see [fuaran.ui.sanitizedSrc] on this record. The remedy is the
            POSTER's rather than the source's: an element must have a source but it need not have this
            track, so a refused track is DROPPED. A `<track>` pointing at the refusal URL is a menu entry
            that opens onto nothing.
            """.trimIndent(),
        "TrackEntry.default" to
            """
            Whether the entry elects itself the default of its kind. Legal bytes elect two — the decoder
            does not refuse it, because a lenient host would render it anyway and HTML leaves the case
            undefined — so the RENDERER resolves it, and every host resolves it the same way: the FIRST
            election of a kind is honoured and a later one loses only its claim on the menu. The
            election is per kind, so a captions default and a subtitles default coexist.
            """.trimIndent(),
        "MediaKind" to
            """
            Which playback surface a [Media] node is. `${'$'}type`-discriminated at `kind.kind`, so an unknown
            case reports at `…kind.kind.${'$'}type` rather than at the bare slot (WIRE_FORMAT.md 6).

            The set is CLOSED at [Video] | [Audio]: a third surface is an admission to the vocabulary, not a
            spelling a decoder may guess at.
            """.trimIndent(),
        "Video" to
            """
            The video surface. [autoplay] is a declaration whose rendering is constrained: a host that
            honours it MUST emit it together with a muted attribute, and MUST NOT mute where it is absent.
            There is deliberately no `muted` slot — it would be a second knob free to disagree with the
            first, and the only combination it would add is the one no host may render.
            """.trimIndent(),
        "Audio" to
            """
            The audio surface, whose payload is the discriminator alone.

            **There is NO autoplay pathway here — in the type, on the wire, or in a render arm.** That is
            stronger than a default of `false`: a slot defaulting to off is one a document can switch on, and
            there is no document this format wants to be able to state in which a page begins making sound
            unbidden. `{"${'$'}type":"Audio","autoplay":true}` decodes to an audio surface that does not autoplay,
            because the value has nowhere to land — an unknown key, tolerated by rule 2 like any other.
            """.trimIndent(),
        "Embed" to
            """
            The sandboxed third-party embed (WIRE_FORMAT.md 3.6.8, Phase 1111) — a Display kind carrying a
            document URL, a mandatory accessible title, an optional declared aspect ratio, and a closed list
            of sandbox relaxations that is EMPTY by default.

            **A KIND, not a [Mount] variant and not a [Media] one.** `Mount` composes a COOPERATING guest —
            a scope id, a declared message channel, a capability request list, a host-side loader that
            produced the guest tree — and a third-party page has none of those and cannot acquire them;
            widening `Mount` to admit an uncooperative third party would weaken every guarantee it makes.
            `Media` fetches an asset and DISPLAYS it, decoded by the user agent's own codec into no scripting
            context, where an embed fetches a document and lets it EXECUTE. That difference is why [src]
            takes its own, stricter egress class (§19.1 — `https` and nothing else) rather than reusing the
            §19 accept set: see [fuaran.ui.sanitizedSrc] on this record.

            [title] is REQUIRED, on [Media.label]'s argument one kind over: a frame is a focus container a
            reader tabs INTO, so there is no decorative embed, and a frame with no accessible name is
            announced as "frame" and nothing else.

            [aspectRatio] REUSES [ImageAspect]. The cases are pure layout ratios with nothing image-specific
            in them and the wire carries bare strings, so the type name reaches no document; minting a
            parallel enum with identical cases would create two closed sets that must be kept in step.
            """.trimIndent(),
        "Embed.permissions" to
            """
            Omitted on the wire at the EMPTY list, and empty means TOTAL DENIAL — see [EmbedPermission].
            """.trimIndent(),
        "Tree" to
            """
            Recursive disclosure with tree semantics (WIRE_FORMAT.md 3.6.12, Phase 1120) — a hierarchy of
            ROWS and, optionally, the names of the two State slots a reader opens rows and selects one
            through.

            Its rows are [TreeItem] records rather than [Node]s, and [TreeItem.children] is a list of the
            same record, which makes this the format's first **self-referential** shape. Item nesting is
            therefore bounded on its OWN axis (§21.5): the whole hierarchy lives inside one node and consumes
            no node depth at all.

            **This kind carries no `expandable` and no `selectable` boolean, and none is coming.** A
            behaviour the reader drives is declared as a named State key the host both writes and reads; a
            flag with no key behind it is a decorative control writing state nothing reads.

            The two slot shapes are fixed by the specification, because a host reading them must not guess:
            [expandedStateKey] names a JSON **array of row ids** (the rows currently open) and
            [selectionStateKey] a bare **row-id string**. A value of any other shape reads as *empty* /
            *none* rather than as an error — this is a host's own state slot, not a wire document, so there
            is nothing here to refuse, and refusing would blank a tree over a value the reader never
            authored.

            **A tree naming no [expandedStateKey] renders FULLY EXPANDED and does not toggle**, which is the
            only reading under which such a tree shows its content at all.

            `onSelect` is a closure and is DROPPED here, as every other handler slot in this projection is
            ([Select] carries no `onChange`, [Disclosure] no `onToggle`).
            """.trimIndent(),
        "TreeItem" to
            """
            One row of a [Tree] (3.6.12).

            [id] and [label] are required; [children] omits at the EMPTY LIST and [icon] when absent — so a
            leaf carries two keys and nothing else, which is most of a real hierarchy.

            [id] is required because it is what the two State slots NAME: an id-less row can never be
            expanded, selected or restored, and a synthesised positional id would move the reader's open
            branches the moment a sibling was inserted. [label] is a [TextSource] because it is content —
            authored, translated, bindable.

            **Row ids MUST be unique within one tree, and that is an EMIT-side obligation rather than a
            decode refusal** (§8.1's position for `NodeId`, transferred for §8.1's own reason): duplicate
            detection is a whole-tree property, a decoder streaming a document is not required to carry the
            id set, and there is no error code for it. A decoder that accepts a duplicate is still
            conformant, and this one does.
            """.trimIndent(),
        "Toast.dismissable" to
            """
            0.2.0 — omitted on the wire when `true` (the one inverted default).
            """.trimIndent(),
        "CompareRule" to
            """
            The cross-field operand. `against` is a `Binding`, and that IS the cross-field mechanism
            rather than an accident of typing: any read slot may take a Binding, and the auto-bind rule
            already puts every form field's value in State under the field's own id, so
            `{"${'$'}type":"State","key":"<sibling id>"}` reads the sibling with no coordination vocabulary.
            """.trimIndent(),
        "FieldRule" to
            """
            A field's declared constraint — the ACCEPTED SET, where `FormFieldKind` names the control.
            Every slot is optional structurally; the two well-formedness refusals (a rule that constrains
            nothing; `minLength` above `maxLength`) are relations BETWEEN slots and so live in the
            decoder's policy layer rather than in this shape.
            """.trimIndent(),
        "FileUpload.dropTarget" to
            """
            Phase 1115 — two ADDITIONAL ingress routes, never replacements for the picker. Both omit at
            `false`, and the polarity is load-bearing: the shortest upload document is the plain picker,
            which is what every document written before that revision says.
            """.trimIndent(),
        "FileUpload.capture" to
            """
            Phase 1116 (3.6.18) — WHICH of the reader's own recording devices the platform should open in
            place of the file browser. OPTIONAL rather than omit-at-default: "say nothing" is a state of
            its own, because an upload naming no device is asking for the file browser, which is not one
            of the two devices wearing a default.
            """.trimIndent(),
        "FileUpload.destination" to
            """
            Phase 1117 (3.6.20) — the host-registered destination an upload streams to. A NAME, and a
            name because it must never be an ADDRESS: a wire document comes from an arbitrary emitter,
            and a URL here would let that emitter choose where a reader's file goes. The empty string is
            REFUSED rather than read as absence, which would silently turn an upload the author meant to
            stream into a client-only one.
            """.trimIndent(),
        "FileUpload.maxBytes" to
            """
            Phase 1548 (3.6.23) — the declared ceilings: how large a file, and how many of them, this
            control accepts.

            [maxBytes] is PER FILE rather than per selection. That is what makes it meaningful on a
            single-file upload and what makes it the quantity a body read can be measured against; a
            control bounding a whole multiple selection states both members and the total it declares is
            `maxBytes x maxFiles`. A per-selection sum would be a different quantity wearing the same
            name.

            [maxFiles] is meaningful only alongside `multiple`, and beside `"multiple":false` it is INERT
            — carried, not refused, because the bytes describe a control every host renders identically
            with or without it, so there is nothing for a decoder to be right about.

            Both are OPTIONAL and POSITIVE. Absent declares no ceiling, which is every pre-1548 document
            exactly; `0` and below are refused at decode, because a ceiling of zero is not a small ceiling
            but a control that can accept nothing. `Int` here is 7.1's signed 32-bit slot, bounded by the
            strict integer reader rather than by this type.
            """.trimIndent(),
        "FilterItem" to
            """
            0.2.0 filters-unification: a filter chip's control is an ordinary [FormFieldKind] —
            the parallel `FilterKind` DU (`TextFilter` / `ChoiceFilter` / `RangeFilter` /
            `SegmentedFilter`) is retired; its discriminators are a hard `UNKNOWN_DU_CASE`.
            A chip control with no `value` key decodes to the auto binding `Filter(<name>)`.
            """.trimIndent(),
        "DataGrid" to
            """
            The row-feed grid.

            The sort / page / edit positions are addressed through STATE keys rather than literal values —
            `sortStateKey`, `pageStateKey`, `editStateKey` — so a control can move them; `defaultSort` and
            `pageSize` are the initial configuration. The projection decodes all six faithfully (it is a
            view of the wire, so dropping a declaration would misreport the tree); acting on them is the
            renderer's business, not the decoder's.
            """.trimIndent(),
        "DataGrid.editable" to
            """
            0.2.0 — omitted on the wire when `false`.
            """.trimIndent(),
        "DataGrid.pageSize" to
            """
            Rows per page. The schema pins `minimum: 1` — a zero page size paginates nothing.
            """.trimIndent(),
        "DataGrid.keepRowsTogether" to
            """
            Phase 1473 — the paginated-media pair, on [Box]'s terms.
            """.trimIndent(),
        "DataGrid.exportable" to
            """
            Phase 1123 — this grid's rows may be taken out of the page as a file. Omitted at `false`.
            """.trimIndent(),
        "DataGrid.transferInKey" to
            """
            Phase 1125 — the two sides of ONE shared State key. A grid declaring [transferOutKey] K may
            RELEASE rows onto K; one declaring [transferInKey] K ACCEPTS rows arriving on it. Separate
            decoder arms, and the corpus vectors them separately for that reason.
            """.trimIndent(),
        "DataGrid.rowActionDeclared" to
            """
            Phase 1855 — WIRE_FORMAT.md 3.6.24. `onRowClick` is a closure-bearing slot, so it rides the
            wire as the `"<closure>"` sentinel and carries exactly ONE readable fact: this grid declares
            a row action. That fact — and only that fact — is what decides whether a rendered row is
            marked interactive, so it is carried as a presence flag rather than dropped. Presence is the
            reference decoder's test (any value declares), and the closure itself never crosses.
            """.trimIndent(),
        "GridColumn.format" to
            """
            0.2.x — `format` / `width` omitted on the wire at their defaults.
            """.trimIndent(),
        "GridColumn.field" to
            """
            The declarative row-field projection; `value` (a closure) is presence-only and dropped.
            """.trimIndent(),
        "DefaultSort" to
            """
            An initial sort: a zero-based header index (the schema pins `minimum: 0`) plus a direction.
            """.trimIndent(),
        "ChartAnnotationX" to
            """
            Phase 1491 (4l) — an annotation's x address: a CATEGORY key read under the categorical scale, and
            an ISO-8601 `Date` read under `Temporal`. Its own type rather than two inline fields, because a
            range band addresses an x-axis interval with a PAIR of these.
            """.trimIndent(),
        "ChartAnnotationRange" to
            """
            Phase 1492 (4l) — a range band's PAIR: two of the same address form, on one axis.

            THE AXIS IS THE CASE. 4l requires a band to declare which axis it sits on; carrying that as a
            separate flag beside an untyped pair would admit a document declaring the value axis and
            addressing it with two category keys. The union tag declares the axis AND types the pair with it,
            so that document cannot be written by any conformant emitter.
            """.trimIndent(),
        "ChartAnnotation" to
            """
            Phase 1490 (4l) — a chart's data-addressed annotation. An annotation names a place in the DATA's
            coordinates and, optionally, a label; it carries no geometry and no style at all, which is what
            makes it survive a data change, a theme flip, a restyle and a resize.
            """.trimIndent(),
        "ReferenceLine" to
            """
            A horizontal line at [value] in the VALUE axis's own units.
            """.trimIndent(),
        "EventMarker" to
            """
            A VERTICAL line at an x address — the reference line mirrored across the axes.
            """.trimIndent(),
        "RangeBand" to
            """
            A shaded interval on either axis, and the one member that draws BEHIND every series.
            """.trimIndent(),
        "Chart.stacked" to
            """
            Round-trips when present; absent (the legacy wire) defaults to `false`.
            """.trimIndent(),
        "Chart.annotations" to
            """
            Phase 1490 — omitted when the chart declares none.
            """.trimIndent(),
        "MasonryLayout" to
            """
            WIRE_FORMAT 3.6.7 (Phase 1082) — the column-fill mode. `cols` is REQUIRED and POSITIVE; there is
            deliberately no `templateColumns` twin, because the multi-column model realising masonry has no
            track list for one to name.
            """.trimIndent(),
        "Binding" to
            """
            A reactive value source. Closure-bearing accessors (`Computed.fn`, `Query.accessor`)
            do not survive the wire — the render projection keeps the declarative skeleton (names,
            dependency edges) and drops the closure.
            """.trimIndent(),
        "StaticBinding" to
            """
            A literal payload. `value` is the raw JSON — a typed slot or the host schema decomposes it.
            """.trimIndent(),
        "FilterBinding" to
            """
            0.2.0 — optional `defaultValue`: yielded (raw) before the filter is first written.
            """.trimIndent(),
        "SelectionBinding" to
            """
            A row-selection source over the grid at [nodeId]. 0.2.9 — optional `defaultValue`
            (the `Filter.defaultValue` convention); 0.2.10 — optional `field` (the declarative
            row-field projection: present ⇒ the accessor projects that field off the clicked row).
            """.trimIndent(),
        "NowBinding" to
            """
            The host-furnished instant. Phase 1533 — the declared [grain] truncates the instant BEFORE it is
            read. Absent is `Second`, which is the identity; a present value outside the four is refused
            rather than silently read at a neighbouring resolution.
            """.trimIndent(),
        "LocalBinding" to
            """
            The controlled input's edit buffer (3.3.3). [codec] declares the buffer's OWN codec and replaces
            the identity on both sides; [commitTo] declares the State key the flush writes to.

            [commitTo] and the `onCommit` closure are mutually exclusive, because the wire cannot carry the
            closure and two hosts would otherwise write to different places from identical bytes.
            """.trimIndent(),
        "TransformBinding" to
            """
            A declarative dataframe transform. `source` (columnar table) and `pipeline` (ordered
            steps) are owned by the `Fuaran.Core` codec, so the render projection holds them as
            raw [JsonValue] — the sanctioned "don't decompose content the host doesn't own"
            posture (WIRE_FORMAT.md 3.3). `params` binds pipeline params to scalar sources.
            """.trimIndent(),
        "ExprBinding" to
            """
            Phase 1534 (3.3.2) — ONE scalar expression evaluated to ONE value over the SAME `ColExpr` algebra
            a [TransformBinding] pipeline step carries. The case introduces no operator and no expression
            language of its own, so an expression means here exactly what it means inside a `derive`.

            [expr] is held as raw [JsonValue] on this surface for the reason [TransformBinding]'s pipeline is:
            the algebra is owned by the `Fuaran.Core` codec, and a render projection does not decompose
            content the host does not own.

            Two DECODE refusals nonetheless apply, both because an expression HAS NO ROW: a `col` reference
            anywhere in it, and a `param` the binding's own list does not bind. Left admitted, each would
            decode to an expression whose evaluation could only ever fail, once per render, on every host.
            """.trimIndent(),
        "Action" to
            """
            A wire-survivable action. Closure payloads (`Dispatch.msg`) collapse to a placeholder.
            """.trimIndent(),
        "NavigateAction" to
            """
            Phase 1536 — [route] is a [TextSource], not a bare string, so a tree can name a destination it
            computes from what the reader is looking at. The bare JSON string IS `Literal`'s canonical form,
            so every document written before the widening decodes exactly as it did.

            [target] is the CLOSED `Self | Blank` enum, omitted at `Self`, and deliberately not HTML's
            `target` attribute: that vocabulary also carries `_parent` and `_top`, which are frame-busting
            gestures a hosted tree must not be able to ask for.
            """.trimIndent(),
        "SetStateAction" to
            """
            Write a state slot. Exactly ONE of [value] (a literal payload) and [valueFrom] (a binding
            resolved at dispatch time) is present — the schema states it as a `oneOf`, so both-present is
            a reject rather than a precedence question.
            """.trimIndent(),
        "WriteToClipboardAction" to
            """
            Phase 1126 — the payload is a [TextSource]. A `text` that is neither a string nor a
            `${'$'}type`-tagged text source is `WRONG_TYPE` and is never coerced: a host reading the widening as
            "this member is now open" would put a JSON literal on the reader's clipboard, and a clipboard is
            a channel the reader later pastes somewhere with authority.
            """.trimIndent(),
        "PrintAction" to
            """
            Phase 1124 — the payload-free print, and the ONE action case strict about unrecognised members:
            page range, size, margins and copies are the host's page setup and the reader's dialogue, so
            accepting a member here would leave the emitter believing it had constrained a printing it had
            not, with no error anywhere saying otherwise.
            """.trimIndent(),
        "ConfirmAction" to
            """
            Phase 1537 — ask, then act. Confirmation is bounded at ONE question: a confirm reachable from
            either continuation is refused, and the check walks the DECODED continuation so a chain cannot
            hide the nesting. An absent [onCancel] means "nothing happens", which an absent action already
            expresses.
            """.trimIndent(),
        "FocusAction" to
            """
            Phase 1537 — a bare node id, the [CommitLocalAction] shape. It addresses a node in THIS document,
            so there is nothing for a binding to compute and no [TextSource] here.
            """.trimIndent(),
        "ValueFormat" to
            """
            The display format on a Metric / LabelValueRow / grid column (`code`-carrying currency).
            """.trimIndent(),
        "DateTimeValueFormat" to
            """
            Phase 1811 — `CellFormat.DateTime` (was `Date`): the pattern renders a date, a time or both.
            """.trimIndent(),
        "DurationValueFormat" to
            """
            An elapsed-time format: the unit the raw value counts in, plus the rendering style.
            """.trimIndent(),
        "RelativeTimeValueFormat" to
            """
            A relative-time format ("3 minutes ago"). Distinct from [RelativeTimeNumberFormat], which is the
            same vocabulary in `Binding.Format`'s DU - the wire carries two format DUs and a case name in
            one is not a case in the other.
            """.trimIndent(),
        "CustomValueFormat" to
            """
            The formatter is a closure — presence only.
            """.trimIndent(),
        "NumberFormat" to
            """
            The `Binding.Format` numeric format DU (`isoCode`-carrying currency; adds Date / RelativeTime).
            """.trimIndent(),
        "DateTimeNumberFormat" to
            """
            `Format.DateTime` (was `Date` until Phase 1811). Phase 1810 — `dateStyle` / `timeStyle` are BOTH
            optional: `timeStyle` alone is a time of day, both together a date-time. Neither present is a
            validator concern on the reference host, not a decode refusal.
            """.trimIndent(),
        "SinceNumberFormat" to
            """
            Phase 1533 — the elapsed-time rendition. Distinct from [RelativeTimeNumberFormat]: this one is a
            function of the HOST instant as well as of its source, so a host with no instant renders nothing
            rather than an invented delta. [unit] is OPTIONAL and its absence is the auto-selection request,
            not a default.
            """.trimIndent(),
        "DurationNumberFormat" to
            """
            Phase 819 — the numeric source counts [unit]s, rendered per the bounded [style].
            """.trimIndent(),
        "ToggleField" to
            """
            The switch affordance: the same boolean slot as [CheckboxField], a different control.
            """.trimIndent(),
        "ComboboxField" to
            """
            The typeahead / autocomplete field (WIRE_FORMAT.md 3.6.9, Phase 1113) — the searchable form of
            [ChoiceField].

            **The line an emitter and a host both have to hold is one sentence:** a BOUNDED KNOWN set the
            reader scans is a `Choice`; a LARGE, SEARCHABLE or ASYNCHRONOUS set — or one that admits a value
            not on the list — is a `Combobox`. The failure this distinction prevents is not an invalid
            document but a valid one: a `Choice` over two hundred options parses, validates and renders
            everywhere, and is unusable.

            [options] and [value] are `Choice`'s slots **deliberately and normatively**: the constrained
            combobox IS a searchable select, so a document migrating between the two changes its `${'$'}type` and
            nothing else, and a host implementing a different value contract here would break exactly that
            migration. An asynchronous suggestion source needs no vocabulary of its own — a `Query` binding
            in the ordinary [options] slot IS the async feed, with `dependsOn` giving it the dependency edge.

            [allowFreeText] omits at `false`, and the polarity is load-bearing: the SHORTEST combobox
            document is the CONSTRAINED one, so an emitter that says nothing gets the shape a select would
            have had, and admitting off-list values is the thing it has to ask for. A present member of any
            type other than boolean is `WRONG_TYPE` and is never coerced — a lenient truthiness read would
            widen the field on `"no"` and `"false"` alike.

            **`allowFreeText = false` is not enforceable by any static host and must not be claimed as if it
            were.** Per §22, client validation is not a trust boundary: a host that accepts submissions
            re-checks membership server-side, exactly as it re-checks every other declared constraint.
            """.trimIndent(),
        "SegmentedChoiceField.orientation" to
            """
            Decode-optional (0.2.0) — absent restores the language default `Horizontal`.
            """.trimIndent(),
        "RangeField" to
            """
            0.2.0 — the dual-thumb numeric range control (absorbed the retired
            `FilterKind.RangeFilter`). A `Static` pair rides as the bare `{"max":…,"min":…}`
            object (no `${'$'}type`); optional bounds omitted when absent.
            """.trimIndent(),
        "DateTimeField" to
            """
            Phase 1811 — `DateTime` (was `Date`): a date, a time of day or both, per [variant].
            """.trimIndent(),
        "DateTimeRangeField" to
            """
            0.7.0 — the single-control date range: `RangeField`'s pair mechanics with
            `DateField`'s value conventions (an identical field list to `DateField`,
            reusing `DateFieldVariant`). A `Static` pair rides as the bare
            `{"from":…,"to":…}` object (no `${'$'}type`); `min`/`max` (ISO strings) and `step`
            (seconds) bound BOTH ends and are omitted when absent. In a filter context the
            pair binds ONE filter param, not two — the reason the case exists rather than
            two coordinated `DateField`s.

            Phase 1811 — `DateTimeRange` (was `DateRange`): the single-control range over [DateTimeField]'s conventions.
            """.trimIndent(),
        "TokensField" to
            """
            Phase 1121 (3.6.19) — SEVERAL values accumulated as removable chips, over a suggestion set that
            may be open, searchable, asynchronous, or absent entirely.

            Every member is optional, so `{"${'$'}type":"Tokens"}` is a complete document. [allowFreeText] omits
            at **`true`** — the OPPOSITE polarity to [ComboboxField], and the one thing about this case a
            host is most likely to get wrong: a combobox's option source is REQUIRED so "constrained" is its
            resting state, where [suggestions] is optional so "open" is this one's. The default follows the
            required-ness of the set.

            [value] is a `Binding<string list>` and the list is ORDERED — chips appear where the reader added
            them, so a host must not sort or de-duplicate it.
            """.trimIndent(),
        "RatingField" to
            """
            Phase 1130 (3.6.17) — a subjective score on a small ordinal scale. The line against
            [RangedNumberField] is who the number belongs to: a rating is a judgement a person GIVES, a
            ranged number a measurement they REPORT.

            [max] is the case's only required member and is refused below 1 — a scale with no positions has
            nothing to draw and no keystroke that could change anything. [value] is a float even where
            nothing can type a fraction, because the commonest rating a reader sees is an AVERAGE arriving
            through a query. [allowHalf] governs ENTRY, never display.
            """.trimIndent(),
        "ColorField" to
            """
            Phase 1130 (3.6.17) — the platform's own colour picker. A CONTROL, not a `rule.format`: a swatch
            that opens the operating system's picker, which no format on a text field can produce.

            Both members optional. The value is `#rrggbb` and nothing else — the one form a native colour
            input can hold or return — and case is PRESERVED rather than normalised.
            """.trimIndent(),
        "EditableCell" to
            """
            `onEdit` closure — always emitted; presence only.
            """.trimIndent(),
        "CheckboxCell" to
            """
            `get` + `onToggle` closures — always emitted; presence only.
            """.trimIndent(),
        "ButtonCell" to
            """
            The `onClick` closure is always emitted; only the label survives the wire.
            """.trimIndent(),
        "TonedPillCell" to
            """
            A value-conditional pill (Phase 750) — the declarative twin of [PillCell], and the ONE
            cell kind holding no closure, which is exactly why it survives the wire.

            [field] names the row property that is both the pill's label and the map key; [map]
            carries value → tone; [defaultTone] covers a value the map does not mention and is
            omitted on the wire at [ToneVariant.Default].

            It is the first cell kind this projection carries a PAYLOAD for. Every other one is
            defined by a closure, which never rides the wire, so the case name was the whole of the
            information — a `data object` said everything there was to say. A declared tone rule is
            data, so it has to be carried.
            """.trimIndent(),
        "DrawStyle" to
            """
            A drawing style. The four [Binding] paint fields cover shape styling; label glyphs
            carry additional text fields, held as optional raw tokens (render-only).
            """.trimIndent(),
        "DrawStyle.markId" to
            """
            Phase 642 — keyed mark identity: a data-bearing shape's derivation-based id
            (`series-field|category-key`), stable under row reorder and data refresh
            (object constancy). Omitted when absent; chrome shapes stay unstamped.
            """.trimIndent(),
        "ImageFit" to
            """
            `Image.fit` — how the decoded pixels fill the box the layout gives the element
            (WIRE_FORMAT.md 3.6.2). Absent means [Natural].
            """.trimIndent(),
        "ImageAspect" to
            """
            `Image.aspectRatio` — the box the element reserves BEFORE the image arrives (3.6.2). Absent
            means [Natural].

            These are TOKENS, never CSS values: the slot names one of four ratios and never carries a number,
            a pair, or a stylesheet spelling (`"16 / 9"`, `"16:9"`, `1.7778`). Admitting an arbitrary ratio
            would put an author-supplied value in a style attribute, which is the free-form escape this format
            does not have — so `"16/9"` is `UNKNOWN_DU_CASE`, not a second spelling of [SixteenNine].

            It is a LAYOUT reservation, not a crop: what happens to pixels that do not match the box is
            [ImageFit]'s statement, and a host derives neither from the other.
            """.trimIndent(),
        "ImageLoading" to
            """
            `Image.loading` — whether the client fetches during initial load or defers (3.6.2). Absent means
            [Eager], and that default is deliberate rather than the "unoptimised" value: deferring an
            above-the-fold image delays the largest contentful paint, and only the author knows where the
            image sits. A host MUST NOT infer laziness from position, viewport, or anything else the tree
            does not say.
            """.trimIndent(),
        "TrackKind" to
            """
            A `TrackEntry.kind` — which timed-text channel a media track carries (WIRE_FORMAT.md 3.6.6).
            A BARE enum (3.5), so an unrecognised token reports at the entry's own `kind` path with no
            `${'$'}type` suffix.

            **`Metadata` is absent by design and is not a spelling to guess at.** Its cues are rendered by
            no user agent and read only by script, so a declarative document naming it would state an intent
            no conformant host could honour without leaving the vocabulary. The set is closed at four; a
            fifth is an addition to the format, which is exactly what an `UNKNOWN_DU_CASE` here says.
            """.trimIndent(),
        "EmbedPermission" to
            """
            An `Embed.permissions` element — one sandbox relaxation a document asks for (3.6.8). A BARE enum,
            so an unrecognised token reports at `${'$'}.kind.permissions[i]` with no `${'$'}type` suffix.

            **The empty list is TOTAL DENIAL, and that polarity is the design**: the wire-cheapest document
            is the most locked-down one, so the default a careless emitter produces is the safe one.

            **Two relaxations are excluded rather than defaulted off, and are not reserved either** —
            top-level navigation (the drive-by redirect) and downloads (a file-save prompt in a third
            party's hands). Both are names a later phase must NOT take. Popups, modals, pointer lock,
            presentation and orientation lock have no recorded demand and ARE reserved, which is the whole
            reason the slot is an enum rather than a record of booleans: a fifth case is a bare-string
            addition rather than a type replacement.

            A decoder MUST NOT silently drop an unrecognised token: that would turn a document asking for
            something this vocabulary has no name for into a document asking for LESS, which reads as
            success. [enumOf]'s refusal is what keeps that honest.
            """.trimIndent(),
        "TimeStyle" to
            """
            Phase 1810 — the time-of-day half of `Format.DateTime`'s `dateStyle` / `timeStyle` pair. The
            same four cases as [DateStyle], deliberately a SEPARATE enum: the two name breadths of different
            things.
            """.trimIndent(),
        "DateTimeFieldVariant" to
            """
            Phase 1811 — `DateTimeVariant` on the wire (was `DateVariant`): the temporal breadth of a
            [DateTimeField] / [DateTimeRangeField]. The three cases did not move.
            """.trimIndent(),
        "TrendPolarity" to
            """
            `Metric.trendPolarity` — which way the measured quantity IMPROVES (WIRE_FORMAT.md 3.6.1).
            Distinct from `tone`, which says how the reading STANDS: a host derives neither from the other,
            and nothing ever writes back to `tone`.

            **`Neutral` is reserved by the specification and is deliberately NOT a case here.** The case set
            IS the accepted wire set, so omitting it means `"Neutral"` fails `UNKNOWN_DU_CASE` naming the two
            legal spellings, rather than this surface quietly deciding a question the reservation holds open
            — and no `when` carries a dead arm waiting for a case the wire will not produce. That is the same
            modelling choice the Rust reference core made for the same slot, and it is why the wire slot is
            an enum rather than an `inverted` bool: admitting the third case later is one added constant plus
            an exhaustiveness ERROR at every site that must then decide what it means.

            No alias arm is registered, and that omission is deliberate: the obvious candidates are precisely
            the spellings that must not be accepted — `Neutral` would pre-empt the reservation, and
            `Inverted` / `Descending` would reinstate the boolean spelling 3.6.1 refuses.
            """.trimIndent(),
        "TimeGrain" to
            """
            Phase 1533 — the resolution a `NowBinding` declares for the host-furnished instant. FOUR members
            and not `RelativeTimeUnit`'s seven, deliberately: this is a TRUNCATION of a calendar instant, and
            a week, a month or a year has no truncation every host agrees on (which weekday starts a week;
            which calendar). The four here truncate the canonical ISO-8601 form by prefix.
            """.trimIndent(),
        "ModalityKind" to
            """
            WIRE_FORMAT.md 3.6.11 — the modality a `Modal` declares. Omitted at `Modal`, which is the
            blocking modality every pre-modality document meant.
            """.trimIndent(),
        "TextDirection" to
            """
            Phase 1472 — the node-level declared text direction (3.1). Lower-case on the wire, like
            `LiveRegionKind` and `SortDirection`, because these are the HTML `dir` tokens themselves. Omitted
            at `auto`, which leaves the inherited direction in place — the pre-1472 rendering.
            """.trimIndent(),
        "NavigateTarget" to
            """
            Phase 1536 — `NavigateAction`'s destination window (3.6.21). A CLOSED enum of two, deliberately
            not HTML's `target` attribute: that vocabulary also carries `_parent` and `_top`, which are
            frame-busting gestures a hosted tree must not be able to ask for, so `_blank` is refused rather
            than aliased — accepting any of it would teach an emitter the wrong vocabulary.
            """.trimIndent(),
        "CaptureSource" to
            """
            Phase 1116 — the recording device a `FileUpload` asks the platform for (3.6.18). There is
            deliberately no display-capture case and there will not be one by widening this member: a screen
            capture reaches every window the reader has open rather than one device behind the picker.
            """.trimIndent(),
        "SortDirection" to
            """
            `staticRows.defaultSort.direction` / a grid's `defaultSort.direction` — a closed pair.
            """.trimIndent(),
        "LinkProtection" to
            """
            `Link.protection` — the obfuscation the host applies to a rendered `mailto:` href.
            """.trimIndent(),
        "TextFormat" to
            """
            `FieldRule.format` — the closed named-format set a text field's value is held to.
            """.trimIndent(),
        "CompareOp" to
            """
            `CompareRule.op` — the cross-field comparison operator.
            """.trimIndent(),
        "NodeCategory" to
            """
            The four behavioural categories the wire keeps flat but a host recovers on decode
            (WIRE_FORMAT.md 3.2).
            """.trimIndent(),
        "category" to
            """
            The render projection's load-bearing dispatch spine: an **exhaustive** `when` over the
            sealed [NodeKind] hierarchy with **no `else` arm**. Adding a new node kind without
            classifying it here is a compile error — the same "a new kind is a build error"
            guarantee the native Rust host gets from `match`. The Compose renderer (a later phase)
            extends this same spine; it must stay `else`-free.
            """.trimIndent(),
        "discriminator" to
            """
            The wire discriminator name for a decoded [NodeKind] — used by the corpus coverage report.
            """.trimIndent(),
    )
