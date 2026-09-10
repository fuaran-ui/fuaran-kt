// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
//
// Root Gradle build. Declares the plugin versions for the whole graph (applied per-module).
// Kotlin is pinned to 2.4.0 (matching the `kotlinc` the `run.ps1` Stage-0 build uses); the
// Compose compiler is the built-in `org.jetbrains.kotlin.plugin.compose` plugin, version-locked
// to Kotlin 2.x (so there is no separate compose-compiler-vs-Kotlin matrix to reconcile). AGP
// 8.13.x pairs with Gradle 8.14.x and supports compileSdk 36.
import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    kotlin("jvm") version "2.4.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("com.android.library") version "8.13.2" apply false
    // Maven Central publication through Sonatype's Central Portal — applied per published module.
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

allprojects {
    group = "io.fuaran"
    version = "0.1.0"
}

// Maven Central — ONE identity for every published module. A module that publishes applies the
// plugin and names only its own artifact shape (`KotlinJvm` for the pure-JVM modules, the release
// AAR for `:fuaran-renderer`); the coordinates, licence, developer and SCM blocks are the same for
// all of them and live here so no module can publish under a divergent identity. `:samples`
// applies nothing and is not published. The release itself is `.github/workflows/publish-maven-central.yml`:
// a `v*` tag publishes; a `workflow_dispatch` run is always a dry run to the runner's Maven local.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension>("mavenPublishing") {
            // The Central Portal, with automatic release: a bundle the portal validates goes live
            // without a second click, which is how the estate's other registries publish on a tag.
            publishToMavenCentral(true)
            // Central refuses an unsigned bundle at upload, so signing is not optional for a release
            // — but a dry run, or a `publishToMavenLocal` on a machine holding no key, must still
            // work. The key arrives as the Gradle properties `signingInMemoryKey` /
            // `signingInMemoryKeyPassword` (the workflow sets them from secrets as
            // ORG_GRADLE_PROJECT_*), and signing is wired only when it is present.
            if (project.hasProperty("signingInMemoryKey")) {
                signAllPublications()
            }
            coordinates(project.group.toString(), project.name, project.version.toString())
            pom {
                name.set(project.name)
                description.set(
                    "Native Kotlin surface of the Fuaran UI wire format, over the Rust reference core — " +
                        "module ${project.name}.",
                )
                url.set("https://github.com/fuaran-ui/fuaran-kt")
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("ajwillshire")
                        name.set("Andrew J. Willshire")
                        email.set("andrew@fuaran.com")
                        organization.set("Diametrical Ltd")
                    }
                }
                scm {
                    url.set("https://github.com/fuaran-ui/fuaran-kt")
                    connection.set("scm:git:https://github.com/fuaran-ui/fuaran-kt.git")
                    developerConnection.set("scm:git:ssh://git@github.com/fuaran-ui/fuaran-kt.git")
                }
            }
        }
    }
}
