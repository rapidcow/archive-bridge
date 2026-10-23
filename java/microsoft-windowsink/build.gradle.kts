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
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    val inkFixture = rootProject.file(providers.gradleProperty("journalistInkFixture")
        .getOrElse("mscherotter-journalist-ai/src/test/fixtures/journalist/single-sketch.gif"))
    systemProperty("bridge.journalist.inkFixture", inkFixture.absolutePath)
    inputs.files(provider { if (inkFixture.isFile) listOf(inkFixture) else emptyList() })
        .withPropertyName("optionalPrivateInk")
}
