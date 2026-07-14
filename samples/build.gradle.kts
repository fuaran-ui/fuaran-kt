// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
//
// `samples` — a minimal Android sample app driving a live Fuaran tree end-to-end (Phase 545). It
// composes the whole surface: the render floor (`:fuaran-renderer`) + tone bridge, the interaction
// host (`FuaranHost` / `InteractiveFuaranTree`), the confined session (`:fuaran-core` JNI binding),
// and the server-driven driver (`:fuaran-driver`). `assembleDebug` proves it compiles; an on-device
// run drives the loop against a fixture server (the native `.so` is provided by the AAR / jniLibs on
// device — see `run.ps1 -Package`; this build does not require it).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "fuaran.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "fuaran.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":fuaran-ui"))
    implementation(project(":fuaran-renderer"))
    implementation(project(":fuaran-driver"))
    implementation(project(":fuaran-core"))

    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
}
