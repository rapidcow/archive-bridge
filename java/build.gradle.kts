// Aggregates Javadoc across every subproject into one invocation, so
// full cross-checking of {@link}s between modules is possible in one
// go - each module's own `javadoc` task (see its build.gradle.kts)
// instead uses `-linkoffline` to resolve the other modules it {@link}s
// as external docs, so it can be previewed standalone without needing
// this aggregate.
//
// This can't just be Gradle's built-in Javadoc task type pointed at
// every subproject's sources: that task always invokes the `javadoc`
// tool with an explicit list of source *files*, and in that mode the
// standard doclet silently - no warning, no error - drops any package
// whose only file is a package-info.java, whenever another package in
// the same invocation has an unresolved {@code @link}. (Confirmed
// directly against the `javadoc` tool: the same sources produce a
// complete site under `-sourcepath`/`-subpackages`, which discovers
// packages by name instead of by file list.) So this invokes the real
// `javadoc` binary itself, in that mode.
evaluationDependsOnChildren()

// The most recent available documentation is 4.35.1, for some reason?
val protobufJavadocVersion = "4.35.1"

tasks.register<Exec>("javadoc") {
    group = "documentation"
    description = "Generates Javadoc for every subproject in one pass, to check cross-module {@link}s all resolve."

    val mainSourceSets = subprojects.map {
        it.extensions.getByType<JavaPluginExtension>().sourceSets["main"]
    }
    dependsOn(subprojects.map { it.tasks.named("classes") })

    val sourceRoots = mainSourceSets.flatMap { it.java.srcDirs }
    val classpath = files(mainSourceSets.map { it.compileClasspath })
    val destDir = layout.buildDirectory.dir("docs/javadoc").get().asFile

    inputs.files(mainSourceSets.map { it.allJava })
    inputs.files(classpath)
    outputs.dir(destDir)

    doFirst {
        destDir.mkdirs()
    }

    commandLine(
        "javadoc",
        "-d", destDir.absolutePath,
        "-sourcepath", sourceRoots.joinToString(File.pathSeparator) { it.absolutePath },
        "-classpath", classpath.asPath,
        "-link", "https://javadoc.io/doc/com.google.protobuf/protobuf-java/${protobufJavadocVersion}/",
        "-subpackages", "org.rapidcow",
    )
}
