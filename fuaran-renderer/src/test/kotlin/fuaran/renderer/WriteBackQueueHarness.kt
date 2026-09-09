// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import fuaran.ui.JsonString
import fuaran.ui.JsonValue
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The write-back queue's DECISIONS, asserted in the plain-JVM gate (Phase 1541).
 *
 * Same split and the same reason as the accessibility and trend-sentiment harnesses beside it: what
 * coalesces, what does not, and what "settled" means are ordinary logic over a queue, and typed in
 * Compose vocabulary they would be checkable only on the box carrying the Android SDK. The Robolectric
 * leg (`WriteBackDispatchTest`) keeps only what it alone can answer — that the host's round trip
 * genuinely leaves the caller's thread.
 *
 * A `main`-driven runner rather than JUnit, matching the sibling harnesses: the repo builds with a bare
 * `kotlinc` and no artefact resolution, so the exit code is the gate.
 */

/** How many checks [writeBackQueueFailures] runs — reported so a leg that shrank is visible. */
var writeBackQueueChecksRun = 0
    private set

/** Runs work on the calling thread — the deterministic half of these checks. */
private val direct = Executor { it.run() }

internal fun writeBackQueueFailures(): List<String> {
    val c = SentimentChecks()

    c.check("aSingleWriteRunsImmediatelyAndSettles") {
        // No timer: one considered edit is written at once. A debounce that always waits would add
        // latency to the case that has none.
        val seen = mutableListOf<Pair<String, JsonValue>>()
        val pending = mutableListOf<Boolean>()
        val q = WriteBackQueue(direct, { k, v -> seen.add(k to v) }, { pending.add(it) })
        q.submit("slot", JsonString("a"))
        c.eq("performed", listOf<Pair<String, JsonValue>>("slot" to JsonString("a")), seen)
        c.eq("settles", false, q.pending)
        c.eq("reported pending, then settled", listOf(true, false), pending)
    }

    c.check("writesToOneKeyCoalesceWhileOneIsInFlightAndTheLatestWins") {
        // The queue is driven with a worker that does NOT start until released, so three edits arrive
        // while the first is unstarted — which is the only arrangement in which coalescing is
        // observable at all.
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val q =
            WriteBackQueue(
                worker,
                { key, value ->
                    started.countDown()
                    gate.await(5, TimeUnit.SECONDS)
                    seen.add("$key=${(value as JsonString).value}")
                },
                {},
            )

        q.submit("slot", JsonString("a"))
        if (!started.await(5, TimeUnit.SECONDS)) error("the first write never started")
        q.submit("slot", JsonString("b"))
        q.submit("slot", JsonString("c"))
        q.submit("slot", JsonString("d"))
        // A DIFFERENT key must NOT be collapsed away: two slots are two facts, and dropping one
        // because the other was edited later would lose an edit rather than an intermediate.
        q.submit("other", JsonString("z"))
        gate.countDown()
        worker.shutdown()
        if (!worker.awaitTermination(5, TimeUnit.SECONDS)) error("the drain did not finish")

        // `b` and `c` were superseded before they were ever sent — not lost edits, because no reader
        // of that slot could have observed a value that never reached the session.
        c.eq("coalesced", listOf("slot=a", "slot=d", "other=z"), seen.toList())
    }

    c.check("aThrowingWriteDoesNotAbandonTheQueue") {
        // A queue left permanently `draining` would go on accepting writes and never run one again —
        // a worse outcome than the throw it was carrying, and a silent one.
        val seen = mutableListOf<String>()
        val q =
            WriteBackQueue(
                direct,
                { key, _ ->
                    if (key == "bad") error("the host's perform blew up")
                    seen.add(key)
                },
                {},
            )
        q.submit("bad", JsonString("x"))
        q.submit("good", JsonString("y"))
        c.eq("the queue kept running", listOf("good"), seen)
        c.eq("and is settled", false, q.pending)
    }

    c.check("pendingIsTrueWhileAWriteIsQueued") {
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val q = WriteBackQueue(worker, { _, _ -> started.countDown(); gate.await(5, TimeUnit.SECONDS) }, {})
        q.submit("slot", JsonString("a"))
        if (!started.await(5, TimeUnit.SECONDS)) error("the write never started")
        c.eq("in flight is pending", true, q.pending)
        gate.countDown()
        worker.shutdown()
        if (!worker.awaitTermination(5, TimeUnit.SECONDS)) error("the drain did not finish")
        c.eq("settled afterwards", false, q.pending)
    }

    writeBackQueueChecksRun = c.passed + c.failures.size
    return c.failures
}

fun main() {
    println("== write-back queue :: coalescing + settlement (platform-neutral) ==")
    val failures = writeBackQueueFailures()
    if (failures.isEmpty()) {
        println("PASS: $writeBackQueueChecksRun write-back queue checks green — latest-wins per key, and the drain survives a throw.")
    } else {
        println("FAIL: ${failures.size} write-back queue check(s) failed")
        failures.forEach { println("  - $it") }
        kotlin.system.exitProcess(1)
    }
}
