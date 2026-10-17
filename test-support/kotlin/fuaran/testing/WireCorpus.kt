// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.testing

import java.io.File

/**
 * The ONE corpus locator every harness in this repository resolves through (Phase 2301).
 *
 * It is compiled into each module's test source set (`test-support/kotlin`, added by each module's
 * `build.gradle.kts`) and onto `run.ps1`'s direct `kotlinc` line, so a Gradle test worker, a
 * Gradle `JavaExec` harness and the plain-JVM harnesses all answer the same question the same way.
 * Before it, six readers each carried their own candidate list, and they disagreed: some honoured
 * `FUARAN_CORPUS`, one honoured `FUARAN_WIRE_FIXTURES`, one refused a wrong declared root while the
 * others fell through it.
 *
 * **Order.** `FUARAN_WIRE_FIXTURES` (the variable the cross-host change-set names a corpus by), then
 * `-Dfuaran.corpus` (what the Gradle legs forward), then `FUARAN_CORPUS` (what CI and `run.ps1`
 * set), then the side-by-side candidates relative to the working directory.
 *
 * **A declared root is REFUSED BY NAME, never fallen back from.** A typo'd or stale declaration that
 * fell through to the side-by-side corpus would produce a run identical to a working one, so the
 * override could never be falsified — and a probe pointing a harness at a perturbed copy would pass
 * by certifying the unperturbed original.
 *
 * **The corpus is REQUIRED.** No candidate resolving is a failure naming every path tried, not a
 * skip: a harness that skipped on a missing corpus reported green having certified nothing.
 */
object WireCorpus {
    /** The variable the cross-host change-set uses to name the corpus a run certifies against. */
    const val WIRE_FIXTURES_ENV = "FUARAN_WIRE_FIXTURES"

    /** The system property the Gradle legs forward. */
    const val CORPUS_PROPERTY = "fuaran.corpus"

    /** The variable CI and `run.ps1` set. */
    const val CORPUS_ENV = "FUARAN_CORPUS"

    /**
     * The corpus checkout's directory name. Spelled here and nowhere else in this repository's Kotlin
     * sources — the corpus harness asserts that (see [literalPathOffenders]).
     */
    const val DIR_NAME = "wire-format-fixtures"

    /**
     * The files that identify THIS corpus. `manifest.json` alone identifies nothing: the corpus's own
     * sub-corpora each carry one, and so does every other specification corpus.
     */
    private val IDENTITY_MARKERS = listOf("manifest.json", "idl.json", "WIRE_FORMAT.md")

    /** Side-by-side candidates, relative to the working directory (repo root or a module dir). */
    private val CANDIDATES = listOf("../$DIR_NAME", "../../$DIR_NAME", "../../../$DIR_NAME", DIR_NAME)

    private fun isWireCorpus(dir: File): Boolean = IDENTITY_MARKERS.all { File(dir, it).isFile }

    private fun declarations(): List<Pair<String, String>> =
        listOfNotNull(
            System.getenv(WIRE_FIXTURES_ENV)?.let { WIRE_FIXTURES_ENV to it },
            System.getProperty(CORPUS_PROPERTY)?.let { "-D$CORPUS_PROPERTY" to it },
            System.getenv(CORPUS_ENV)?.let { CORPUS_ENV to it },
        ).filter { it.second.isNotBlank() }

    /** The corpus root. Throws, naming what was tried, when there is none. */
    fun root(): File {
        // The FIRST declaration decides. A later one is not consulted, so a stale FUARAN_CORPUS
        // cannot veto the FUARAN_WIRE_FIXTURES a probe set.
        declarations().firstOrNull()?.let { (source, value) ->
            val f = File(value)
            if (isWireCorpus(f)) return f.absoluteFile
            val missing = IDENTITY_MARKERS.filterNot { File(f, it).isFile }
            throw IllegalStateException(
                "$source names '$value' (${f.absolutePath}), which is not the Fuaran UI wire-format corpus: " +
                    "$missing absent. It is REFUSED rather than fallen back from — a fall-back would certify a " +
                    "corpus you did not name, and a typo would look identical to a working run.",
            )
        }
        for (c in CANDIDATES) {
            val f = File(c)
            if (isWireCorpus(f)) return f.canonicalFile
        }
        throw IllegalStateException(
            "the wire-format corpus was not found, and it is REQUIRED (a run without it certifies nothing). " +
                "Set $WIRE_FIXTURES_ENV to the corpus root. Tried, from ${File(".").absolutePath}: " +
                CANDIDATES.joinToString { File(it).absolutePath },
        )
    }

    /**
     * The repository root: the nearest ancestor of the working directory holding this file's
     * `test-support` tree beside `settings.gradle.kts`.
     */
    fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "test-support").isDirectory) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("cannot find the repository root above ${File(".").absolutePath}")
    }

    /**
     * Every Kotlin source under [repo] other than this file that spells the corpus directory as a
     * path literal — a string literal whose content is the directory name, or names it as a path
     * segment. Such a literal is a second locator, and second locators are what drifted apart.
     * Prose that merely mentions the corpus (a comment, a message) is not a path and is not flagged.
     */
    fun literalPathOffenders(repo: File): List<String> {
        val name = Regex.escape(DIR_NAME)
        val pathLiteral = Regex("\"(?:[^\"\\n]*[/\\\\])?$name(?:[/\\\\][^\"\\n]*)?\"")
        return repo
            .walkTopDown()
            .onEnter { it.name !in setOf(".git", "build", ".gradle", "node_modules") }
            .filter { it.isFile && it.extension == "kt" && it.name != "WireCorpus.kt" }
            .flatMap { f ->
                f.readLines().withIndex().filter { (_, line) -> pathLiteral.containsMatchIn(line) }.map { (i, line) ->
                    "${f.relativeTo(repo).invariantSeparatorsPath}:${i + 1}: ${line.trim()}"
                }
            }.toList()
    }
}
