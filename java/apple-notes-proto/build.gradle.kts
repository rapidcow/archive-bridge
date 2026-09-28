plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

// Need to explicitly specify that we want to reference protobuf
// itself, since "protobuf" is a substring of "protobuf-plugin".
// See <https://docs.gradle.org/9.8.0/userguide/version_catalogs.html#sec:avoiding_subgroup_accessors>
val protobufVersion = libs.versions.protobuf.asProvider().get()
// The most recent available documentation is 4.35.1, for some reason?
val protobufJavadocVersion = "4.35.1"

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

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        links("https://javadoc.io/doc/com.google.protobuf/protobuf-java/${protobufJavadocVersion}/");
    }
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
