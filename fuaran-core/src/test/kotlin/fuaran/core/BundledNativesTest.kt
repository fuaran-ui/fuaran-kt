// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The bundled-natives layout is a CONTRACT between the release workflow (which stages
 * `natives/<platform>/<file>`) and the loader (which looks the same path up on the classpath);
 * these pin both halves. The load itself is proven only where the natives are present — the release
 * workflow runs this class with `FUARAN_EXPECT_BUNDLED=1` after staging the linux-x64 leg, so a JAR
 * whose loader cannot bind its own shim never reaches the registry. Without that variable the
 * absence must be NAMED, never swallowed: a loader that silently did nothing is the failure that
 * reaches a consumer as an `UnsatisfiedLinkError` three calls later.
 */
class BundledNativesTest {
    @Test
    fun platformKeysMatchTheWorkflowMatrix() {
        assertEquals("linux-x64", BundledNatives.platformKey("Linux", "amd64"))
        assertEquals("windows-x64", BundledNatives.platformKey("Windows 11", "amd64"))
        assertEquals("macos-x64", BundledNatives.platformKey("Mac OS X", "x86_64"))
        assertEquals("macos-aarch64", BundledNatives.platformKey("Mac OS X", "aarch64"))
        assertEquals("windows-aarch64", BundledNatives.platformKey("Windows 11", "aarch64"))
        assertNull(BundledNatives.platformKey("FreeBSD", "amd64"))
        assertNull(BundledNatives.platformKey("Linux", "riscv64"))
    }

    @Test
    fun libraryNamesAreCoreFirstAndPlatformSpelled() {
        assertEquals(listOf("fuaran_rs.dll", "fuaran_jni.dll"), BundledNatives.libraryFileNames("windows-x64"))
        assertEquals(listOf("libfuaran_rs.so", "libfuaran_jni.so"), BundledNatives.libraryFileNames("linux-x64"))
        assertEquals(listOf("libfuaran_rs.dylib", "libfuaran_jni.dylib"), BundledNatives.libraryFileNames("macos-aarch64"))
        assertEquals("/fuaran/core/natives/linux-x64/libfuaran_rs.so", BundledNatives.resourcePath("linux-x64", "libfuaran_rs.so"))
    }

    @Test
    fun loadBindsTheShimWhereBundledAndNamesTheGapWhereNot() {
        val expectBundled = System.getenv("FUARAN_EXPECT_BUNDLED") == "1"
        if (expectBundled) {
            NativeBridge.loadBundled()
            // A native call that needs no session: the shim symbol bound, and the core answered.
            val err = FuaranNative.lastError()
            assertTrue("lastError() answers on a fresh thread", err.isEmpty())
            // Idempotent — a second call must not re-extract or re-load.
            NativeBridge.loadBundled()
        } else {
            try {
                NativeBridge.loadBundled()
                fail("loadBundled() succeeded on a JAR that should carry no natives for this platform — set FUARAN_EXPECT_BUNDLED=1 if it does")
            } catch (e: IllegalStateException) {
                assertTrue("the failure names the gap: ${e.message}", e.message!!.contains("fuaran-core"))
                assertTrue("the failure names the remedy: ${e.message}", e.message!!.contains("NativeBridge.load(absolutePath)"))
            }
        }
    }
}