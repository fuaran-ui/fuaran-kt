// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.ui.idl

import fuaran.testing.WireCorpus
import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * The model drift check (Phase 2299) — regenerate the sealed tree model from the corpus's
 * `idl.json` and compare it, byte for byte, with the generated files on disk.
 *
 *  * Default: COMPARE. Any difference fails the run naming the file, the first differing line and
 *    the command that regenerates, so a vocabulary change that reached the corpus and not this
 *    surface is red here rather than a decode failure somewhere downstream.
 *  * `FUARAN_IDL_REGENERATE=1`: REWRITE the generated files, then report what moved. The diff is
 *    the review: a regenerated model that no longer compiles is the point — every exhaustive
 *    `when` over the kind set (the decoder's dispatch, the renderer's) names the arm that is owed.
 *
 * A dialect refusal (an entry naming something the IDL no longer has, or restating a rule) fails
 * either mode before anything is written, and so do the generator's carry-rule checks
 * ([generatorRuleChecks], Phase 2331).
 *
 * **Locating the corpus.** Through the one locator every harness here shares, [WireCorpus]:
 * `FUARAN_WIRE_FIXTURES` first, then `-Dfuaran.corpus`, then `FUARAN_CORPUS`, then the side-by-side
 * candidates. The corpus is REQUIRED — a drift check that quietly compared nothing is the vacuous
 * green this posture exists to refuse.
 *
 * Plain `main()`-driven, like the corpus decode harness: the exit code is the gate.
 */
private const val REGENERATE_HINT =
    "regenerate: FUARAN_IDL_REGENERATE=1 ./gradlew :fuaran-ui:idlModelCheck   " +
        "(PowerShell: \$env:FUARAN_IDL_REGENERATE='1'; ./gradlew.bat :fuaran-ui:idlModelCheck)"

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Generate from the corpus at [idlFile]; the map is repository-relative path to content. */
fun generateFrom(idlFile: File): Map<String, String> {
    val bytes = idlFile.readBytes()
    return generateModel(readIdl(String(bytes, Charsets.UTF_8)), sha256Hex(bytes), DIALECT, MODEL_DOCS)
}

/**
 * The first line that differs, skipping the header's recorded hash — which differs whenever the IDL
 * moved at all, and so says nothing about WHAT moved.
 */
private fun firstDifference(want: String, got: String): String {
    val w = want.split("\n")
    val g = got.split("\n")
    val hashMoved = w.firstOrNull { "idl.json sha256" in it } != g.firstOrNull { "idl.json sha256" in it }
    for (i in 0 until maxOf(w.size, g.size)) {
        val a = w.getOrNull(i)
        val b = g.getOrNull(i)
        if (a != b && !(a != null && b != null && "idl.json sha256" in a && "idl.json sha256" in b)) {
            return "${if (hashMoved) "idl.json moved; " else ""}first difference at line ${i + 1}:\n" +
                "      generated: ${a ?: "<end of file>"}\n      on disk:   ${b ?: "<end of file>"}"
        }
    }
    return "only the recorded idl.json hash differs (the IDL's bytes moved, the vocabulary did not)"
}

fun main() {
    val root = WireCorpus.repoRoot()
    // The corpus is REQUIRED (Phase 2301): the one locator throws, naming what it tried, when there is none.
    val idlFile = File(WireCorpus.root(), "idl.json")
    println("idl.json: ${idlFile.path}")
    val generated =
        try {
            generateFrom(idlFile)
        } catch (e: DialectRefusal) {
            println("FAIL: the dialect refused generation — ${e.message}")
            exitProcess(1)
        } catch (e: IdlShapeException) {
            println("FAIL: idl.json carries a shape this generator does not read — ${e.message}")
            exitProcess(1)
        }
    // Phase 2331 — the carry rule, proven by perturbing this same IDL before anything is compared.
    val ruleFailures = generatorRuleChecks(readIdl(idlFile.readText()))
    if (ruleFailures.isNotEmpty()) {
        ruleFailures.forEach { println("FAIL: generator rule — $it") }
        exitProcess(1)
    }
    println("  generator rules: a new field is carried or refused by name; a new union case reaches its tag set")
    val regenerate = System.getenv("FUARAN_IDL_REGENERATE") == "1"
    var stale = 0
    for ((path, content) in generated) {
        val f = File(root, path)
        val onDisk = if (f.isFile) f.readText() else null
        if (onDisk == content) {
            println("  current: $path")
            continue
        }
        if (regenerate) {
            f.writeText(content)
            println("  REGENERATED: $path")
        } else {
            stale++
            println("  STALE: $path — ${if (onDisk == null) "absent" else firstDifference(content, onDisk)}")
        }
    }
    if (stale > 0) {
        println("FAIL: $stale generated model file(s) do not match idl.json. The vocabulary moved and this surface did not.")
        println("  $REGENERATE_HINT")
        exitProcess(1)
    }
    println(if (regenerate) "Model regenerated from idl.json." else "PASS: the generated model matches idl.json (${generated.size} files).")
}
