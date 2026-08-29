// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import fuaran.ui.Json
import fuaran.ui.JsonArray
import fuaran.ui.JsonObject
import fuaran.ui.JsonString
import java.io.File

/**
 * The render-obligation reader and reporting surface (WIRE_FORMAT.md 13).
 *
 * `render-fidelity.json` declares, per node kind, the subset of that kind's fallback contract
 * stated as **checkable claims**, drawn from a closed vocabulary the artefact enumerates at the
 * top level. This file reads that enumeration and provides the reporting shape every adopting
 * surface uses, so the surfaces answer the same question in the same words rather than each
 * inventing a way to say "we did not check that".
 *
 * **The enumeration is the artefact's, never a list beside the checkers.** That is the whole
 * mechanism: a claim newly declared on a kind this surface renders arrives here as a claim with no
 * checker and turns the gate RED, rather than as a paragraph a future reader may or may not
 * re-read. A hand list would go stale in exactly the direction that produces a green run.
 *
 * The reader is deliberately STRICT. A tolerant reader that shrugged at a malformed row would
 * report a short enumeration — or an empty one — and a gate that enumerates nothing passes while
 * checking nothing, which is the failure shape this whole mechanism exists to remove. Every
 * structural surprise is an error naming the row.
 *
 * Placed in the test source set rather than beside the renderer: this is a conformance instrument,
 * not part of the shipped surface, and it must not reach the published artefact.
 */

/** One checkable claim a kind owes, bound to the section that states it. */
data class RenderObligation(val id: String, val statement: String, val section: String)

/** One entry of the artefact's closed obligation vocabulary. */
data class ObligationVocabularyEntry(val id: String, val meaning: String)

/** One kind row's declared obligations (most rows declare none — that is a positive statement). */
data class KindObligations(val kind: String, val obligations: List<RenderObligation>)

/** The subset of the render-fidelity manifest this instrument reads. */
data class RenderFidelityManifest(
    val obligationVocabulary: List<ObligationVocabularyEntry>,
    val kinds: List<KindObligations>,
)

private fun str(o: JsonObject, key: String, where: String): String =
    (o[key] as? JsonString)?.value
        ?: error("$where: missing or non-string `$key` — the artefact row cannot be read")

/**
 * Parse the obligation half of `render-fidelity.json`.
 *
 * Only the vocabulary and the per-kind `obligations` arrays are read; the tier declarations beside
 * them are a different consumer's business (the fidelity-badge recipe) and reading them here would
 * couple this gate to fields it makes no claim about.
 */
fun parseRenderFidelityManifest(json: String): RenderFidelityManifest {
    val root = Json.parse(json) as? JsonObject ?: error("render-fidelity.json is not a JSON object")

    val vocabularyArray =
        root["obligationVocabulary"] as? JsonArray
            ?: error("render-fidelity.json carries no `obligationVocabulary` — the vocabulary is CLOSED, so its absence is unreadable, never empty")
    val vocabulary =
        vocabularyArray.items.mapIndexed { i, v ->
            val o = v as? JsonObject ?: error("obligationVocabulary[$i] is not an object")
            ObligationVocabularyEntry(
                id = str(o, "id", "obligationVocabulary[$i]"),
                meaning = str(o, "meaning", "obligationVocabulary[$i]"),
            )
        }

    val kindsArray = root["kinds"] as? JsonArray ?: error("render-fidelity.json carries no `kinds`")
    val kinds =
        kindsArray.items.mapIndexed { i, v ->
            val o = v as? JsonObject ?: error("kinds[$i] is not an object")
            val kind = str(o, "kind", "kinds[$i]")
            // A row with no `obligations` key declares none. That IS the common case (31 of 41
            // rows today), so it is read as the empty list rather than as a malformed row.
            val declared = o["obligations"] as? JsonArray
            val obligations =
                declared?.items?.mapIndexed { j, ov ->
                    val oo = ov as? JsonObject ?: error("kinds[$i]($kind).obligations[$j] is not an object")
                    val at = "$kind.obligations[$j]"
                    RenderObligation(
                        id = str(oo, "id", at),
                        statement = str(oo, "statement", at),
                        section = str(oo, "section", at),
                    )
                } ?: emptyList()
            KindObligations(kind, obligations)
        }

    return RenderFidelityManifest(vocabulary, kinds)
}

/** The environment variable that overrides where the artefact is read from. */
internal const val RENDER_FIDELITY_ENV = "FUARAN_RENDER_FIDELITY"

