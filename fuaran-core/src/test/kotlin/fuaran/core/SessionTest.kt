// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.core

import fuaran.ui.FuaranException
import fuaran.ui.FuaranSession
import fuaran.ui.LiteralText
import fuaran.ui.Markdown
import fuaran.ui.Metric
import fuaran.ui.decodeNode
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
