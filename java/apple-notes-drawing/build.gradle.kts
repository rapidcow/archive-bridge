plugins {
    `java-library`
}

val fractionVersion = libs.versions.commons.numbers.fraction.get()
// The most recent available documentation is 4.35.1, for some reason?
val protobufJavadocVersion = "4.35.1"

repositories {
    mavenCentral()
}

dependencies {
    api(project(":apple-notes-proto"))
    // Fraction (org.apache.commons.numbers.fraction) appears in
    // ChannelReading's public API surface, so this has to be `api',
    // not `implementation' - consumers need it on their own compile
    // classpath too.
    api(libs.commons.numbers.fraction)

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

// Generated types share this module's package, but their documentation
// belongs to schema. Build its Javadoc first to supply the element-list.
tasks.javadoc {
    dependsOn(":apple-notes-proto:javadoc")
    (options as StandardJavadocDocletOptions).apply {
        links("https://commons.apache.org/proper/commons-numbers/commons-numbers-fraction/javadocs/api-${fractionVersion}/")
        links("https://javadoc.io/doc/com.google.protobuf/protobuf-java/${protobufJavadocVersion}/")
        linksOffline(
            "https://javadoc.rapidcow.org/archive/bridge/apple-notes-proto/0.0.0/", // XXX: Check me!
            project(":apple-notes-proto").layout.buildDirectory.dir("docs/javadoc").get().asFile.path,
        )
    }
}
