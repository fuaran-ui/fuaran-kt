// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.core

import fuaran.ui.FuaranNativeBridge

/**
 * The concrete JNI-backed [FuaranNativeBridge] — a thin Kotlin adapter over the static
 * [FuaranNative] JNI declarations. Keeping the seam here (rather than in `fuaran-ui`)
 * keeps the pure-JVM decoder module free of any native dependency; `fuaran-core`
 * supplies the platform binding.
 *
 * Load the JNI shim once before creating a session:
 * `NativeBridge.load("/abs/path/fuaran_jni.dll")` (desktop test leg) or
 * `NativeBridge.loadLibrary("fuaran_jni")` (packaged / Android).
 */
object NativeBridge : FuaranNativeBridge {
    fun load(absolutePath: String) = FuaranNative.load(absolutePath)

    fun loadLibrary(name: String) = FuaranNative.loadLibrary(name)

    override fun sessionNew(nodeJson: ByteArray): Long = FuaranNative.sessionNew(nodeJson)

    override fun lastError(): ByteArray = FuaranNative.lastError()

    override fun sessionFree(handle: Long) = FuaranNative.sessionFree(handle)

    override fun sessionRender(handle: Long): ByteArray = FuaranNative.sessionRender(handle)

    override fun sessionTreeJson(handle: Long): ByteArray = FuaranNative.sessionTreeJson(handle)

    override fun sessionProjectResolved(handle: Long): ByteArray = FuaranNative.sessionProjectResolved(handle)

    override fun sessionResolvedRows(handle: Long, nodeId: ByteArray): ByteArray =
        FuaranNative.sessionResolvedRows(handle, nodeId)

    override fun sessionApplyOp(handle: Long, opJson: ByteArray): ByteArray = FuaranNative.sessionApplyOp(handle, opJson)

    override fun sessionSetState(handle: Long, key: ByteArray, value: ByteArray): ByteArray =
        FuaranNative.sessionSetState(handle, key, value)

    override fun sessionSetFilter(handle: Long, key: ByteArray, value: ByteArray): ByteArray =
        FuaranNative.sessionSetFilter(handle, key, value)

    override fun sessionSetQuery(handle: Long, key: ByteArray, value: ByteArray): ByteArray =
        FuaranNative.sessionSetQuery(handle, key, value)
}
