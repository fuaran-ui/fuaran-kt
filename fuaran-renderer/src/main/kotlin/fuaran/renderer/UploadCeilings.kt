// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import fuaran.ui.FileUpload

/**
 * The upload-ceiling projection — `FileUpload.maxBytes` / `.maxFiles` (WIRE_FORMAT.md 3.6.23,
 * Phase 1548) read into what this surface can honestly do with them.
 *
 * ## What this floor can and cannot answer
 *
 * 3.6.23 states five render obligations. Four of the five turn on a SELECTION, and this floor meets
 * none: the `FileUpload` arm renders a labelled, enablement-aware button and opens no file picker
 * at all, so there is no pick to refuse (obligation 1), no refusal to report (obligation 2), and no
 * `file-read` event to gate (obligation 3, which is a server-driven host's in any case). The fifth
 * — that a declared ceiling never relaxes a host's own bound — is satisfied vacuously, because
 * nothing on this path admits a file to bound.
 *
 * That leaves obligation 4, whose normative sentence this projection follows literally: a tier that
 * cannot ACT on a ceiling records only THAT one was declared, never its value, "because nothing on
 * that path can act on the number and publishing it would invite a reader to believe otherwise".
 * The obligation is written for the static no-script tier and its `data-` attribute markers, and
 * this surface emits no attribute bag and no document — which is why
 * `FileUpload/ceiling-recorded-never-enforced` is a DECLARED EXEMPTION in
 * `RenderObligationHarness.kt` rather than an asserted claim. The REASONING behind that sentence is
 * not attribute-shaped, though, and it applies here unchanged: a native control captioned
 * `max 5 MB` beside a button that enforces nothing would make the same false promise a valued
 * marker would.
 *
 * So the plan below is deliberately VALUE-FREE, and the fields are booleans rather than the numbers
 * precisely so that no arm downstream can render a ceiling as though it held.
 *
 * **The values are still DECODED and still carried** — `FileUpload` holds both members, they
 * round-trip, and an embedding app that adds a real picker reads them off the node. What is
 * withheld is the RENDER, not the data.
 *
 * **Why this file carries no Compose import.** The same reasoning as [TrendSentiment] and
 * [AccessibilityProjection] beside it: the decision — record the declaration, withhold the number —
 * is ordinary logic over the decoded model, and typed in Compose vocabulary it would be provable
 * only on a box carrying the Android SDK. A decision testable on one platform is a decision nobody
 * re-checks.
 */
data class UploadCeilingMarkers(
    /** A `maxBytes` was declared on the control. */
    val byteCeilingDeclared: Boolean,
    /** A `maxFiles` was declared on the control. */
    val fileCountCeilingDeclared: Boolean,
    /**
     * Whether this surface claims to ENFORCE either ceiling.
     *
     * **Always `false`, and it is a field rather than an omission.** A reader of the plan can see
     * that the claim was considered and declined; a missing field would leave them unable to tell a
     * decision from an oversight. It becomes answerable the day this floor grows a picker, and this
     * is where that answer goes.
     */
    val enforced: Boolean,
) {
    /**
     * Neither ceiling was declared. An UNDECLARED ceiling earns no marker at all — 3.6.23
     * obligation 4's second half — so this is the case in which a conformant surface renders
     * nothing extra and an upload written before the revision looks exactly as it did.
     */
    val isEmpty: Boolean
        get() = !byteCeilingDeclared && !fileCountCeilingDeclared

    /**
     * The marker line, or `null` where there is nothing to record.
     *
     * Carries no number, by construction: it is built from the booleans above, so there is no path
     * by which a value could reach it.
     */
    val summary: String?
        get() {
            val parts = buildList {
                if (byteCeilingDeclared) add("per-file byte ceiling declared")
                if (fileCountCeilingDeclared) add("file-count ceiling declared")
            }
            return if (parts.isEmpty()) null else parts.joinToString(" · ")
        }
}

/** Project one upload's declared ceilings into the markers this floor may show. */
fun uploadCeilingMarkers(k: FileUpload): UploadCeilingMarkers =
    UploadCeilingMarkers(
        byteCeilingDeclared = k.maxBytes != null,
        fileCountCeilingDeclared = k.maxFiles != null,
        enforced = false,
    )
