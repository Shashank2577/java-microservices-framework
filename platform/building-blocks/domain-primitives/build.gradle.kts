// Pure-Java value objects used across every service's domain layer.
// No Spring, no JPA, no infra. See `.claude/skills/java-architecture/SKILL.md` §3.

plugins {
    `java-library`
    alias(libs.plugins.pitest)
}

dependencies {
    // Pure-test bundle — JUnit + AssertJ + Mockito. NO Spring; this module must not
    // pull a framework dependency into the domain layer.
    testImplementation(libs.bundles.pure.test)
}

// Mutation testing — the real coverage signal per `.claude/skills/java-code-quality` §8
// and `java-testing/SKILL.md` §8. Domain/application packages must hit ≥ 85%; domain
// primitives are simpler so we hold the bar high (the framework rule applies symmetrically).
pitest {
    junit5PluginVersion.set(libs.versions.pitest.junit5)
    targetClasses.set(listOf("com.example.platform.domain.primitives.*"))
    threads.set(2)
    mutators.set(listOf("STRONGER"))
    mutationThreshold.set(85)
    coverageThreshold.set(95)
    timestampedReports.set(false)
    outputFormats.set(listOf("HTML", "XML"))
}
