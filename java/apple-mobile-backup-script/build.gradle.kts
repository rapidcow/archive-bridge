plugins {
    `application`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":apple-mobile-backup"))
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

tasks.register<JavaExec>("examine") {
    group = "scripts"
    description = "Examine iOS backup"
    mainClass = "org.rapidcow.archive.script.apple.mobile.backup.IOSBackupExam"
    classpath = sourceSets.main.get().runtimeClasspath
}