/**
 * Locate `render-fidelity.json`.
 *
 * [RENDER_FIDELITY_ENV] takes precedence over the corpus root so the gate's go-red property can be
 * PROVEN against a perturbed scratch copy — an obligation injected on a kind whose row declares
 * none must turn this gate red — without writing to the shared corpus, which is the oracle and is
 * never edited to make a surface pass.
 *
 * An override naming a path that is not a file is an ERROR, never a quiet fall-back to the shared
 * corpus: a fall-back would make the go-red proof unfalsifiable, because a mistyped path would
 * produce the same green run as an unperturbed one.
 */
internal fun locateRenderFidelityArtifact(): File? {
    System.getenv(RENDER_FIDELITY_ENV)?.let { declared ->
        val f = File(declared)
        if (f.isFile) return f
        error(
            "$RENDER_FIDELITY_ENV names ${f.absolutePath}, which is not a file. Refusing to fall back to " +
                "the corpus copy: a silent fall-back would make an override-driven proof unfalsifiable.",
        )
    }
    val corpus = locateA11yCorpus() ?: return null
    val artifact = File(corpus, "render-fidelity.json")
    return if (artifact.isFile) artifact else null
}

// --------------------------------------------------------------------------- //
// The reporting surface
// --------------------------------------------------------------------------- //

/**
 * A surface's answer for one declared obligation.
 *
 * [Unchecked] is the case the whole mechanism exists for. A surface that renders a kind and has no
 * checker for one of its claims must say so WITH a reason — **not checked is not passed** — and an
 * obligation that quietly falls out of a suite is exactly the silent failure the closed vocabulary
 * replaces. [NotRendered] is distinct: nothing is owed, rather than owed and unpaid.
 *
 * [NotRendered] is unreachable on this surface **by construction**, and that is a positive
 * statement rather than dead code: the dispatch spine in `FuaranNode` is an `else`-free exhaustive
 * `when` over the sealed `NodeKind`, so every kind the wire declares has a real arm here and every
 * declared obligation is one this surface owes. The case exists because the reporting shape is the
 * spec's, shared across every adopting surface, and a surface that dropped it could not express a
 * kind it does not render.
 */
sealed interface ObligationOutcome {
    /** The surface renders the kind and its suite checks the claim. */
    data object Asserted : ObligationOutcome

    /** The surface renders the kind and has no checker for the claim. */
    data class Unchecked(val reason: String) : ObligationOutcome

    /** The surface does not render the kind at all. */
    data class NotRendered(val reason: String) : ObligationOutcome
}

/** One line of a surface's obligation report. */
data class ObligationReport(
    val kind: String,
    val claimId: String,
    val statement: String,
    val section: String,
    val outcome: ObligationOutcome,
)

/** Every declared obligation, paired with the kind that owes it, in artefact order. */
fun allObligations(manifest: RenderFidelityManifest): List<Pair<String, RenderObligation>> =
    manifest.kinds.flatMap { row -> row.obligations.map { row.kind to it } }

/**
 * Project the manifest through a surface's own answer, one line per declared obligation. The
 * enumeration is the manifest's, so a newly declared obligation appears in the report the moment it
 * lands rather than when someone remembers it.
 */
fun reportObligations(
    manifest: RenderFidelityManifest,
    statusOf: (String, String) -> ObligationOutcome,
): List<ObligationReport> =
    allObligations(manifest).map { (kind, obligation) ->
        ObligationReport(
            kind = kind,
            claimId = obligation.id,
            statement = obligation.statement,
            section = obligation.section,
            outcome = statusOf(kind, obligation.id),
        )
    }

/**
 * The report lines a surface must SURFACE: everything it did not assert. Empty is the only silent
 * result — anything else is printed, so an unchecked obligation is visible in the run rather than
 * inferable from its absence.
 */
fun unassertedObligations(report: List<ObligationReport>): List<ObligationReport> =
    report.filter { it.outcome !is ObligationOutcome.Asserted }

/** The one-line rendering of a report line, so the same sentence appears in every surface's output. */
fun describeObligationReport(line: ObligationReport): String {
    val outcome =
        when (val o = line.outcome) {
            is ObligationOutcome.Asserted -> "asserted"
            is ObligationOutcome.Unchecked -> "UNCHECKED (${o.reason})"
            is ObligationOutcome.NotRendered -> "not rendered (${o.reason})"
        }
    return "${line.kind}/${line.claimId} [${line.section}]: $outcome"
}
