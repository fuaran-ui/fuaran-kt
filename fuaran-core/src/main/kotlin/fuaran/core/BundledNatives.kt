// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The desktop natives bundled in the `fuaran-core` JAR (the sqlite-jdbc pattern): the reference
 * core (`fuaran_rs`) and the JNI shim (`fuaran_jni`) for each platform the release workflow builds,
 * packed under `fuaran/core/natives/<platform>/`. A consumer on one of those platforms calls
 * [NativeBridge.loadBundled] and needs no native toolchain; a pure-decode consumer never calls it and
 * never pays for the natives at all beyond their bytes in the JAR.
 *
 * The two libraries are extracted to a fresh temporary directory and loaded core-first, because the
 * shim links against the core: loading the core by absolute path first means the dynamic linker finds
 * it already resident (Linux/macOS also carry an `$ORIGIN` / `@loader_path` rpath as a belt beside
 * those braces). Android is NOT this path — its per-ABI `.so`s ride the AAR's `jniLibs/` and load by
 * name through [NativeBridge.loadLibrary].
 */
object BundledNatives {
    /** The bundled layout's platform key for an OS + architecture pair, or `null` when no leg is built for it. */
    fun platformKey(osName: String, osArch: String): String? {
        val os = osName.lowercase()
        val arch = osArch.lowercase()
        val osKey =
            when {
                os.startsWith("windows") -> "windows"
                os.startsWith("mac") || os.contains("darwin") -> "macos"
                os.startsWith("linux") -> "linux"
                else -> return null
            }
        val archKey =
            when (arch) {
                "amd64", "x86_64", "x64" -> "x64"
                "aarch64", "arm64" -> "aarch64"
                else -> return null
            }
        return "$osKey-$archKey"
    }

    /** The library file names for a platform key, in load order: the core first, then the shim that links it. */
    fun libraryFileNames(platformKey: String): List<String> =
        when (platformKey.substringBefore('-')) {
            "windows" -> listOf("fuaran_rs.dll", "fuaran_jni.dll")
            "macos" -> listOf("libfuaran_rs.dylib", "libfuaran_jni.dylib")
            else -> listOf("libfuaran_rs.so", "libfuaran_jni.so")
        }

    /** The classpath resource path of one bundled library. */
    fun resourcePath(platformKey: String, fileName: String): String = "/fuaran/core/natives/$platformKey/$fileName"

    @Volatile
    private var loaded = false

    /**
     * Extract and load this platform's bundled natives; idempotent. Throws [IllegalStateException]
     * naming the missing resource when the JAR carries no leg for this platform — a consumer on such a
     * platform builds the shim itself and uses [NativeBridge.load] with the absolute path.
     */
    @Synchronized
    fun load() {
        if (loaded) return
        val platform =
            platformKey(System.getProperty("os.name") ?: "", System.getProperty("os.arch") ?: "")
                ?: throw IllegalStateException(
                    "fuaran-core bundles no natives for ${System.getProperty("os.name")} / ${System.getProperty("os.arch")}; " +
                        "build the JNI shim for this platform and call NativeBridge.load(absolutePath).",
                )
        val dir = Files.createTempDirectory("fuaran-core-natives-").toFile()
        dir.deleteOnExit()
        for (fileName in libraryFileNames(platform)) {
            val resource = resourcePath(platform, fileName)
            val stream =
                BundledNatives::class.java.getResourceAsStream(resource)
                    ?: throw IllegalStateException(
                        "fuaran-core carries no bundled native at $resource; " +
                            "this JAR was built without the $platform leg. " +
                            "Build the JNI shim for this platform and call NativeBridge.load(absolutePath).",
                    )
            val target = File(dir, fileName)
            stream.use { Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            target.deleteOnExit()
            System.load(target.absolutePath)
        }
        loaded = true
    }
}