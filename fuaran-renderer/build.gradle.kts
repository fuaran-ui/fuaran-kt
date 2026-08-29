// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
//
// `fuaran-renderer` — the Jetpack Compose render floor over the `:fuaran-ui` sealed tree model
// (Phase 544). A pure projection of the decoded model into composables — no wire-JSON parsing
// lives here (decode happens first, in `:fuaran-ui`). Android library so it can host Compose; the
// render-coverage gate runs headlessly on the JVM under Robolectric (no emulator).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "fuaran.renderer"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // Robolectric needs the merged Android resources + a real android.jar on the unit-test
        // classpath so Compose can compose headlessly on the JVM.
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all { test ->
                // Point the render-coverage gate at the shared wire-format corpus by absolute path
                // (working-dir-independent): <fuaran-kt>/../wire-format-fixtures.
                test.systemProperty(
                    "fuaran.corpus",
                    rootProject.projectDir.parentFile.resolve("wire-format-fixtures").absolutePath,
                )
                // Gradle does not hand the ambient environment to a test JVM, so the
                // render-obligation gate's artefact override has to be forwarded explicitly.
                // It exists so the gate's go-red property can be PROVEN against a perturbed
                // scratch copy of `render-fidelity.json` without writing to the shared corpus,
                // which is the oracle and is never edited to make this surface pass.
                System.getenv("FUARAN_RENDER_FIDELITY")?.let {
                    test.environment("FUARAN_RENDER_FIDELITY", it)
                }
            }
        }
    }
}

// Robolectric composes against the ComponentActivity that `ui-test-manifest` merges into the
// DEBUG manifest (debugImplementation below — it must not ship in the release AAR). The release
// unit-test variant therefore has no test activity and every Compose test fails with "Unable to
// resolve activity"; the JVM/Robolectric gate is variant-independent, so run it once, on debug.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enableUnitTest = false }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":fuaran-ui"))

    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // Render-coverage gate — headless Compose under Robolectric.
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // The empty-activity manifest that hosts the Compose test — must merge into the (debug) manifest
    // that Robolectric loads for local unit tests, hence debugImplementation, not testImplementation.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}
