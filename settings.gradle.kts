pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Foojay toolchain resolver — lets Gradle auto-download Temurin JDKs when the local
// environment lacks an exact match. Without this, the `JvmVendorSpec.ADOPTIUM`
// requirement in the toolchain config fails on machines with non-Temurin JDKs
// (e.g., a Homebrew OpenJDK).
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://packages.confluent.io/maven/")    // Confluent Avro serializers
    }
    // gradle/libs.versions.toml is auto-detected by Gradle 8 as the `libs` catalog —
    // no manual `versionCatalogs { create("libs") { from(...) } }` needed.
}

rootProject.name = "java-microservices-framework"

// Platform — shared building blocks (plain libraries; no Spring auto-config).
include(":platform:building-blocks:domain-primitives")
// include(":platform:building-blocks:error-model")     // sprint 2
// include(":platform:building-blocks:outbox")          // sprint 2
// include(":platform:building-blocks:test-support")    // sprint 2

// Platform — Spring Boot starters (auto-config; depend on these to inherit framework defaults).
// include(":platform:starters:web-starter")            // sprint 2
// include(":platform:starters:persistence-starter")    // sprint 2
// include(":platform:starters:messaging-starter")      // sprint 2
// include(":platform:starters:security-starter")       // sprint 2
// include(":platform:starters:observability-starter")  // sprint 2

// Services — copy starter-service to bootstrap a new bounded context.
// include(":services:starter-service")                 // sprint 2
