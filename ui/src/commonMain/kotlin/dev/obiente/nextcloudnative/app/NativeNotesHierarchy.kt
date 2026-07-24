package dev.obiente.nextcloudnative.app

internal data class NativeNoteBreadcrumb(
    val label: String,
    val path: String,
)

internal data class NativeNoteFolder(
    val name: String,
    val path: String,
    val directNoteCount: Int,
    val descendantNoteCount: Int,
)

internal data class NativeNotesLocation(
    val path: String,
    val breadcrumbs: List<NativeNoteBreadcrumb>,
    val folders: List<NativeNoteFolder>,
    val notes: List<NextcloudNote>,
)

internal data class NativeNoteMarkdownMetadata(
    val preview: String?,
    val wordCount: Int,
    val completedTasks: Int,
    val totalTasks: Int,
)

internal fun buildNativeNotesLocation(
    notes: List<NextcloudNote>,
    requestedPath: String,
): NativeNotesLocation {
    val path = normalizeNoteCategory(requestedPath)
    val prefix = path.takeIf(String::isNotEmpty)?.plus('/').orEmpty()
    val visibleFolders = linkedMapOf<String, MutableList<NextcloudNote>>()
    notes.forEach { note ->
        val category = runCatching { normalizeNoteCategory(note.category) }.getOrDefault("")
        if (category == path || !category.startsWith(prefix)) return@forEach
        val remainder = category.removePrefix(prefix)
        val childName = remainder.substringBefore('/').takeIf(String::isNotBlank) ?: return@forEach
        visibleFolders.getOrPut(childName) { mutableListOf() } += note
    }
    val folders = visibleFolders.map { (name, descendants) ->
        val childPath = listOf(path, name).filter(String::isNotBlank).joinToString("/")
        NativeNoteFolder(
            name = name,
            path = childPath,
            directNoteCount = descendants.count { note ->
                runCatching { normalizeNoteCategory(note.category) }.getOrNull() == childPath
            },
            descendantNoteCount = descendants.size,
        )
    }.sortedBy { folder -> folder.name.lowercase() }
    val directNotes = notes.filter { note ->
        runCatching { normalizeNoteCategory(note.category) }.getOrNull() == path
    }.sortedWith(
        compareByDescending<NextcloudNote> { note -> note.favorite }
            .thenByDescending(NextcloudNote::modified)
            .thenBy { note -> note.title.lowercase() },
    )
    return NativeNotesLocation(
        path = path,
        breadcrumbs = buildList {
            add(NativeNoteBreadcrumb("Notes", ""))
            var current = ""
            path.split('/').filter(String::isNotBlank).forEach { segment ->
                current = listOf(current, segment).filter(String::isNotBlank).joinToString("/")
                add(NativeNoteBreadcrumb(segment, current))
            }
        },
        folders = folders,
        notes = directNotes,
    )
}

internal fun NextcloudNote.markdownMetadata(): NativeNoteMarkdownMetadata {
    val markdown = content.orEmpty()
    val lines = markdown.lineSequence().toList()
    val taskLines = lines.mapNotNull { line ->
        NOTE_TASK_PATTERN.matchEntire(line.trim())?.groupValues?.getOrNull(1)
    }
    val preview = lines.asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { line ->
            line
                .replace(NOTE_HEADING_PREFIX, "")
                .replace(NOTE_LIST_PREFIX, "")
                .replace(NOTE_TASK_PREFIX, "")
                .replace(NOTE_INLINE_MARKUP, "$1")
                .trim()
        }
        .firstOrNull(String::isNotBlank)
        ?.take(MAX_NOTE_LIST_PREVIEW_CHARACTERS)
    val words = markdown
        .replace(NOTE_CODE_BLOCK, " ")
        .splitToSequence(NOTE_WORD_SEPARATOR)
        .count { token -> token.any(Char::isLetterOrDigit) }
    return NativeNoteMarkdownMetadata(
        preview = preview,
        wordCount = words,
        completedTasks = taskLines.count { marker -> marker.equals("x", ignoreCase = true) },
        totalTasks = taskLines.size,
    )
}

internal fun noteFolderParent(path: String): String {
    val normalized = normalizeNoteCategory(path)
    return normalized.substringBeforeLast('/', missingDelimiterValue = "")
}

internal fun noteFolderRenameTarget(path: String, newName: String): String {
    val normalized = normalizeNoteCategory(path)
    require(normalized.isNotEmpty()) { "The root note folder cannot be renamed." }
    val segment = normalizeNoteCategory(newName)
    require('/' !in segment) { "Enter a folder name, not a path." }
    return listOf(noteFolderParent(normalized), segment).filter(String::isNotBlank).joinToString("/")
}

private val NOTE_TASK_PATTERN = Regex("""^[-*+]\s+\[([ xX])]\s+.*$""")
private val NOTE_HEADING_PREFIX = Regex("""^#{1,6}\s+""")
private val NOTE_LIST_PREFIX = Regex("""^[-*+]\s+""")
private val NOTE_TASK_PREFIX = Regex("""^\[[ xX]]\s+""")
private val NOTE_INLINE_MARKUP = Regex("""(?:\*\*|__|~~|`)(.+?)(?:\*\*|__|~~|`)""")
private val NOTE_CODE_BLOCK = Regex("""(?s)```.*?```""")
private val NOTE_WORD_SEPARATOR = Regex("""[^\p{L}\p{N}_'-]+""")
private const val MAX_NOTE_LIST_PREVIEW_CHARACTERS = 240
