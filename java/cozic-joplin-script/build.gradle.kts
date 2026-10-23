plugins {
    `application`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":cozic-joplin"))
    testImplementation(libs.junit.jupiter)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Apply a specific Java toolchain to ease working on different environments.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

application {
    mainClass = "org.rapidcow.ai.archive.script.cozic.joplin.JoplinExportViewer"
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
}
