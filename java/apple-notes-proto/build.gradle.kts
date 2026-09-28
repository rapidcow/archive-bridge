plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

// Need to explicitly specify that we want to reference protobuf
// itself, since "protobuf" is a substring of "protobuf-plugin".
// See <https://docs.gradle.org/9.8.0/userguide/version_catalogs.html#sec:avoiding_subgroup_accessors>
val protobufVersion = libs.versions.protobuf.asProvider().get()

repositories {
    mavenCentral()
}

dependencies {
    api(libs.protobuf.java)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${protobufVersion}"
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// This module's package-info.java {@links} apple-notes-drawing's
// packages and vice versa, so no single-module `javadoc` task can
// ever resolve everything on its own - use the root project's
// aggregate `javadoc` task instead.
tasks.javadoc {
    enabled = false
}

// protoc copies each field's own comment onto its has/get/set/clear
// accessors, but never comments the generic runtime plumbing (parse
// factories, descriptor accessors, FIELD_NUMBER constants, ...) it
// emits identically on every message class. gradle.build.ProtoJavadoc
// fills that in with canned javadoc, matched by signature shape, so it
// applies to every proto-generated class here and in future modules.
tasks.matching { it.name.startsWith("generate") && it.name.endsWith("Proto") }.configureEach {
    val protoSourcesDir = layout.buildDirectory.dir("generated/sources/proto").get().asFile
    doLast {
        org.rapidcow.gradle.build.ProtoJavadoc.documentGeneratedSources(protoSourcesDir)
    }
}
