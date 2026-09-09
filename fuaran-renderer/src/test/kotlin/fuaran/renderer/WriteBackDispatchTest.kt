// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import fuaran.ui.FuaranException
import fuaran.ui.JsonString
import fuaran.ui.TreeSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1541 — where a write-back RUNS.
 *
 * This is the ONE claim about the write path that needs a host: that `FuaranHost.writeBack` returns
 * without waiting for the session round trip, and that the round trip does not happen on the caller's
 * thread. It is what `WriteBackGapTest` beside it cannot see — that one asks only whether the value
 * arrived, and a synchronous write satisfies it perfectly while stalling every keystroke.
 *
 * The QUEUE's decisions — what coalesces, what does not, what "settled" means — are asserted in
 * `WriteBackQueueHarness`, which is platform-neutral and runs in the plain-JVM gate, so they are
 * re-checkable on whatever machine they are changed from rather than only on one carrying the Android
 * SDK.
 *
 * The session here BLOCKS until released, which is the only way to tell a synchronous call from an
 * asynchronous one: a fast fake finishes before any assertion could distinguish them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WriteBackDispatchTest {
    private fun md(text: String): String =
        """{"id":"root","kind":{"${'$'}type":"Markdown","text":{"${'$'}type":"Literal","text":"$text"}}}"""

    /** A session whose `setState` waits on a latch and records the thread it ran on. */
    private class BlockingSession(private val current: String) : TreeSession {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)

        @Volatile
        var writeThreadName: String? = null

        override fun treeJson(): String = current

        override fun projectResolved(): String = current

        override fun applyOp(opJson: String) {
            throw FuaranException("UNSUPPORTED", null, null, "this fake carries no apply engine")
        }

        override fun setState(key: String, valueJson: String) {
            writeThreadName = Thread.currentThread().name
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }

        override fun setFilter(key: String, valueJson: String) = Unit

        override fun setQuery(key: String, valueJson: String) = Unit

        override fun close() = Unit
    }

    @Test
    fun writeBackReturnsWithoutWaitingForTheSessionAndRunsOffTheCallersThread() {
        val session = BlockingSession(md("Hello"))
        val worker = Executors.newSingleThreadExecutor()
        val host = FuaranHost.start(session, worker, FuaranHost.DirectExecutor)
        val callerThread = Thread.currentThread().name

        val startedAt = System.nanoTime()
        host.writeBack("slot", JsonString("a"))
        val returnedAfterMillis = (System.nanoTime() - startedAt) / 1_000_000

        // The session is INSIDE `setState` and holding, and the call to `writeBack` has already
        // returned. Before Phase 1541 this line was unreachable: `writeBack` performed the session
        // call, `projectResolved()` and `decodeNode` inline, so it would still be blocked here — on
        // Compose that is the main thread, and on a live JNI session it is the main thread waiting on
        // the core's own confining executor.
        assertTrue("the write must have started on another thread", session.entered.await(5, TimeUnit.SECONDS))
        assertTrue(
            "writeBack must not wait for the round trip; it took $returnedAfterMillis ms",
            returnedAfterMillis < 1_000,
        )
        assertTrue("a write in flight is reported as pending", host.writesPending)
        assertNotEquals(
            "the session round trip must not run on the caller's thread",
            callerThread,
            session.writeThreadName,
        )

        session.release.countDown()
        worker.shutdown()
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
    }
}
