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
