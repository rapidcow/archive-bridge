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

// A plain list of this module's own packages, consumed by sibling
// modules' `-linkoffline` (see their build.gradle.kts) so their
// {@link}s into this module resolve without a circular task
// dependency on this module's own `javadoc` task.
tasks.register("elementList") {
    val outputDir = layout.buildDirectory.dir("tmp/elementList")
    val srcDirs = sourceSets.main.get().java.srcDirs
    inputs.files(sourceSets.main.get().allJava)
    outputs.dir(outputDir)
    doLast {
        org.rapidcow.gradle.build.ElementList.generate(srcDirs, outputDir.get().asFile.resolve("element-list"))
    }
}

// This module's package-info.java {@links} apple-notes-proto's
// package, so resolve it as an external doc site rather than
// depending on apple-notes-proto's own `javadoc` task directly - that
// task {@links} back into this module, so a direct dependency would
// be circular.
tasks.javadoc {
    dependsOn(":apple-notes-proto:elementList")
    // apple-notes-proto's own StrokeData et al. only exist once protoc
    // has run - needed below so -sourcepath actually has something to
    // find once this {@links} a specific generated class, not just
    // the proto package itself.
    dependsOn(":apple-notes-proto:generateProto")
    (options as StandardJavadocDocletOptions).apply {
        linksOffline(
            // TODO: confirm the actual published version path once
            // apple-notes-proto has a real release; "latest" is a
            // placeholder.
            "https://javadoc.rapidcow.org/archive/bridge/apple-notes-proto/latest/",
            project(":apple-notes-proto").layout.buildDirectory.dir("tmp/elementList").get().asFile.path,
        )
        // linksOffline only decides the *URL* a resolved {@link}
        // renders as; javadoc still needs to resolve the symbol at
        // all, same as javac, and it never consults an element-list
        // for that - only -sourcepath or -classpath. Adding
        // apple-notes-proto's own sources here (both hand-written and
        // protoc-generated) is what actually lets these {@link}s
        // resolve.
        addPathOption("sourcepath").value = listOf(
            project(":apple-notes-proto").projectDir.resolve("src/main/java"),
            project(":apple-notes-proto").layout.buildDirectory.dir("generated/sources/proto/main/java").get().asFile,
        )
    }
}
