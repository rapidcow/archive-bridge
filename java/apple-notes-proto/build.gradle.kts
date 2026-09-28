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

// This module's package-info.java {@links} apple-notes-drawing's
// packages, which apple-notes-drawing's own package-info.java {@links}
// right back --- so this can't depend on apple-notes-drawing's own
// `javadoc' task for its element-list (that task equally depends on
// this one's), that's a real cycle, not just an independence nicety.
// Depending on the root aggregate `:javadoc' task instead sidesteps
// it: both this task and apple-notes-drawing's depend on it, but it
// depends on neither of them (see root build.gradle.kts), and it's a
// real build (not a side-channel file), covering every module's
// packages in one `element-list'.
tasks.javadoc {
    dependsOn(":javadoc")
    (options as StandardJavadocDocletOptions).apply {
        links("https://javadoc.io/doc/com.google.protobuf/protobuf-java/${protobufJavadocVersion}/");
        linksOffline(
            // TODO: confirm the actual published version path once
            // apple-notes-drawing has a real release; "latest" is a
            // placeholder.
            "https://javadoc.rapidcow.org/archive/bridge/apple-notes-drawing/latest/",
            rootProject.layout.buildDirectory.dir("docs/javadoc").get().asFile.path,
        )
        // linksOffline only decides the *URL* a resolved {@link}
        // renders as; javadoc still needs to resolve the symbol at
        // all, same as javac, and it never consults an element-list
        // for that --- only -sourcepath or -classpath.  Adding
        // apple-notes-drawing's own source root here (not its
        // compiled classes --- it doesn't have any yet, and doesn't
        // need to) is what actually lets these {@link}s resolve.
        addPathOption("sourcepath").value =
            listOf(project(":apple-notes-drawing").projectDir.resolve("src/main/java"))
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
