// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
//
// `fuaran-ui` — the pure-JVM native Kotlin surface (sealed tree model +
// render-projection decoder) over the Rust reference core of the Fuaran UI wire format.
// Dependency-light by mandate: the JSON reader is hand-rolled, so there is no runtime
// dependency. Testable on any JVM without Android tooling.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
    // Emit Java-17 bytecode so an Android library (`:fuaran-renderer`, whose D8/desugar target is
    // JVM 17) can consume this module by project dependency. The toolchain still runs on JDK 21.
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Keep the (source-free) Java compile task's target in lockstep with Kotlin's 17 so Gradle's
// JVM-target-consistency check passes despite the JDK-21 toolchain.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(kotlin("test"))
}

// The corpus decode harness is `main()`-driven, not JUnit, so the `test` task
// never runs it — until now it was reachable only through `run.ps1`'s direct
// `kotlinc` build, i.e. only on a machine with kotlinc on PATH. This task makes
// it runnable by Gradle on any platform (notably CI) with no change to the
// harness itself: it exits non-zero on any failed check.
tasks.register<JavaExec>("corpusCheck") {
    group = "verification"
    description = "Decode every node-round-trip fixture in the shared wire-format corpus."
    mainClass.set("fuaran.ui.CorpusDecodeTestKt")
    classpath = sourceSets["test"].runtimeClasspath
    // The harness finds the corpus via -Dfuaran.corpus, $FUARAN_CORPUS, or a
    // sibling checkout — and SKIPS cleanly when it finds none. A caller that
    // means to GATE on it must therefore pass one of them (CI does, and asserts
    // the run was not a skip).
    System.getProperty("fuaran.corpus")?.let { systemProperty("fuaran.corpus", it) }
}
