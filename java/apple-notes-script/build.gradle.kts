plugins { application }

repositories {
    mavenCentral()
    ivy {
        url = uri("https://ftp.rapidcow.org/pub/lib/java")
        patternLayout { artifact("[organisation]/[module]/[revision]/[artifact]-[revision].[ext]") }
        metadataSources { artifact() }
        content { includeModule("org/rapidcow/trivnote", "libexpr") }
    }
}

dependencies {
    implementation("org/rapidcow/trivnote:libexpr:0.0.0")
    implementation(project(":apple-notes-store"))
    implementation(project(":cozic-joplin-script"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
application { mainClass = "org.rapidcow.ai.archive.script.apple.notes.AppleNotesViewer" }
tasks.named<Test>("test") {
    useJUnitPlatform()
    providers.gradleProperty("notesViewerFixture").orNull?.let {
        systemProperty("notesViewerFixture", file(it).absolutePath)
    }
}

// Keep the Perl program and its sibling schema together, also in distributions.
tasks.processResources {
    from(rootProject.file("../ai/tools")) {
        include("apple-notes-drawing-decode.pl", "apple-notes-drawing-2.proto")
        into("drawing-decoder")
    }
}
