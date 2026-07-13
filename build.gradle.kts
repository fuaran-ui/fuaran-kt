// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
//
// Root Gradle scaffold (see settings.gradle.kts). Kotlin is pinned to 2.x to match the
// `kotlinc` the `run.ps1` build uses. Applied per-module.
plugins {
    kotlin("jvm") version "2.4.0" apply false
}

allprojects {
    group = "io.fuaran"
    version = "0.1.0"
}
