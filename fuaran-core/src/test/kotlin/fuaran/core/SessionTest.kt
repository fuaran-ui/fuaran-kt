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
import fuaran.ui.FuaranSessionClosedException
import fuaran.ui.JsonObject
import fuaran.ui.JsonString
import fuaran.ui.LiteralText
import fuaran.ui.Markdown
import fuaran.ui.Metric
import fuaran.ui.Node
import fuaran.ui.Placement
import fuaran.ui.ResolvedRows
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

/**
 * The ids of a Box's children, read off the session's OWN re-encoded tree — so a placement
 * assertion is about the tree the core HOLDS, never a Kotlin-side echo of the request.
 *
 * Box-only, which is all the placement seed contains; a non-Box parent reads as childless
 * and fails the assertion loudly rather than quietly matching an empty expectation.
 */
private fun childIds(treeJson: String, parentId: String): List<String> {
    fun kids(node: Node): List<Node> = (node.kind as? Box)?.children ?: emptyList()
    fun find(node: Node): Node? {
        if (node.id == parentId) return node
        for (child in kids(node)) {
            val hit = find(child)
            if (hit != null) return hit
        }
        return null
    }
    val parent = find(decodeNode(treeJson)) ?: error("no node '$parentId' in the session tree")
    return kids(parent).map { it.id }
}

private fun require(cond: Boolean, msg: String) {
    if (!cond) error(msg)
}

// 0.2.0 canonical seeds: bare-string Literals; the scalar displayed value is `value`
// (the retired `source` spelling is a hard decode error in the core).
private const val SEED_METRIC =
    """{"id":"metric-1","kind":{"${'$'}type":"Metric","format":{"${'$'}type":"Currency","code":"GBP"},""" +
        """"label":"Revenue","tone":"Brand","value":{"${'$'}type":"Static","value":1234.5}}}"""

