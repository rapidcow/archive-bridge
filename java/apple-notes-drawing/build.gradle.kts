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
// package, which apple-notes-proto's own package-info.java {@links}
// right back - so this can't depend on apple-notes-proto's own
// `javadoc` task for its element-list (that task equally depends on
// this one's), that's a real cycle, not just an independence nicety.
// Depending on the root aggregate `:javadoc` task instead sidesteps
// it: both this task and apple-notes-proto's depend on it, but it
// depends on neither of them (see root build.gradle.kts), and it's a
// real build (not a side-channel file), covering every module's
// packages in one `element-list`.
tasks.javadoc {
    dependsOn(":javadoc")
    // apple-notes-proto's own StrokeData et al. only exist once protoc
    // has run - needed below so -sourcepath actually has something to
    // find once this {@links} a specific generated class, not just
    // the proto package itself. Depending on the compile task (which
    // depends on protoc generating sources, transitively) rather than
    // that generation task directly, so this doesn't need to know
    // protoc's own task-naming scheme.
    dependsOn(":apple-notes-proto:compileJava")
    (options as StandardJavadocDocletOptions).apply {
        linksOffline(
            // TODO: confirm the actual published version path once
            // apple-notes-proto has a real release; "latest" is a
            // placeholder.
            "https://javadoc.rapidcow.org/archive/bridge/apple-notes-proto/latest/",
            rootProject.layout.buildDirectory.dir("docs/javadoc").get().asFile.path,
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
