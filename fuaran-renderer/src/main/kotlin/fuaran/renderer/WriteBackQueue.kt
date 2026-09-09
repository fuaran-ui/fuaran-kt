// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.renderer

import fuaran.ui.JsonValue
import java.util.concurrent.Executor

/**
 * The interaction host's per-key COALESCING write queue (Phase 1541): writes run on a worker, one at
 * a time, and a further edit to a key whose write has not started yet replaces it.
 *
 * **Compose-free and android-free, deliberately**, and listed by name in `run.ps1`'s neutral-renderer
 * set beside `Binding.kt`, `AccessibilityProjection.kt` and `TrendSentiment.kt`. The reason is the one
 * this repository already records for those three: the DECISIONS here — what coalesces, what does not,
 * what "settled" means — are ordinary logic, and typed in Compose vocabulary they would be provable
 * only on a box carrying the Android SDK, which the reference dev box is not. A decision testable on
 * only one machine is a decision nobody re-checks. Adding an `androidx` or `android.os` import to this
 * file breaks the plain-JVM build loudly, which is the guard.
 *
 * `FuaranHost` supplies the two callbacks; this type owns the queue and nothing else.
 *
 * @param worker where [perform] runs. **Single-threaded**, in the host's default: it keeps writes in
 *   the order they were made and means this queue only ever has one in-flight write to reason about.
 * @param perform performs one write. Runs on [worker]. It must NOT throw — the host wraps its own
 *   round trip in a `runCatching` — but a throw is absorbed rather than allowed to abandon the drain,
 *   because a queue left permanently "draining" would silently stop accepting work.
 * @param onPendingChanged reports whether anything is queued or in flight. Called on [worker]; the
 *   host posts it to the main thread.
 */
class WriteBackQueue(
    private val worker: Executor,
    private val perform: (key: String, value: JsonValue) -> Unit,
    private val onPendingChanged: (pending: Boolean) -> Unit,
) {
    /** State key to its LATEST unstarted value. `LinkedHashMap` so keys drain in the order first seen. */
    private val queued = LinkedHashMap<String, JsonValue>()
    private val lock = Any()
    private var draining = false

    /** Whether a write is queued or in flight. */
    val pending: Boolean
        get() = synchronized(lock) { queued.isNotEmpty() || draining }

    /**
     * Enqueue a write. Returns immediately.
     *
     * **Latest wins, per key.** A value replaced before it was ever sent is not a lost edit: no
     * reader of that slot could have observed it, because it never reached the session. Coalescing is
     * per KEY and never across keys — two slots are two facts, and dropping one because the other was
     * edited later would lose an edit rather than an intermediate.
     *
     * There is no timer. A single edit starts at once, so this debounces under load and adds no
     * latency when there is none — which is what a text field wants: a burst of keystrokes collapses,
     * and one considered edit is written immediately.
     */
    fun submit(key: String, value: JsonValue) {
        val startDrain =
            synchronized(lock) {
                queued[key] = value
                if (draining) {
                    false
                } else {
                    draining = true
                    true
                }
            }
        onPendingChanged(true)
        if (startDrain) worker.execute { drain() }
    }

    private fun drain() {
        while (true) {
            val next =
                synchronized(lock) {
                    val entry = queued.entries.firstOrNull()
                    if (entry == null) {
                        draining = false
                        null
                    } else {
                        queued.remove(entry.key)
                        entry.key to entry.value
                    }
                } ?: break
            try {
                perform(next.first, next.second)
            } catch (t: Throwable) {
                // `perform` owns its own failure reporting; absorbing here only keeps the DRAIN
                // alive. An escape would leave `draining` true forever — the queue would go on
                // accepting writes and never run one again, which is a worse outcome than the throw
                // it was carrying. An `Error` still propagates: an OOM or a linkage failure is not a
                // write that went wrong.
                if (t is Error) throw t
            }
        }
        // Read the queue rather than asserting `false`: a write submitted while this drain was
        // finishing starts its own, and a flat `false` from here would report "settled" over it.
        onPendingChanged(pending)
    }
}
