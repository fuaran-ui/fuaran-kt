// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.ui.idl

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
 * either mode before anything is written.
 *
 * **Locating the corpus.** `FUARAN_WIRE_FIXTURES` first, then `-Dfuaran.corpus`, then
 * `FUARAN_CORPUS`, then the side-by-side candidates the decode harness also tries. Missing on a
 * standalone clone of this repository is a SKIP; missing on a checkout that plainly has sibling
 * surfaces beside it (or with `FUARAN_REQUIRE_CORPUS=1`) is a FAILURE — a drift check that quietly
 * compared nothing is the vacuous green this posture exists to refuse.
 *
 * Plain `main()`-driven, like the corpus decode harness: the exit code is the gate.
 */
private const val REGENERATE_HINT =
    "regenerate: FUARAN_IDL_REGENERATE=1 ./gradlew :fuaran-ui:idlModelCheck   " +
        "(PowerShell: \$env:FUARAN_IDL_REGENERATE='1'; ./gradlew.bat :fuaran-ui:idlModelCheck)"

private val CANDIDATES = listOf("../wire-format-fixtures", "../../wire-format-fixtures", "wire-format-fixtures")

private val SIBLING_HOST_NAMES =
    listOf("fuaran-dotnet", "fuaran", "fuaran-ts", "fuaran-py", "fuaran-go", "fuaran-rs", "fuaran-swift")

/** The repository root: the nearest ancestor of the working directory holding the `fuaran-ui` module. */
private fun repoRoot(): File {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
        if (File(dir, "fuaran-ui/src/main/kotlin/fuaran/ui").isDirectory) return dir
        dir = dir.parentFile
    }
    error("cannot find the repository root (no fuaran-ui/src/main/kotlin/fuaran/ui above ${File(".").absolutePath})")
}

private fun locateIdl(root: File): File? {
    val explicit =
        listOfNotNull(System.getenv("FUARAN_WIRE_FIXTURES"), System.getProperty("fuaran.corpus"), System.getenv("FUARAN_CORPUS"))
    for (e in explicit) File(e, "idl.json").takeIf { it.isFile }?.let { return it }
    for (c in CANDIDATES) File(root, c).resolve("idl.json").takeIf { it.isFile }?.let { return it.canonicalFile }
    return null
}

private fun crossHostSibling(root: File): File? {
    var dir: File? = root.parentFile
    while (dir != null) {
        for (name in SIBLING_HOST_NAMES) File(dir, name).takeIf { it.isDirectory }?.let { return it }
        dir = dir.parentFile
    }
    return null
}

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
    val root = repoRoot()
    val idlFile = locateIdl(root)
    if (idlFile == null) {
        val sibling = crossHostSibling(root)
        if (sibling != null || System.getenv("FUARAN_REQUIRE_CORPUS") == "1") {
            println("FAIL: no corpus idl.json found, on a checkout that should have one (${sibling ?: "FUARAN_REQUIRE_CORPUS=1"}).")
            println("  FUARAN_WIRE_FIXTURES=${System.getenv("FUARAN_WIRE_FIXTURES") ?: "<unset>"}")
            println("  FUARAN_CORPUS=${System.getenv("FUARAN_CORPUS") ?: "<unset>"}")
            for (c in CANDIDATES) println("  tried: ${File(root, c).absolutePath}")
            println("The model drift check compared NOTHING.")
            exitProcess(1)
        }
        println("SKIP: no wire corpus beside this checkout (set FUARAN_WIRE_FIXTURES). The model was not compared.")
        return
    }
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
