// Root build — applies to every sub-project. Per-module specifics live in each module.
// See `.claude/skills/java-stack/SKILL.md` and `java-code-quality/SKILL.md`.

plugins {
    // Apply versions; sub-projects opt in via `plugins { alias(libs.plugins.x) }`.
    alias(libs.plugins.spotless)        apply false
    alias(libs.plugins.errorprone)      apply false
    alias(libs.plugins.spotbugs)        apply false
    alias(libs.plugins.dep-check)       apply false
    alias(libs.plugins.pitest)          apply false
}

allprojects {
    group = "com.example.platform"
    version = providers.gradleProperty("project.version").getOrElse("0.1.0-SNAPSHOT")
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "jacoco")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
            vendor = JvmVendorSpec.ADOPTIUM
        }
        withSourcesJar()
        withJavadocJar()
    }

    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/**/*.java")
            googleJavaFormat("1.24.0")
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
            importOrder("java", "javax", "jakarta", "org.springframework", "com.example", "")
        }
        kotlinGradle { ktlint() }
    }

    extensions.configure<JacocoPluginExtension> {
        toolVersion = "0.8.12"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        finalizedBy(tasks.named("jacocoTestReport"))
        systemProperty("user.timezone", "UTC")
        // Keep tests deterministic.
        systemProperty("java.util.logging.config.file", "/dev/null")
    }

    // Jacoco coverage gate — 95% line / 90% branch on changed code is the framework rule
    // (java-code-quality §7, java-testing §8). domain/application packages have a tighter 98% line bar
    // and are enforced where present.
    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification").configure {
        violationRules {
            rule {
                element = "BUNDLE"
                limit { counter = "LINE";   minimum = "0.95".toBigDecimal() }
                limit { counter = "BRANCH"; minimum = "0.90".toBigDecimal() }
            }
            rule {
                element = "PACKAGE"
                includes = listOf("com.example.*.domain.*", "com.example.*.application.*")
                limit { counter = "LINE"; minimum = "0.98".toBigDecimal() }
            }
        }
    }

    tasks.named("check") {
        dependsOn("spotlessCheck", "jacocoTestCoverageVerification")
    }
}

// Aggregate task: validate the framework artefacts (skills + vault) plus normal build.
tasks.register("validateFramework") {
    group = "verification"
    description = "Run the framework cross-skill consistency validator."
    doLast {
        val python = providers.environmentVariable("PYTHON").getOrElse("python3")
        val result = exec {
            commandLine(python, "tools/validate_framework.py")
        }
        if (result.exitValue != 0) throw GradleException("framework validator failed")
    }
}
