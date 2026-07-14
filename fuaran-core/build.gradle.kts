// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
//
// `fuaran-core` — the C-ABI JNI binding under the Kotlin surface: the hand-written JNI
// shim over the fuaran-rs `fuaran_*` session surface, plus the native packaging (a
// desktop `.dll`/`.so`/`.dylib` for tests; Android per-ABI `.so`s in `jniLibs/` via
// cargo-ndk). Depends on `fuaran-ui` for the render-projection decoder the round-trip
// test re-projects into. (Phase 543; wired into the Gradle graph at Phase 545.)
import java.io.File

plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":fuaran-ui"))
    testImplementation(kotlin("test"))
    testImplementation(project(":fuaran-ui"))
    // The live-session interaction round-trip drives the same server-driven driver over the real core.
    testImplementation(project(":fuaran-driver"))
    testImplementation("junit:junit:4.13.2")
}

// The live-session interaction round-trip (`InteractionRoundTripTest`) needs the desktop native shim
// (`fuaran_jni.dll` + its `fuaran_rs.dll` dependency), built by `dev-scripts/build-native-desktop.ps1`.
// Its absolute path is supplied via `-Pfuaran.lib=<abs>` (or the `FUARAN_LIB` env var); the test
// **cleanly skips** (JUnit `assumeTrue`) when it is unset, so `:fuaran-core:test` stays green on a box
// without the Rust toolchain. When set, we forward it as a system property and put the shim's directory
// on both `java.library.path` and `PATH` so the dependent `fuaran_rs.dll` resolves at load.
tasks.test {
    val libProp = providers.gradleProperty("fuaran.lib").orElse(providers.environmentVariable("FUARAN_LIB"))
    if (libProp.isPresent) {
        val lib = libProp.get()
        systemProperty("fuaran.lib", lib)
        val dir = File(lib).parent
        if (dir != null) {
            systemProperty("java.library.path", dir)
            environment("PATH", dir + File.pathSeparator + (System.getenv("PATH") ?: ""))
        }
    }
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}
