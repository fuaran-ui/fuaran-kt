// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
//
// Root Gradle build. Declares the plugin versions for the whole graph (applied per-module).
// Kotlin is pinned to 2.4.0 (matching the `kotlinc` the `run.ps1` Stage-0 build uses); the
// Compose compiler is the built-in `org.jetbrains.kotlin.plugin.compose` plugin, version-locked
// to Kotlin 2.x (so there is no separate compose-compiler-vs-Kotlin matrix to reconcile). AGP
// 8.13.x pairs with Gradle 8.14.x and supports compileSdk 36.
plugins {
    kotlin("jvm") version "2.4.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("com.android.library") version "8.13.2" apply false
}

allprojects {
    group = "io.fuaran"
    version = "0.1.0"
}
