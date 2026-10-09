plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

val protobufVersion = libs.versions.protobuf.asProvider().get()
val protobufJavadocVersion = "4.35.1"

repositories {
    mavenCentral()
}

dependencies {
    api(libs.protobuf.java)
}

protobuf {
    protoc {
        // The com.google.protobuf:protoc artifact ships no FreeBSD
        // binary, so Gradle's download-the-artifact path 404s there.
        // Fall back to whatever `protoc` is on PATH (e.g. the
        // FreeBSD ports/pkg build) instead.
        if (System.getProperty("os.name").contains("BSD", ignoreCase = true)) {
            path = "protoc"
        } else {
            artifact = "com.google.protobuf:protoc:${protobufVersion}"
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
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
