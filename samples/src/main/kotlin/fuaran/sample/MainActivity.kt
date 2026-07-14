// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fuaran.core.NativeBridge
import fuaran.driver.HttpUrlTransport
import fuaran.driver.Rendered
import fuaran.driver.Rejected
import fuaran.driver.ServerDrivenDriver
import fuaran.renderer.FuaranHost
import fuaran.renderer.FuaranNode
import fuaran.renderer.FuaranTheme
import fuaran.renderer.InteractiveFuaranTree
import fuaran.ui.FuaranSession
import fuaran.ui.TreeSession
import fuaran.ui.decodeNode

/**
 * The Phase 545 end-to-end sample: a live Fuaran tree rendered through the Material tone bridge with
 * interaction wired back through the certified Rust session.
 *
 * On a device (where the JNI `.so` is packaged) it brings up a [FuaranSession] over the seed tree and
 * renders it through [InteractiveFuaranTree], so a control interaction round-trips through the Rust
 * core and recomposes. When the native library is unavailable (e.g. a bare `assembleDebug` with no
 * jniLibs) it degrades to a **static render** of the same seed — still a real Fuaran projection, just
 * without live mutation — so the sample always builds and always shows something meaningful.
 *
 * The server-driven (SDUI) path is shown by [driveFromServer]: point it at a fixture server and the
 * [ServerDrivenDriver] fetches the initial tree, applies streamed ops against the session, and posts
 * interaction events back.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FuaranTheme {
                SampleScreen()
            }
        }
    }
}

/** The seed dashboard tree, as canonical wire JSON — a heading, a metric, and an interactive button. */
private val SEED_TREE =
    """
    {"id":"root","kind":{"${'$'}type":"Box","role":"Dashboard","layout":{"${'$'}type":"Flex","direction":"Vertical","wrap":false,"gap":8},
      "children":[
        {"id":"title","kind":{"${'$'}type":"Heading","level":1,"variant":"Standard","text":{"${'$'}type":"Literal","text":"Fuaran Sample"}}},
        {"id":"rev","kind":{"${'$'}type":"Metric","emphasis":"Loud","format":{"${'$'}type":"Currency","code":"GBP"},
          "label":{"${'$'}type":"Literal","text":"Revenue"},"source":{"${'$'}type":"Static","value":1234.5},"tone":"Brand","weight":"Standard"}},
        {"id":"refresh","kind":{"${'$'}type":"Button","variant":"Primary","label":{"${'$'}type":"Literal","text":"Refresh"},
          "onClick":{"${'$'}type":"SetState","key":"n","payload":1}}}
      ]}}
    """.trimIndent()

@Composable
private fun SampleScreen() {
    // Try to bring up a live session (needs the packaged native .so); fall back to a static render.
    val host = remember { tryCreateLiveHost() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        if (host != null) {
            InteractiveFuaranTree(host)
            host.lastError?.let { Text("⚠ ${it.code}: ${it.message}", Modifier.padding(top = 8.dp)) }
        } else {
            // Native session unavailable — render the seed statically (decode-only, no mutation).
            Text("(static render — native session not loaded)", Modifier.padding(bottom = 8.dp))
            FuaranNode(decodeNode(SEED_TREE))
        }
    }
}

/** Load the JNI shim and open a confined session over the seed, or `null` when native is absent. */
private fun tryCreateLiveHost(): FuaranHost? =
    try {
        NativeBridge.loadLibrary("fuaran_jni")
        FuaranHost(FuaranSession.create(NativeBridge, SEED_TREE))
    } catch (_: Throwable) {
        null
    }

/**
 * Drive the sample from a server-driven (SDUI) fixture: fetch the initial tree, apply the streamed op
 * stream against a live session, and re-project after each. Call from a background thread (the
 * transport blocks). Returns the number of successful re-projections; a reject is surfaced inline and
 * the loop survives.
 */
fun driveFromServer(baseUrl: String, sessionFactory: (String) -> TreeSession = { FuaranSession.create(NativeBridge, it) }): Int {
    var rendered = 0
    ServerDrivenDriver(HttpUrlTransport(baseUrl), sessionFactory).run { state ->
        when (state) {
            is Rendered -> rendered++
            is Rejected -> { /* surface state.error.code in the UI's error banner */ }
            else -> {}
        }
    }
    return rendered
}
