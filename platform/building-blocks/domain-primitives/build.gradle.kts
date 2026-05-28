// Pure-Java value objects used across every service's domain layer.
// No Spring, no JPA, no infra. See `.claude/skills/java-architecture/SKILL.md` §3.

plugins {
    `java-library`
}

dependencies {
    testImplementation(libs.bundles.spring.test)
}
