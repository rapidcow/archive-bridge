plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":microsoft-windowsink"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    val inkFixture = rootProject.file(providers.gradleProperty("journalistInkFixture")
        .getOrElse("mscherotter-journalist-ai/src/test/fixtures/journalist/single-sketch.gif"))
    systemProperty("bridge.journalist.inkFixture", inkFixture.absolutePath)
    // An unavailable annex target is not a Gradle resource-copy failure.
    inputs.files(provider { if (inkFixture.isFile) listOf(inkFixture) else emptyList() })
        .withPropertyName("optionalPrivateInk")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}
