plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

val fractionVersion = libs.versions.commons.numbers.fraction.get()
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
    // Fraction (org.apache.commons.numbers.fraction) appears in
    // ChannelReading's public API surface, so this has to be `api',
    // not `implementation' - consumers need it on their own compile
    // classpath too.
    api(libs.commons.numbers.fraction)

    testImplementation(libs.junit.jupiter)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${protobufVersion}"
    }
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

// StrokeData now lives in this same module (see package-info.java for
// why), so ordinary javadoc resolves it without any cross-module
// -linkoffline/-sourcepath trick - just the external link to
// protobuf-java's own generic runtime types (Message, ByteString, ...).
tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        charSet = "UTF-8"
        docEncoding = "UTF-8"
        links("https://commons.apache.org/proper/commons-numbers/commons-numbers-fraction/javadocs/api-${fractionVersion}/")
        links("https://javadoc.io/doc/com.google.protobuf/protobuf-java/${protobufJavadocVersion}/")
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
