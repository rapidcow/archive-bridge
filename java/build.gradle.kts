// Aggregates Javadoc across every subproject into one invocation, so
// {@link}s between apple-notes-proto's generated classes and
// apple-notes-drawing's decoders resolve - they reference each other
// both ways, so no per-module `javadoc` task can ever see enough of
// the world to resolve them alone (see each subproject's own
// `javadoc` task, disabled in favor of this one).
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

val protobufJavadocVersion = "4.35.1"

tasks.register<Exec>("javadoc") {
    group = "documentation"
    description = "Generates Javadoc for every subproject in one pass, so cross-module {@link}s resolve."

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
