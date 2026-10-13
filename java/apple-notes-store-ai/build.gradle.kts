plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":apple-notes-drawing"))
    // JDBC driver for NoteStore.sqlite. Nothing from java.sql or the
    // driver is exposed in this module's API, so `implementation'.
    implementation(libs.sqlite.jdbc)
    // Z_METADATA and Z_MODELCACHE are binary plists. Only plain Java types
    // cross this module's API, so `implementation' here too.
    implementation(libs.dd.plist)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

// Annex fixtures are read directly by tests. Do not let processTestResources
// snapshot or copy dangling links when their content has not been fetched.
sourceSets.test {
    resources.exclude("fixtures/**")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    val storeFixture = file(providers.gradleProperty("notesStoreFixture")
        .getOrElse("src/test/resources/fixtures/macOS/sequoia/fresh/NoteStore.sqlite"))
    systemProperty("bridge.appleNotesStore.fixture", storeFixture.absolutePath)
    inputs.files(provider { if (storeFixture.isFile) listOf(storeFixture) else emptyList() })
        .withPropertyName("optionalStoreFixture")
}

// Resolve each dependency's documentation using its generated element-list.
tasks.javadoc {
    dependsOn(":apple-notes-drawing:javadoc", ":apple-notes-proto:javadoc")
    (options as StandardJavadocDocletOptions).apply {
        linksOffline(
            "https://javadoc.rapidcow.org/archive/bridge/apple-notes-drawing/0.0.0/",
            project(":apple-notes-drawing").layout.buildDirectory.dir("docs/javadoc").get().asFile.path,
        )
        linksOffline(
            "https://javadoc.rapidcow.org/archive/bridge/apple-notes-proto/0.0.0/",
            project(":apple-notes-proto").layout.buildDirectory.dir("docs/javadoc").get().asFile.path,
        )
    }
}
