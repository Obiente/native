package dev.obiente.nextcloudnative.contracts

/** One `@tag content` entry of a documentation comment; content may span several lines. */
internal data class PhpDocTag(val name: String, val content: String)

/**
 * Splits a documentation comment into its tags. Leading `*` decoration is removed and a tag runs
 * until the next line that starts with `@`. Text before the first tag is ignored.
 */
internal fun PhpToken.DocComment.tags(): List<PhpDocTag> {
    if (!complete) return emptyList()
    val tags = mutableListOf<PhpDocTag>()
    var name: String? = null
    val content = StringBuilder()
    fun flush() {
        name?.let { tags += PhpDocTag(it, content.toString().trim()) }
        name = null
        content.setLength(0)
    }
    text.lineSequence().forEach { raw ->
        val line = raw.trimStart().removePrefix("*").trim()
        val match = DOC_TAG.matchEntire(line)
        if (match != null) {
            flush()
            if (tags.size >= MAX_DOC_TAGS) return tags
            name = match.groupValues[1]
            content.append(match.groupValues[2])
        } else if (name != null && line.isNotEmpty()) {
            content.append(' ').append(line)
        }
    }
    flush()
    return tags
}

/**
 * Psalm parameter types by parameter name; `@psalm-param` overrides `@param`. A malformed override
 * drops the type instead of falling back to the weaker declaration.
 */
internal fun PhpToken.DocComment.parameterTypes(): Map<String, PsalmType> {
    val tags = tags()
    val declared = linkedMapOf<String, PsalmType?>()
    listOf("param", "psalm-param").forEach { tagName ->
        tags.filter { it.name == tagName }.forEach tag@ { tag ->
            val split = splitLeadingPsalmType(tag.content)
            if (split == null) {
                // An unbalanced type still names its parameter; the declaration is dropped.
                ANY_PARAM_NAME.find(tag.content)?.groupValues?.get(1)?.let { declared[it] = null }
                return@tag
            }
            val name = PARAM_NAME.find(split.second)?.groupValues?.get(1) ?: return@tag
            declared[name] = parsePsalmType(split.first)
        }
    }
    return declared.mapNotNull { (name, type) -> type?.let { name to it } }.toMap()
}

/** The psalm return type; `@psalm-return` overrides `@return`. A malformed override drops both. */
internal fun PhpToken.DocComment.returnType(): PsalmType? {
    val tags = tags()
    tags.lastOrNull { it.name == "psalm-return" }?.let { tag ->
        return splitLeadingPsalmType(tag.content)?.first?.let(::parsePsalmType)
    }
    return tags.lastOrNull { it.name == "return" }?.let { tag ->
        splitLeadingPsalmType(tag.content)?.first?.let(::parsePsalmType)
    }
}

/** `@psalm-type Name = type` declarations. Malformed declarations are omitted. */
internal fun PhpToken.DocComment.typeAliases(): Map<String, PsalmType> = buildMap {
    tags().filter { it.name == "psalm-type" }.take(MAX_DOC_ALIASES).forEach { tag ->
        val match = ALIAS_DECLARATION.matchEntire(tag.content) ?: return@forEach
        parsePsalmType(match.groupValues[2])?.let { put(match.groupValues[1], it) }
    }
}

internal data class PsalmTypeImport(val name: String, val fromClass: String, val alias: String)

/** `@psalm-import-type Name from Class [as Alias]` declarations. */
internal fun PhpToken.DocComment.typeImports(): List<PsalmTypeImport> =
    tags().filter { it.name == "psalm-import-type" }.take(MAX_DOC_ALIASES).mapNotNull { tag ->
        val match = IMPORT_DECLARATION.matchEntire(tag.content) ?: return@mapNotNull null
        PsalmTypeImport(
            name = match.groupValues[1],
            fromClass = match.groupValues[2],
            alias = match.groupValues[3].ifEmpty { match.groupValues[1] },
        )
    }

private val DOC_TAG = Regex("""@([A-Za-z][A-Za-z0-9_-]{0,63})(.*)""")
private val PARAM_NAME = Regex("""^(?:&|\.\.\.)?\$([A-Za-z_][A-Za-z0-9_]{0,63})""")
private val ANY_PARAM_NAME = Regex("""\$([A-Za-z_][A-Za-z0-9_]{0,63})""")
private val ALIAS_DECLARATION = Regex("""([A-Za-z_][A-Za-z0-9_]{0,127})\s*=?\s*(.+)""", RegexOption.DOT_MATCHES_ALL)
private val IMPORT_DECLARATION = Regex(
    """([A-Za-z_][A-Za-z0-9_]{0,127})\s+from\s+(\\?[A-Za-z_][A-Za-z0-9_\\]{0,255})(?:\s+as\s+([A-Za-z_][A-Za-z0-9_]{0,127}))?\s*""",
)
private const val MAX_DOC_TAGS = 256
private const val MAX_DOC_ALIASES = 256
