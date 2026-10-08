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

// Resolve drawing's package documentation using its generated element-list.
tasks.javadoc {
    val drawingDocsUrl = "https://javadoc.rapidcow.org/archive/bridge/apple-notes-drawing/0.0.0/"
    val schemaDocsUrl = "https://javadoc.rapidcow.org/archive/bridge/apple-notes-proto/0.0.0/"
    val schemaDocsDir = project(":apple-notes-proto").layout.buildDirectory.dir("docs/javadoc")
    val storeDocsDir = layout.buildDirectory.dir("docs/javadoc")
    inputs.dir(schemaDocsDir)
    dependsOn(":apple-notes-drawing:javadoc")
    (options as StandardJavadocDocletOptions).apply {
        linksOffline(
            drawingDocsUrl,
            project(":apple-notes-drawing").layout.buildDirectory.dir("docs/javadoc").get().asFile.path,
        )
    }

    // linkoffline maps packages, not individual classes. Both artifacts share
    // a package, so redirect generated-class links using proto's actual pages,
    // while keeping package-summary links pointed at drawing.
    doLast {
        val schemaPages = schemaDocsDir.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "html" &&
                it.relativeTo(schemaDocsDir.get().asFile).invariantSeparatorsPath.startsWith("org/") &&
                !it.name.startsWith("package-") && !it.name.startsWith("class-use") }
            .map { it.relativeTo(schemaDocsDir.get().asFile).invariantSeparatorsPath }
            .toList()
        storeDocsDir.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "html" }
            .forEach { page ->
                val original = page.readText(Charsets.UTF_8)
                var updated = original
                schemaPages.forEach { path ->
                    updated = updated.replace(drawingDocsUrl + path, schemaDocsUrl + path)
                }
                if (updated != original) page.writeText(updated, Charsets.UTF_8)
            }
    }
}
