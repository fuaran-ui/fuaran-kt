// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import fuaran.ui.Json
import fuaran.ui.JsonArray
import fuaran.ui.JsonObject
import fuaran.ui.JsonString
import fuaran.ui.Node
import fuaran.ui.decodeNode
import fuaran.ui.discriminator
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The Phase 544 render-coverage gate — the render-leg twin of Phase 542's decode-coverage harness.
 *
 * Every **node-round-trip** fixture in the shared `wire-format-fixtures/` corpus is decoded (via the
 * shipped `:fuaran-ui` `decodeNode` — the renderer never parses wire JSON) and then **composed
 * headlessly** through [FuaranNode] under Robolectric (JVM, no emulator). The bar: every fixture
 * composes without throwing and with **zero fallback-arm hits** ([RenderCoverage.fallbacks] empty);
 * per-kind coverage is reported. Any corpus kind not exercised is enumerated as an explicit TODO —
 * never a silent gap. Skips cleanly when the corpus is absent.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RenderCoverageTest {
    @Test
    fun everyCorpusNodeFixtureRendersThroughTheFloorWithZeroFallbacks() {
        val corpus = locateCorpus()
        if (corpus == null) {
            println("SKIP: wire-format-fixtures corpus not found (set -Dfuaran.corpus). Nothing to certify.")
            return
        }
        println("Corpus: ${corpus.absolutePath}")
        val manifestJson = File(corpus, "manifest.json").readText()
        val fixtures = manifestNodeFixtures(manifestJson)
        val corpusKinds = manifestKinds(manifestJson)

        val coverage = RenderCoverage()
        val failures = mutableListOf<String>()

        // Decode first (the renderer is a pure projection — decode is not its concern).
        val decoded = mutableListOf<Pair<String, Node>>()
        for ((id, input) in fixtures) {
            try {
                decoded.add(id to decodeNode(File(corpus, input).readText()))
            } catch (e: Throwable) {
                failures.add("decode $id — ${e::class.simpleName}: ${e.message}")
            }
        }

        // Render each fixture in an isolated headless composition.
        for ((id, node) in decoded) {
            try {
                runComposeUiTest {
                    setContent {
                        CompositionLocalProvider(LocalRenderCoverage provides coverage) {
                            MaterialTheme { FuaranNode(node) }
                        }
                    }
                    waitForIdle()
                }
            } catch (e: Throwable) {
                failures.add("render $id (${node.kind.discriminator()}) — ${e::class.simpleName}: ${e.message}")
            }
        }

        println()
        println("Per-NodeKind render coverage (${coverage.kinds.size} distinct kinds over ${decoded.size} fixtures):")
        coverage.kinds.forEach { (disc, n) -> println("  %-16s %d".format(disc, n)) }

        val todo = (corpusKinds - coverage.kinds.keys).sorted()
        println()
        if (todo.isEmpty()) {
            println("Floor coverage: every corpus node kind rendered through a real arm (no TODO kinds).")
        } else {
            println("TODO kinds (present in corpus, not exercised by a rendered fixture): $todo")
        }
        if (coverage.fallbacks.isNotEmpty()) println("FALLBACK-ARM HITS: ${coverage.fallbacks}")

        println()
        if (failures.isEmpty() && coverage.fallbacks.isEmpty()) {
            println("PASS: all ${decoded.size} node fixtures composed through the floor with zero fallback-arm hits.")
        } else {
            failures.forEach { println("  - $it") }
        }

        assertTrue("fallback-arm hits: ${coverage.fallbacks}", coverage.fallbacks.isEmpty())
        assertTrue("render/decode failures:\n${failures.joinToString("\n")}", failures.isEmpty())
    }
}

// --------------------------------------------------------------------------- //
// Corpus helpers (mirror the Phase 542 CorpusDecodeTest locate/parse logic)
// --------------------------------------------------------------------------- //

private fun locateCorpus(): File? {
    System.getProperty("fuaran.corpus")?.let {
        val f = File(it)
        if (File(f, "manifest.json").isFile) return f
    }
    System.getenv("FUARAN_CORPUS")?.let {
        val f = File(it)
        if (File(f, "manifest.json").isFile) return f
    }
    val candidates =
        listOf(
            "../../wire-format-fixtures",
            "../wire-format-fixtures",
            "../../../wire-format-fixtures",
            "wire-format-fixtures",
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

private fun manifestKinds(manifestJson: String): Set<String> {
    val root = Json.parse(manifestJson) as JsonObject
    val arr = root["kinds"] as? JsonArray ?: return emptySet()
    return arr.items.mapNotNull { (it as? JsonString)?.value }.toSet()
}
