package org.rapidcow.gradle.build

import java.io.File

/**
 * Fills in the canned javadoc that protoc never writes.
 *
 * protoc already copies each field's own `.proto` comment onto that
 * field's has/get/set/clear accessors, but it never comments the
 * generic runtime plumbing it emits identically on every message
 * class it generates: descriptor accessors, parse/build factories,
 * FIELD_NUMBER constants and their private backing fields, and so on.
 * That plumbing is a small, fixed set of signature shapes, so rather
 * than hand-editing generated (and regenerated) sources, [documentGeneratedSources]
 * matches each shape by regex and inserts a generic javadoc comment
 * above it whenever one is missing.
 *
 * Wire this in as a `doLast` on `generate(Test)Proto` so it reruns
 * whenever protoc regenerates sources in every proto module.
 */
object ProtoJavadoc {
    private data class Rule(
        val pattern: Regex,
        val lines: (baseName: String, match: MatchResult) -> List<String>,
    )

    private fun toPascalCase(screamingSnakeCase: String): String =
        screamingSnakeCase.split("_").joinToString("") { part ->
            part.lowercase().replaceFirstChar { it.uppercase() }
        }

    /** Extracts bare parameter names ("data", "extensionRegistry", ...) from a captured parameter list. */
    private fun paramNames(paramList: String): List<String> {
        val trimmed = paramList.trim()
        if (trimmed.isEmpty()) return emptyList()
        return trimmed.split(",").map { it.trim().split(Regex("\\s+")).last() }
    }

    private fun paramDescription(name: String): String = when (name) {
        "data" -> "The serialized bytes to parse."
        "input" -> "The source to read from."
        "extensionRegistry" -> "The extensions to recognize while parsing."
        "prototype" -> "The message whose values seed the new builder."
        "other" -> "The message to merge in."
        "registry" -> "The registry to populate."
        else -> "The $name parameter."
    }

