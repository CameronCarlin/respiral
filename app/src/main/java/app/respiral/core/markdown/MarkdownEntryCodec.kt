package app.respiral.core.markdown

import app.respiral.core.model.VaultEntry
import app.respiral.core.model.VaultMedia
import app.respiral.core.model.VaultTag
import java.time.Instant
import java.util.UUID

interface MarkdownEntryCodec {
    fun encode(entry: VaultEntry): String

    fun decode(markdown: String): VaultEntry
}

class MalformedEntryException(message: String) : IllegalArgumentException(message)

class CanonicalMarkdownEntryCodec : MarkdownEntryCodec {
    override fun encode(entry: VaultEntry): String {
        validateMedia(entry.media)
        val tags = entry.tags.sortedBy(VaultTag::ordinal).joinToString(", ") { it.name }
        val media = entry.media.joinToString(", ") {
            "{path: ${quote(it.relativePath)}, mimeType: ${quote(it.mimeType)}}"
        }

        return buildString {
            appendLine("---")
            appendLine("id: ${entry.id}")
            appendLine("title: ${quote(entry.title)}")
            appendLine("createdAt: ${entry.createdAt}")
            appendLine("updatedAt: ${entry.updatedAt}")
            appendLine("tags: [$tags]")
            appendLine("media: [$media]")
            appendLine("---")
            append(entry.body)
        }
    }

    override fun decode(markdown: String): VaultEntry {
        val (frontMatter, body) = splitFrontMatter(markdown)
        val fields = parseFields(frontMatter)

        return VaultEntry(
            id = parseUuid(fields.getValue("id")),
            title = parseQuoted(fields.getValue("title"), "title"),
            body = body,
            createdAt = parseUtcInstant(fields.getValue("createdAt"), "createdAt"),
            updatedAt = parseUtcInstant(fields.getValue("updatedAt"), "updatedAt"),
            tags = parseTags(fields.getValue("tags")),
            media = parseMedia(fields.getValue("media")),
        )
    }

    private fun splitFrontMatter(markdown: String): Pair<String, String> {
        if (!markdown.startsWith("---\n")) malformed("Entry must begin with front matter")
        val closingDelimiter = markdown.indexOf("\n---\n", startIndex = 4)
        if (closingDelimiter < 0) malformed("Entry front matter must have a closing delimiter")
        return markdown.substring(4, closingDelimiter) to markdown.substring(closingDelimiter + 5)
    }

    private fun parseFields(frontMatter: String): Map<String, String> {
        if (frontMatter.isEmpty()) malformed("Entry front matter is empty")
        val fields = linkedMapOf<String, String>()
        frontMatter.split('\n').forEach { line ->
            val separator = line.indexOf(": ")
            if (separator <= 0) malformed("Malformed front matter line")
            val key = line.substring(0, separator)
            val value = line.substring(separator + 2)
            if (key !in requiredKeys) malformed("Unknown front matter key: $key")
            if (fields.put(key, value) != null) malformed("Duplicate front matter key: $key")
        }
        val missing = requiredKeys - fields.keys
        if (missing.isNotEmpty()) malformed("Missing required front matter: ${missing.joinToString()}")
        return fields
    }

    private fun parseUuid(value: String): UUID = try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        malformed("Invalid entry id")
    }

    private fun parseUtcInstant(value: String, field: String): Instant {
        if (!value.endsWith("Z")) malformed("$field must be an ISO-8601 UTC timestamp")
        return try {
            Instant.parse(value)
        } catch (_: Exception) {
            malformed("Invalid $field timestamp")
        }
    }

    private fun parseTags(value: String): Set<VaultTag> {
        val values = parseBracketList(value, "tags")
        val tags = values.map { name ->
            try {
                VaultTag.valueOf(name)
            } catch (_: IllegalArgumentException) {
                malformed("Unknown vault tag: $name")
            }
        }
        if (tags.size != tags.toSet().size) malformed("Duplicate vault tag")
        return tags.toSet()
    }

    private fun parseMedia(value: String): List<VaultMedia> {
        val content = parseBracketContent(value, "media")
        if (content.isEmpty()) return emptyList()

        val media = mutableListOf<VaultMedia>()
        var position = 0
        while (position < content.length) {
            val match = mediaPattern.matchAt(content, position)
                ?: malformed("Malformed media front matter")
            val path = parseQuoted(match.groupValues[1], "media path")
            val mimeType = parseQuoted(match.groupValues[2], "media MIME type")
            media += VaultMedia(path, mimeType)
            position = match.range.last + 1
            if (position == content.length) break
            if (!content.startsWith(", ", position)) malformed("Malformed media front matter")
            position += 2
        }
        validateMedia(media)
        return media
    }

    private fun parseBracketList(value: String, field: String): List<String> {
        val content = parseBracketContent(value, field)
        if (content.isEmpty()) return emptyList()
        return content.split(", ").also {
            if (it.any(String::isEmpty)) malformed("Malformed $field list")
        }
    }

    private fun parseBracketContent(value: String, field: String): String {
        if (!value.startsWith('[') || !value.endsWith(']')) malformed("$field must be a bracketed list")
        return value.substring(1, value.length - 1)
    }

    private fun validateMedia(media: List<VaultMedia>) {
        media.forEach { item ->
            if (
                item.relativePath.isBlank() ||
                item.relativePath.startsWith('/') ||
                item.relativePath.contains("..") ||
                item.relativePath.contains('\\') ||
                item.relativePath.contains('\u0000')
            ) {
                malformed("Media path must be relative to the vault root")
            }
            if (item.mimeType.isBlank()) malformed("Media MIME type cannot be blank")
        }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            append(
                when (character) {
                    '\\' -> "\\\\"
                    '"' -> "\\\""
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    else -> character
                },
            )
        }
        append('"')
    }

    private fun parseQuoted(value: String, field: String): String {
        if (value.length < 2 || value.first() != '"' || value.last() != '"') {
            malformed("$field must be a quoted string")
        }
        return buildString {
            var index = 1
            while (index < value.lastIndex) {
                val character = value[index]
                if (character != '\\') {
                    append(character)
                    index += 1
                    continue
                }
                if (index + 1 >= value.lastIndex) malformed("Invalid escape in $field")
                append(
                    when (value[index + 1]) {
                        '\\' -> '\\'
                        '"' -> '"'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        else -> malformed("Invalid escape in $field")
                    },
                )
                index += 2
            }
        }
    }

    private fun malformed(message: String): Nothing = throw MalformedEntryException(message)

    private companion object {
        val requiredKeys = setOf("id", "title", "createdAt", "updatedAt", "tags", "media")
        val mediaPattern = Regex("""\{path: ("(?:\\.|[^"\\])*"), mimeType: ("(?:\\.|[^"\\])*")\}""")
    }
}
