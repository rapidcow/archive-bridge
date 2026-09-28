plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(libs.junit.jupiter)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Apply a specific Java toolchain to ease working on different environments.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
}

// This module's package-info.java {@links} apple-notes-proto's
// packages and vice versa, so no single-module `javadoc` task can
// ever resolve everything on its own - use the root project's
// aggregate `javadoc` task instead.
tasks.javadoc {
    enabled = false
}
