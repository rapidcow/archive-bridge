plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// This module's package-info.java {@links} the other apple-notes-*
// modules' packages and vice versa, so no single-module `javadoc`
// task can ever resolve everything on its own - use the root
// project's aggregate `javadoc` task instead.
tasks.javadoc {
    enabled = false
}
