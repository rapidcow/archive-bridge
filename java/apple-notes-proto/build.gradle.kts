plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

repositories {
    mavenCentral()
}

dependencies {
    api(libs.protobuf.java)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.36.2"
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