    private val rules = listOf(
        // getDescriptor() on the message class and its Builder.
        Rule(Regex(
            """(?m)^([ \t]*)public static final com\.google\.protobuf\.Descriptors\.Descriptor\r?\n[ \t]*getDescriptor\(\) \{"""
        )) { _, _ ->
            listOf(
                "Returns the descriptor for this message type.",
                "",
                "@return This message's descriptor.",
            )
        },
        // getDescriptor() on the <Name>OuterClass file wrapper.
        Rule(Regex(
            """(?m)^([ \t]*)public static com\.google\.protobuf\.Descriptors\.FileDescriptor\r?\n[ \t]*getDescriptor\(\) \{"""
        )) { _, _ ->
            listOf(
                "Returns the descriptor for this generated {@code .proto} file.",
                "",
                "@return This file's descriptor.",
            )
        },
        // registerAllExtensions(ExtensionRegistryLite) / (ExtensionRegistry).
        Rule(Regex(
            """(?m)^([ \t]*)public static void registerAllExtensions\(\r?\n[ \t]*com\.google\.protobuf\.ExtensionRegistryLite registry\) \{"""
        )) { _, _ ->
            listOf(
                "Registers this file's extensions, if any, into the given",
                "registry. There are none unless the {@code .proto} file",
                "declares an {@code extend} block.",
                "",
                "@param registry The registry to populate.",
            )
        },
        Rule(Regex(
            """(?m)^([ \t]*)public static void registerAllExtensions\(\r?\n[ \t]*com\.google\.protobuf\.ExtensionRegistry registry\) \{"""
        )) { _, _ ->
            listOf(
                "Registers this file's extensions, if any, into the given",
                "registry. There are none unless the {@code .proto} file",
                "declares an {@code extend} block.",
                "",
                "@param registry The registry to populate.",
            )
        },
        // The <Name>OrBuilder interface itself. Anchored on the
        // @Generated annotation above it (not the "public interface"
        // line), since javadoc treats a comment as documenting the
        // annotation's target only when it comes before the annotation.
        Rule(Regex(
            """(?m)^([ \t]*)@com\.google\.protobuf\.Generated\r?\n[ \t]*public interface (\w+)OrBuilder extends"""
        )) { _, match ->
            listOf("Common accessors shared between {@link ${match.groupValues[2]}} and its {@link ${match.groupValues[2]}.Builder}.")
        },
        // The <Name>OuterClass file wrapper class itself (same
        // before-the-annotation anchoring as above).
        Rule(Regex(
            """(?m)^([ \t]*)@com\.google\.protobuf\.Generated\r?\n[ \t]*public final class (\w+)OuterClass extends com\.google\.protobuf\.GeneratedFile \{"""
        )) { _, match ->
            listOf(
                "Holds the file descriptor and static registration hooks",
                "generated for {@code ${match.groupValues[2]}.proto}.",
            )
        },
        // parseFrom(...) factory overloads.
        Rule(Regex(
            """(?m)^([ \t]*)public static [\w.]+ parseFrom\(([\s\S]*?)\)"""
        )) { baseName, match ->
            buildList {
                add("Parses a {@link $baseName} from the given source.")
                add("")
                for (param in paramNames(match.groupValues[2])) {
                    add("@param $param ${paramDescription(param)}")
                }
                add("@return The parsed {@link $baseName}.")
                add("@throws com.google.protobuf.InvalidProtocolBufferException if")
                add("        the input is not a valid, complete-enough encoding.")
            }
        },
        // parseDelimitedFrom(...) factory overloads.
        Rule(Regex(
            """(?m)^([ \t]*)public static [\w.]+ parseDelimitedFrom\(([\s\S]*?)\)"""
        )) { baseName, match ->
            buildList {
                add("Parses a single, length-delimited {@link $baseName} from")
                add("the given stream.")
                add("")
                for (param in paramNames(match.groupValues[2])) {
                    add("@param $param ${paramDescription(param)}")
                }
                add("@return The parsed {@link $baseName}, or {@code null} at end of stream.")
                add("@throws java.io.IOException if an I/O error occurs while reading.")
            }
        },
        // newBuilder() / newBuilder(prototype).
        Rule(Regex(
            """(?m)^([ \t]*)public static Builder newBuilder\(\) \{"""
        )) { baseName, _ ->
            listOf(
                "Creates a new, empty builder for {@link $baseName}.",
                "",
                "@return A new, empty builder.",
            )
        },
        Rule(Regex(
            """(?m)^([ \t]*)public static Builder newBuilder\([\w.]+ prototype\) \{"""
        )) { baseName, _ ->
            listOf(
                "Creates a new builder for {@link $baseName}, pre-populated",
                "with the values from {@code prototype}.",
                "",
                "@param prototype ${paramDescription("prototype")}",
                "@return A new builder seeded with {@code prototype}.",
            )
        },
        // The type-specific mergeFrom(<Type> other) overload (not the
        // inherited mergeFrom(Message), which already has a doc via @Override).
        Rule(Regex(
            """(?m)^([ \t]*)public Builder mergeFrom\((?!com\.google\.protobuf\.Message )[\w.]+ other\) \{"""
        )) { baseName, _ ->
            listOf(
                "Merges the given {@link $baseName} into this builder,",
                "copying over any fields that are set on {@code other}.",
                "",
                "@param other ${paramDescription("other")}",
                "@return This builder for chaining.",
            )
        },
        // parser().
        Rule(Regex(
            """(?m)^([ \t]*)public static com\.google\.protobuf\.Parser<\w+> parser\(\) \{"""
        )) { baseName, _ ->
            listOf(
                "Returns the parser used to read {@link $baseName} from a serialized form.",
                "",
                "@return The parser for {@link $baseName}.",
            )
        },
        // getDefaultInstance() (not getDefaultInstanceForType(), which is
        // an @Override and already documented via inheritance).
        Rule(Regex(
            """(?m)^([ \t]*)public static [\w.]+ getDefaultInstance\(\) \{"""
        )) { baseName, _ ->
            listOf(
                "Returns the default, all-fields-unset instance of {@link $baseName}.",
                "",
                "@return The default instance.",
            )
        },
        // The <FIELD>_FIELD_NUMBER constants.
        Rule(Regex(
            """(?m)^([ \t]*)public static final int (\w+)_FIELD_NUMBER = \d+;"""
        )) { _, match ->
            listOf("The proto field number backing {@link #get${toPascalCase(match.groupValues[2])}()}.")
        },
        // memoizedIsInitialized, the isInitialized() memo field.
        Rule(Regex(
            """(?m)^([ \t]*)private byte memoizedIsInitialized = -1;"""
        )) { _, _ ->
            listOf(
                "Cached result of {@link #isInitialized()}: {@code 1} true,",
                "{@code 0} false, {@code -1} not yet computed.",
            )
        },
        // Every other private, underscore-suffixed backing field: bitField0_
        // and each per-proto-field <name>_ storage field, on both the
        // message class and its Builder.
        Rule(Regex(
            """(?m)^([ \t]*)private (?:int|boolean|double|float|long|com\.google\.protobuf\.ByteString|java\.lang\.String) (\w+)_(?:\s*=[^;]*)?;"""
        )) { _, match ->
            val fieldName = match.groupValues[2]
            if (fieldName.startsWith("bitField")) {
                listOf("Bitmask recording which optional fields are present.")
            } else {
                listOf("Backing storage for {@link #get${fieldName.replaceFirstChar { it.uppercase() }}()}.")
            }
        },
    )

    private fun applyRule(text: String, rule: Rule, baseName: String): String {
        var result = text
        for (match in rule.pattern.findAll(result).toList().asReversed()) {
            val indent = match.groupValues[1]
            val start = match.range.first
            var i = start - 1
            while (i >= 0 && result[i].isWhitespace()) i--
            val alreadyDocumented = i >= 1 && result[i] == '/' && result[i - 1] == '*'
            if (alreadyDocumented) continue
            val comment = buildString {
                append(indent).append("/**\n")
                for (line in rule.lines(baseName, match)) {
                    append(indent).append(if (line.isEmpty()) " *" else " * $line").append("\n")
                }
                append(indent).append(" */\n")
            }
            result = result.substring(0, start) + comment + result.substring(start)
        }
        return result
    }

    /**
     * Walks every `.java` file under [dir], inserting the canned
     * javadoc from [rules] above any matching signature that doesn't
     * already have a comment directly above it.
     */
    fun documentGeneratedSources(dir: File) {
        if (!dir.exists()) return
        dir.walkTopDown().filter { it.isFile && it.extension == "java" }.forEach { file ->
            val baseName = file.nameWithoutExtension
                .removeSuffix("OrBuilder")
                .removeSuffix("OuterClass")
            var text = file.readText()
            for (rule in rules) {
                text = applyRule(text, rule, baseName)
            }
            file.writeText(text)
        }
    }
}
