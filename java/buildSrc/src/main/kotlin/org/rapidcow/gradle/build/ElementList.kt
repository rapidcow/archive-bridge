package org.rapidcow.gradle.build

import java.io.File

/**
 * Writes a plain, `-linkoffline`-compatible `element-list` file - one
 * package name per line - by scanning a module's own source roots for
 * `.java` files, without invoking `javadoc` itself.
 *
 * `-linkoffline <url> <dir>` only needs `<dir>/element-list` to
 * resolve {@link}s into another module's docs; it doesn't care how
 * that file was produced. Generating it this way, instead of pointing
 * at that other module's own real `javadoc` output, means two modules
 * that {@link} each other both ways never end up with a circular task
 * dependency between their `javadoc` tasks.
 */
object ElementList {
    fun generate(sourceRoots: Iterable<File>, outputFile: File) {
        val packages = sortedSetOf<String>()
        for (root in sourceRoots) {
            if (!root.isDirectory) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "java" }
                .forEach { file ->
                    val relativeDir = file.relativeTo(root).parentFile
                    if (relativeDir != null) {
                        packages += relativeDir.path.replace(File.separatorChar, '.')
                    }
                }
        }
        outputFile.parentFile.mkdirs()
        outputFile.writeText(packages.joinToString("") { "$it\n" })
    }
}
