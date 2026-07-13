// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.ui

import java.io.File

/**
 * The corpus render-coverage harness for Phase 542.
 *
 * The bar: every **node round-trip** fixture in the shared `wire-format-fixtures/`
 * corpus decodes into the sealed model with **zero fallback-arm hits** (an unmodelled
 * `$type` throws [FuaranDecodeException]; there is no catch-all producing a generic
 * node). Coverage is reported per [NodeKind] discriminator. The harness locates the
 * corpus via `manifest.json` — the authoritative fixture enumeration — and **skips
 * cleanly** when the corpus is absent, so `fuaran-ui` stays standalone-testable.
 *
 * This is a plain-JVM `main`-driven runner rather than a Gradle/JUnit launch: the repo
 * builds with a bare `kotlinc`, no artefact resolution, and the exit code is the gate.
 */

private class Runner {
    var passed = 0
    var failed = 0
    val failures = mutableListOf<String>()

    fun check(name: String, body: () -> Unit) {
        try {
            body()
            passed++
        } catch (e: Throwable) {
            failed++
            failures.add("$name — ${e::class.simpleName}: ${e.message}")
        }
    }
}

private fun locateCorpus(): File? {
    System.getenv("FUARAN_CORPUS")?.let {
        val f = File(it)
        if (File(f, "manifest.json").isFile) return f
    }
    val candidates =
        listOf(
            "../wire-format-fixtures",
            "../../wire-format-fixtures",
            "wire-format-fixtures",
            "fuaran-kt/../wire-format-fixtures",
        )
    for (c in candidates) {
        val f = File(c)
        if (File(f, "manifest.json").isFile) return f
    }
    return null
}

private fun manifestNodeFixtures(manifestJson: String): List<Pair<String, String>> {
    val root = Json.parse(manifestJson) as JsonObject
    val fixtures = (root["fixtures"] as JsonArray).items
    val out = mutableListOf<Pair<String, String>>()
    for (f in fixtures) {
        val o = f as JsonObject
        val kind = (o["kind"] as? JsonString)?.value ?: continue
        if (kind != "node-round-trip") continue
        val id = (o["id"] as? JsonString)?.value ?: "?"
        val input = (o["inputFile"] as? JsonString)?.value ?: continue
        out.add(id to input)
    }
    return out
}

fun main() {
    val corpus = locateCorpus()
    if (corpus == null) {
        println("SKIP: wire-format-fixtures corpus not found (set FUARAN_CORPUS or run from the repo). Nothing to certify.")
        return
    }
    println("Corpus: ${corpus.absolutePath}")
    val manifest = File(corpus, "manifest.json").readText()
    val nodeFixtures = manifestNodeFixtures(manifest)
    val runner = Runner()
    val coverage = sortedMapOf<String, Int>()

    for ((id, input) in nodeFixtures) {
        runner.check("node-round-trip/$id") {
            val json = File(corpus, input).readText()
            val node = decodeNode(json)
            // Touch the exhaustive dispatch spine so every decoded kind is classified with no `else`.
            val category = node.kind.category()
            val disc = node.kind.discriminator()
            coverage[disc] = (coverage[disc] ?: 0) + 1
            if (category !in NodeCategory.entries) error("uncategorised kind $disc")
        }
    }

    // Default-deny proof: an unmodelled node kind must raise WRONG_NODE_KIND, never a fallback node.
    runner.check("default-deny/unknown-node-kind-throws") {
        val ok =
            try {
                decodeNode("{\"id\":\"x\",\"kind\":{\"\$type\":\"NotAKind\"}}")
                false
            } catch (e: FuaranDecodeException) {
                e.code == FuaranDecodeException.WRONG_NODE_KIND
            }
        if (!ok) error("expected WRONG_NODE_KIND for an unmodelled node kind")
    }
    // Default-deny proof: an unmodelled nested DU case must raise UNKNOWN_DU_CASE.
    runner.check("default-deny/unknown-binding-case-throws") {
        val ok =
            try {
                decodeNode("{\"id\":\"x\",\"kind\":{\"\$type\":\"Sparkline\",\"source\":{\"\$type\":\"Nope\"}}}")
                false
            } catch (e: FuaranDecodeException) {
                e.code == FuaranDecodeException.UNKNOWN_DU_CASE
            }
        if (!ok) error("expected UNKNOWN_DU_CASE for an unmodelled binding case")
    }
    // Empty-id proof.
    runner.check("default-deny/empty-node-id-throws") {
        val ok =
            try {
                decodeNode("{\"id\":\"\",\"kind\":{\"\$type\":\"Markdown\",\"text\":{\"\$type\":\"Literal\",\"text\":\"x\"}}}")
                false
            } catch (e: FuaranDecodeException) {
                e.code == FuaranDecodeException.EMPTY_NODE_ID
            }
        if (!ok) error("expected EMPTY_NODE_ID for an empty id")
    }

    println()
    println("Per-NodeKind coverage (${coverage.size} distinct kinds across ${nodeFixtures.size} node fixtures):")
    for ((disc, n) in coverage) println("  %-16s %d".format(disc, n))

    println()
    if (runner.failed == 0) {
        println("PASS: ${runner.passed} checks green (all ${nodeFixtures.size} node fixtures decoded with zero fallback-arm hits)")
    } else {
        println("FAIL: ${runner.failed} of ${runner.passed + runner.failed} checks failed")
        runner.failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
