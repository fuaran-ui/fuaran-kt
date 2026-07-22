// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.core

import fuaran.ui.Badge
import fuaran.ui.BoundText
import fuaran.ui.Box
import fuaran.ui.Callout
import fuaran.ui.Fact
import fuaran.ui.FuaranException
import fuaran.ui.FuaranSession
import fuaran.ui.LiteralText
import fuaran.ui.Markdown
import fuaran.ui.Metric
import fuaran.ui.Node
import fuaran.ui.SelectionBinding
import fuaran.ui.decodeNode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The Phase 543 live-session round-trip leg: exercise the JNI binding against the real
 * desktop-native Rust core end to end.
 *
 * The headline test seeds a session with a fixture tree, applies a `TreeOp` through the
 * C-ABI, reads back the re-encoded `tree_json`, decodes it with the Phase 542 projection
 * decoder, and asserts the expected sealed-model shape. Around it sit the lifetime +
 * confinement + typed-error guarantees the wrapper promises.
 *
 * A plain `main`-driven runner (no Gradle/JUnit): `run.ps1` builds the native shim, sets
 * `-Dfuaran.lib`, and the exit code is the gate. Skips cleanly when the shim path is
 * absent (the Rust toolchain was unavailable).
 */

private class Runner {
    var passed = 0
    var failed = 0
    val failures = mutableListOf<String>()

    fun check(name: String, body: () -> Unit) {
        try {
            body()
            passed++
            println("  ok   $name")
        } catch (e: Throwable) {
            failed++
            failures.add("$name — ${e::class.simpleName}: ${e.message}")
            println("  FAIL $name — ${e::class.simpleName}: ${e.message}")
        }
    }
}

private fun require(cond: Boolean, msg: String) {
    if (!cond) error(msg)
}

// 0.2.0 canonical seeds: bare-string Literals; the scalar displayed value is `value`
// (the retired `source` spelling is a hard decode error in the core).
private const val SEED_METRIC =
    """{"id":"metric-1","kind":{"${'$'}type":"Metric","format":{"${'$'}type":"Currency","code":"GBP"},""" +
        """"label":"Revenue","tone":"Brand","value":{"${'$'}type":"Static","value":1234.5}}}"""

private const val EDIT_TO_MARKDOWN =
    """{"${'$'}type":"EditNode","newKind":{"${'$'}type":"Markdown","text":{"${'$'}type":"Literal","text":"Edited"}},"target":"metric-1"}"""

private const val SEED_STATE_METRIC =
    """{"id":"m","kind":{"${'$'}type":"Metric","label":"Live",""" +
        """"value":{"${'$'}type":"State","defaultValue":0,"key":"n"}}}"""

// A Badge whose label is a scalar Transform (count of a 2-row embedded frame). The
// decode-only surface cannot evaluate the Transform — the resolved projection (Phase
// 650) folds it to the literal "2".
private const val SEED_SCALAR_TRANSFORM =
    """{"id":"root","kind":{"${'$'}type":"Box","children":[{"id":"count-badge","kind":{"${'$'}type":"Badge",""" +
        """"label":{"${'$'}type":"Bound","binding":{"${'$'}type":"Transform","pipeline":[{"${'$'}type":"groupBy",""" +
        """"aggs":[{"fn":"count","name":"n","of":"id"}],"keys":[]}],"source":{"columns":{"id":{"values":["A","B"]}},""" +
        """"schema":[{"name":"id","type":"string"}]}}},"variant":"Neutral"}}],"layout":{"${'$'}type":"Auto"},"role":"Group"}}"""

/** Depth-first search for a node by id (recurses [Box] children — enough for the fixtures). */
private fun findNode(node: Node, id: String): Node? {
    if (node.id == id) return node
    val kind = node.kind
    if (kind is Box) {
        for (child in kind.children) {
            findNode(child, id)?.let { return it }
        }
    }
    return null
}