// A two-container tree for the Phase 1673 placement leg: a box with two children and an
// empty box beside it, so a move can be observed both LEAVING and ARRIVING.
private const val SEED_PLACEMENT =
    """{"id":"root","kind":{"${'$'}type":"Box","children":[""" +
        """{"id":"left","kind":{"${'$'}type":"Box","children":[""" +
        """{"id":"a","kind":{"${'$'}type":"Markdown","text":"A"}},""" +
        """{"id":"b","kind":{"${'$'}type":"Markdown","text":"B"}}],""" +
        """"layout":{"${'$'}type":"Flex","direction":"Vertical","wrap":false},"role":"Group"}},""" +
        """{"id":"right","kind":{"${'$'}type":"Box","children":[],""" +
        """"layout":{"${'$'}type":"Flex","direction":"Vertical","wrap":false},"role":"Group"}}],""" +
        """"layout":{"${'$'}type":"Flex","direction":"Vertical","wrap":false},"role":"Group"}}"""

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

    // A grid whose rows come from an embedded Transform, plus a sibling of a kind with no row
    // source so the NoRowSource arm has a real node to be asked about.
    val SEED_BOUND_GRID =
        """{"id":"root","kind":{"${'$'}type":"Box","children":[{"id":"shipments","kind":{"${'$'}type":"DataGrid","columns":[{"field":"status","kind":{"${'$'}type":"TonedPill","default":"Subdued","field":"status","map":{"Delayed":"Warning"}},"label":"Status"}],"rowKeyField":"status","source":{"${'$'}type":"Transform","pipeline":[],"source":{"columns":{"status":{"validity":[true,true],"values":["Delayed","Other"]}},"schema":[{"name":"status","type":"string"}]}}}},{"id":"heading","kind":{"${'$'}type":"Heading","level":1,"text":"Shipments","variant":"Standard"}}],"layout":{"${'$'}type":"Auto"},"role":"Group"}}"""

    // A grid bound to a Query no host has fed — the unresolved case.
    val SEED_QUERY_GRID =
        """{"id":"g","kind":{"${'$'}type":"DataGrid","columns":[],"rowKeyField":"id","source":{"${'$'}type":"Query","dependsOn":[],"name":"shipments"}}}"""

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

    // --- Resolved rows (Phase 753): the out-of-band hand-off the tree cannot carry ---
    runner.check("resolved-rows/hands-over-what-the-tree-cannot-carry") {
        FuaranSession.create(NativeBridge, SEED_BOUND_GRID).use { session ->
            // The premise, asserted rather than assumed: a row-context Transform resolves to a
            // COLLECTION, which cannot ride a `Static` slot (§2 rule 11) — so even the resolved
            // projection still carries it raw. That is the whole reason this call exists.
            require(session.projectResolved().contains(""""${'$'}type":"Transform"""")) {
                "the resolved projection cannot carry row-context Transforms"
            }
            val outcome = session.resolvedRows("shipments")
            require(outcome is ResolvedRows.Rows) { "expected resolved rows, got $outcome" }
            require(outcome.rows.size == 2) { "expected 2 rows, got ${outcome.rows.size}" }
            val first = outcome.rows[0] as? JsonObject ?: error("row 0 is not an object")
            require((first["status"] as? JsonString)?.value == "Delayed") { "row 0 status wrong: $first" }
        }
    }

    runner.check("resolved-rows/three-outcomes-stay-distinct") {
        FuaranSession.create(NativeBridge, SEED_BOUND_GRID).use { session ->
            // A real node of a kind with no row source, and an id naming nothing — both caller
            // mistakes, and neither may masquerade as "this grid has no rows".
            require(session.resolvedRows("heading") == ResolvedRows.NoRowSource) { "heading must be NoRowSource" }
            require(session.resolvedRows("nope") == ResolvedRows.NoRowSource) { "unknown id must be NoRowSource" }
        }
        FuaranSession.create(NativeBridge, SEED_QUERY_GRID).use { session ->
            // Unfed: loading, NOT empty.
            require(session.resolvedRows("g") == ResolvedRows.NotResolved) { "an unfed Query must be NotResolved" }
            // Fed: resolves, including to genuinely zero rows — the empty state.
            session.setQuery("shipments", "[]")
            val fed = session.resolvedRows("g")
            require(fed is ResolvedRows.Rows && fed.rows.isEmpty()) { "a fed empty Query must be Rows([]), was $fed" }
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

        // WIRE_FORMAT.md 24.4 - slot SEEDING, and the answer to this surface's
        // adoption question: the derived value is INHERITED from the core.
        //
        // This surface carries no evaluator. A scalar Transform reaches it
        // already folded by the core's project_resolved, and a row-context one
        // arrives out of band through resolved_rows - both read the core's own
        // binding sources, so the core's seeding pass is what fills the slot.
        // There is deliberately no seeding pass on this side and there must not
        // be one: a second pass over the same tree would be a second opinion
        // about a value the core has already decided.
        //
        // The fixture declares two rows under $state.members on a grid's source,
        // beside a badge whose Transform derives over the same key and carries
        // no data of its own. 2 is what the reference tiers render. Observed RED
        // with the core's seeding pass disabled (the badge label folds to the
        // empty string), so this is a measurement rather than an assumption.
        runner.check("project-resolved/state-seeded-pair") {
            val raw = File(corpusNodes, "shared-source-seeded-pair.json").readText()
            FuaranSession.create(NativeBridge, raw).use { session ->
                val tree = decodeNode(session.projectResolved())
                val badge = findNode(tree, "member-count") ?: error("member-count missing")
                val bk = badge.kind
                require(bk is Badge) { "expected a Badge, was ${bk::class.simpleName}" }
                val bl = bk.label
                require(bl is LiteralText && bl.text == "2") {
                    "the badge must derive the SIBLING grid's two declared rows, was $bl"
                }
                // The grid's rows come from the SAME slot, out of band.
                val rows = session.resolvedRows("member-grid")
                require(rows is ResolvedRows.Rows && rows.rows.size == 2) {
                    "the grid must resolve the two declared rows, was $rows"
                }
            }
        }

        // The go-red half. An assertion nobody has watched fail is a claim about
        // the author's confidence, not about the seam - so a WRITE through the
        // session's own channel must move the derived value, which also pins
        // 24.4's precedence (a written value wins over a seed) from this side.
        runner.check("project-resolved/a-write-overrides-the-seed") {
            val raw = File(corpusNodes, "shared-source-seeded-pair.json").readText()
            FuaranSession.create(NativeBridge, raw).use { session ->
                session.setState("members", """[{"team":"Only"}]""")
                val tree = decodeNode(session.projectResolved())
                val badge = findNode(tree, "member-count") ?: error("member-count missing")
                val bl = (badge.kind as Badge).label
                require(bl is LiteralText && bl.text == "1") {
                    "a written value did not override the seed, was $bl"
                }
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

    // --- Lifetime: a use-after-close is TYPED, not merely an IllegalStateException ---
    runner.check("lifetime/use-after-close-is-FuaranSessionClosedException") {
        val session = FuaranSession.create(NativeBridge, SEED_METRIC)
        session.close()
        val threw =
            try {
                session.projectResolved()
                false
            } catch (_: FuaranSessionClosedException) {
                true
            }
        require(threw) { "expected FuaranSessionClosedException, not a bare IllegalStateException" }
    }

    // --- Lifetime: close RACING a call must not free the handle under it ---
    //
    // The failure this covers is a native use-after-free, and the shape of the test is worth
    // explaining. `onExecutor` used to read `closed` on the CALLING thread and then submit: a
    // reader could pass that check, be descheduled, and submit after the closer had already queued
    // `sessionFree`, so `sessionTreeJson` ran against a handle the core no longer owned. That
    // returns garbage, or crashes under a hardened allocator; either way the JVM cannot report it
    // as anything.
    //
    // So the assertion is NOT "no exception happened". A refusal is a correct outcome — the reader
    // may genuinely be late — and demanding otherwise would only re-test the scheduler. What is
    // asserted is that every call either returns THE RIGHT BYTES or is refused by name, and that
    // the process survives to say so. A run in which the free won every race passes; a run in
    // which it lost and read freed memory does not.
    //
    // The legs are not serialised to make them pass: both threads run unsynchronised against the
    // same session from a shared barrier, with the closer interleaved mid-stream.
    runner.check("lifetime/close-does-not-race-an-in-flight-call") {
        val expected = FuaranSession.create(NativeBridge, SEED_METRIC).use { it.treeJson() }
        repeat(40) { attempt ->
            val session = FuaranSession.create(NativeBridge, SEED_METRIC)
            val pool = Executors.newFixedThreadPool(5)
            val start = CountDownLatch(1)
            val bad = java.util.concurrent.ConcurrentLinkedQueue<String>()
            // Every worker's Future is KEPT and joined below. A `submit` whose Future is dropped
            // swallows whatever its task threw, so on the old code the two likeliest outcomes —
            // the untyped `IllegalStateException` from `check(!closed)`, and a
            // `RejectedExecutionException` from a submit after shutdown — would have vanished,
            // leaving only a byte divergence or a process crash to fail the leg. Joining makes
            // "refused by NAME" an assertion rather than a hope.
            val workers =
                List(4) {
                    pool.submit {
                        start.await()
                        repeat(50) {
                            try {
                                val json = session.treeJson()
                                if (json != expected) {
                                    // The one genuinely wrong outcome: a call that RETURNED,
                                    // having read a handle that was no longer the session's.
                                    bad.add("attempt $attempt: read diverged after close (${json.take(60)})")
                                }
                            } catch (_: FuaranSessionClosedException) {
                                // Late — correct, and exactly what the typed refusal is for.
                            }
                        }
                    }
                }
            val closer =
                pool.submit {
                    start.await()
                    session.close()
                }
            start.countDown()
            pool.shutdown()
            require(pool.awaitTermination(30, TimeUnit.SECONDS)) { "close race did not finish in time" }
            (workers + closer).forEach { future ->
                try {
                    future.get()
                } catch (e: java.util.concurrent.ExecutionException) {
                    val cause = e.cause ?: e
                    bad.add("attempt $attempt: escaped as ${cause::class.java.simpleName}: ${cause.message}")
                }
            }
            require(bad.isEmpty()) { bad.joinToString("; ") }
        }
    }

    // --- Lifetime: concurrent double-close frees exactly once ---
    //
    // `close()` was `if (closed) return; closed = true; clean()`, which is not the idempotence it
    // reads as: two threads could both observe `false` and both proceed. What actually saved it is
    // `Cleanable.clean()`, which is atomic — so the guard added a race and no protection, and the
    // sequential double-close leg above could never have shown that.
    runner.check("lifetime/concurrent-double-close-frees-once") {
        repeat(40) {
            val session = FuaranSession.create(NativeBridge, SEED_METRIC)
            val closers = 6
            val pool = Executors.newFixedThreadPool(closers)
            val start = CountDownLatch(1)
            val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()
            repeat(closers) {
                pool.submit {
                    start.await()
                    try {
                        session.close()
                    } catch (t: Throwable) {
                        failures.add("close() threw ${t::class.java.simpleName}: ${t.message}")
                    }
                }
            }
            start.countDown()
            pool.shutdown()
            require(pool.awaitTermination(30, TimeUnit.SECONDS)) { "double-close race did not finish in time" }
            require(failures.isEmpty()) { failures.joinToString("; ") }
            // A second free would have aborted the process long before this line; reaching it, and
            // getting the typed refusal, is the evidence the handle was released exactly once.
            val threw =
                try {
                    session.treeJson()
                    false
                } catch (_: FuaranSessionClosedException) {
                    true
                }
            require(threw) { "expected FuaranSessionClosedException after a concurrent double close" }
        }
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

    // --- Placement: the drag-move, EXERCISED rather than declared (Phase 1673) ---
    //
    // `move` existed on the Rust library surface from Phase 833 and on the C-ABI from
    // nowhere, so this decode-only projection could not reach it at all. These checks are
    // what make "a Kotlin session performs a move with one call" a measurement.
    runner.check("placement/move-relocates-a-node-and-keeps-its-id") {
        FuaranSession.create(NativeBridge, SEED_PLACEMENT).use { session ->
            val envelope = session.move(source = "a", parentId = "right", placement = Placement.Last)
            require(envelope.contains("\"MoveNode\"")) { "expected a MoveNode op, got: $envelope" }
            val tree = session.treeJson()
            require(childIds(tree, "left") == listOf("b")) {
                "the moved node should have left its old parent: ${childIds(tree, "left")}"
            }
            // Under its OWN id: a move mints nothing and remaps nothing. That is the whole
            // difference between this verb and a duplicate.
            require(childIds(tree, "right") == listOf("a")) {
                "the moved node should have arrived under its own id: ${childIds(tree, "right")}"
            }
        }
    }

    runner.check("placement/move-before-an-anchor-lands-in-position") {
        FuaranSession.create(NativeBridge, SEED_PLACEMENT).use { session ->
            session.move(source = "right", parentId = "left", placement = Placement.Before("b"))
            val ids = childIds(session.treeJson(), "left")
            require(ids == listOf("a", "right", "b")) { "expected [a, right, b], got $ids" }
        }
    }

    runner.check("placement/a-refused-move-is-typed-and-changes-nothing") {
        FuaranSession.create(NativeBridge, SEED_PLACEMENT).use { session ->
            val before = session.treeJson()
            val e =
                try {
                    session.move(source = "left", parentId = "left", placement = Placement.Last)
                    null
                } catch (e: FuaranException) {
                    e
                }
            require(e != null) { "moving a node into itself should have thrown" }
            require(e.code == "MoveIntoSelf") { "expected MoveIntoSelf, got ${e.code}" }
            require(session.treeJson() == before) { "a refused move must leave the held tree untouched" }
        }
    }

    runner.check("placement/a-quote-in-a-node-id-is-escaped-rather-than-breaking-the-request") {
        FuaranSession.create(NativeBridge, SEED_PLACEMENT).use { session ->
            // Built through this tier's JSON writer, so the core READS the document and judges
            // its content (the id is absent). Concatenated, the quote would close the string and
            // this would be a `request` parse error instead — a Kotlin-side defect wearing a
            // core-side error's clothes.
            val e =
                try {
                    session.move(source = "no\"such", parentId = "right", placement = Placement.Last)
                    null
                } catch (e: FuaranException) {
                    e
                }
            require(e != null) { "expected a refusal for an absent node" }
            require(e.code == "NodeNotFound") { "expected NodeNotFound, got ${e.code}" }
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
