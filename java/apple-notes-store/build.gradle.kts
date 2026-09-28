plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":apple-notes-drawing"))

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

// This module's package-info.java {@links} apple-notes-drawing's
// package, so its javadoc needs that package resolvable. Unlike the
// old apple-notes-proto <-> apple-notes-drawing pair, this link is
// one-directional - apple-notes-drawing's own package-info.java does
// NOT link back here - so there's no javadoc-task cycle to route
// around via the root `:javadoc' aggregate task; depending directly on
// apple-notes-drawing's own compile task is enough.
tasks.javadoc {
    dependsOn(":apple-notes-drawing:compileJava")
    (options as StandardJavadocDocletOptions).apply {
        linksOffline(
            // TODO: confirm the actual published version path once
            // apple-notes-drawing has a real release; "latest" is a
            // placeholder.
            "https://javadoc.rapidcow.org/archive/bridge/apple-notes-drawing/latest/",
            rootProject.layout.buildDirectory.dir("docs/javadoc").get().asFile.path,
        )
        addPathOption("sourcepath").value = listOf(
            project(":apple-notes-drawing").projectDir.resolve("src/main/java"),
            project(":apple-notes-drawing").layout.buildDirectory.dir("generated/sources/proto/main/java").get().asFile,
        )
    }
}