/** Locate the shared corpus `nodes/` dir, or null on a standalone checkout. */
private fun locateCorpusNodes(): File? {
    System.getenv("FUARAN_CORPUS")?.let {
        val f = File(it, "nodes")
        if (f.isDirectory) return f
    }
    for (c in listOf("../wire-format-fixtures", "../../wire-format-fixtures", "wire-format-fixtures")) {
        val f = File(File(c), "nodes")
        if (File(c, "manifest.json").isFile && f.isDirectory) return f
    }
    return null
}

fun main() {
    val libPath = System.getProperty("fuaran.lib")
    if (libPath.isNullOrBlank()) {
        println("SKIP: -Dfuaran.lib not set (native JNI shim unavailable — Rust toolchain / C compiler absent). Nothing to certify.")
        return
    }
    NativeBridge.load(libPath)
    println("Loaded native JNI shim: $libPath")
    val runner = Runner()

    // --- The headline round-trip: new -> apply(TreeOp) -> tree_json -> decode -> assert ---
    runner.check("round-trip/edit-metric-to-markdown") {
        val decoded =
            FuaranSession.create(NativeBridge, SEED_METRIC).use { session ->
                // Sanity: the seed decodes to a Metric before the edit.
                val before = decodeNode(session.treeJson())
                require(before.kind is Metric) { "seed tree should decode to a Metric, was ${before.kind::class.simpleName}" }
                session.applyOp(EDIT_TO_MARKDOWN)
                decodeNode(session.treeJson())
            }
        require(decoded.id == "metric-1") { "expected id metric-1, was ${decoded.id}" }
        val kind = decoded.kind
        require(kind is Markdown) { "expected Markdown after EditNode, was ${kind::class.simpleName}" }
        val text = kind.text
        require(text is LiteralText && text.text == "Edited") { "expected literal 'Edited', was $text" }
    }

    // --- render() smoke: the live core emits non-empty HTML for the current tree ---
    runner.check("render/non-empty-html") {
        FuaranSession.create(NativeBridge, SEED_METRIC).use { session ->
            val html = session.render()
            require(html.isNotBlank()) { "render() returned blank HTML" }
        }
    }

    // --- Resolved projection (Phase 650): the core folds a scalar Transform to a literal ---
    runner.check("project-resolved/folds-scalar-transform") {
        FuaranSession.create(NativeBridge, SEED_SCALAR_TRANSFORM).use { session ->
            // Additive: the raw tree_json still carries the unresolved Transform.
            require(session.treeJson().contains(""""${'$'}type":"Transform"""")) {
                "tree_json must keep the raw Transform (the resolved projection is additive)"
            }
            val projected = decodeNode(session.projectResolved())
            val badge = findNode(projected, "count-badge") ?: error("count-badge missing from projection")
            val kind = badge.kind
            require(kind is Badge) { "expected a Badge, was ${kind::class.simpleName}" }
            val label = kind.label
            require(label is LiteralText && label.text == "2") {
                "the Badge label Transform must fold to the literal count 2, was $label"
            }
        }
    }

    // --- Render-coverage-shaped: the two corpus fixtures project resolved scalar values ---
    val corpusNodes = locateCorpusNodes()
    if (corpusNodes == null) {
        println("  skip project-resolved/corpus-fixtures (wire-format-fixtures corpus not found)")
    } else {
        runner.check("project-resolved/scalar-transform-composition") {
            val raw = File(corpusNodes, "scalar-transform-composition.json").readText()
            FuaranSession.create(NativeBridge, raw).use { session ->
                val tree = decodeNode(session.projectResolved())
                // Badge label — a global-aggregate scalar Transform → the count 2.
                val badge = findNode(tree, "critical-count-badge") ?: error("critical-count-badge missing")
                val bk = badge.kind
                require(bk is Badge) { "expected a Badge, was ${bk::class.simpleName}" }
                val bl = bk.label
                require(bl is LiteralText && bl.text == "2") { "Badge must resolve the critical count 2, was $bl" }
                // Callout body — a param-defaulted row-field lookup → the defaulted alert text.
                val callout = findNode(tree, "sla-warning") ?: error("sla-warning missing")
                val ck = callout.kind
                require(ck is Callout) { "expected a Callout, was ${ck::class.simpleName}" }
                val cb = ck.body
                require(cb is LiteralText && cb.text == "TCK-2041 breaches SLA in 2 hours") {
                    "Callout body must resolve the defaulted row's alert text, was $cb"
                }
            }
        }

        runner.check("project-resolved/master-detail-preselected") {
            val raw = File(corpusNodes, "master-detail-preselected.json").readText()
            FuaranSession.create(NativeBridge, raw).use { session ->
                val tree = decodeNode(session.projectResolved())
                // The detail Fact value is a Selection(defaultValue 'TCK-2041') — NOT a Transform —
                // so the projection leaves it intact; the surface's BindingContext resolves the
                // seeded default to TCK-2041 at render time.
                val fact = findNode(tree, "detail-ticket") ?: error("detail-ticket missing")
                val fk = fact.kind
                require(fk is Fact) { "expected a Fact, was ${fk::class.simpleName}" }
                val value = fk.value
                require(value is BoundText && value.binding is SelectionBinding) {
                    "the Fact value stays a Selection binding, was $value"
                }
                val sel = value.binding as SelectionBinding
                require(sel.defaultValue != null) { "the Selection default (TCK-2041) survives the projection" }
            }
        }
    }

    // --- set_state exercises the four-buffer marshalling path (no error envelope) ---
    runner.check("set-state/no-error") {
        FuaranSession.create(NativeBridge, SEED_STATE_METRIC).use { session ->
            session.setState("n", "99")
            // tree_json still round-trips after a store write.
            val node = decodeNode(session.treeJson())
            require(node.id == "m") { "expected id m, was ${node.id}" }
        }
    }

    // --- Typed error: an undecodable seed raises FuaranException with a canonical code ---
    runner.check("error/invalid-node-throws-FuaranException") {
        val threw =
            try {
                FuaranSession.create(NativeBridge, """{"id":"x","kind":{"${'$'}type":"NotAKind"}}""")
                false
            } catch (e: FuaranException) {
                require(e.code.isNotBlank()) { "FuaranException carried a blank code" }
                println("     (surfaced ${e.code} at ${e.path})")
                true
            }
        require(threw) { "expected a FuaranException for an unmodelled node kind" }
    }

    // --- Lifetime: close is idempotent; use-after-close is a typed misuse ---
    runner.check("lifetime/close-idempotent-and-use-after-close") {
        val session = FuaranSession.create(NativeBridge, SEED_METRIC)
        session.close()
        session.close() // must not throw
        val threw =
            try {
                session.treeJson()
                false
            } catch (_: IllegalStateException) {
                true
            }
        require(threw) { "expected IllegalStateException calling treeJson() after close()" }
    }

    // --- Confinement: concurrent callers are serialised through the single-owner executor ---
    runner.check("confinement/concurrent-callers-serialised") {
        FuaranSession.create(NativeBridge, SEED_METRIC).use { session ->
            val expected = session.treeJson()
            val threads = 8
            val pool = Executors.newFixedThreadPool(threads)
            val start = CountDownLatch(1)
            val results = java.util.concurrent.ConcurrentLinkedQueue<String>()
            repeat(threads) {
                pool.submit {
                    start.await()
                    repeat(25) { results.add(session.treeJson()) }
                }
            }
            start.countDown()
            pool.shutdown()
            require(pool.awaitTermination(30, TimeUnit.SECONDS)) { "confinement stress did not finish in time" }
            require(results.size == threads * 25) { "expected ${threads * 25} reads, got ${results.size}" }
            require(results.all { it == expected }) { "concurrent tree_json reads diverged — confinement broke" }
        }
    }

    println()
    if (runner.failed == 0) {
        println("PASS: ${runner.passed} JNI session checks green (live desktop Rust core round-trip)")
    } else {
        println("FAIL: ${runner.failed} of ${runner.passed + runner.failed} checks failed")
        runner.failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
