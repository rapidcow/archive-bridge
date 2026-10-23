plugins { application }

repositories { mavenCentral() }

dependencies {
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
