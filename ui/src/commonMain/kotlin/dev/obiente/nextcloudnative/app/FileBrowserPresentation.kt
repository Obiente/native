package dev.obiente.nextcloudnative.app

enum class FileSearchScope { CurrentFolder, AllFiles }

enum class FileWorkspaceFilter { All, Favorites, Folders, Documents, Media, Offline }

enum class FileSortMode { Name, Modified, Size }

enum class FileSortDirection { Ascending, Descending }

enum class FileWorkspaceSource { CurrentFolder, Favorites, GlobalSearch }

fun fileWorkspaceSource(
    searchScope: FileSearchScope,
    filter: FileWorkspaceFilter,
): FileWorkspaceSource = when {
    filter == FileWorkspaceFilter.Favorites -> FileWorkspaceSource.Favorites
    searchScope == FileSearchScope.AllFiles -> FileWorkspaceSource.GlobalSearch
    else -> FileWorkspaceSource.CurrentFolder
}

data class FileBreadcrumb(
    val label: String,
    val path: String,
)

fun fileBreadcrumbs(path: String): List<FileBreadcrumb> = buildList {
    add(FileBreadcrumb("All files", ""))
    var current = ""
    path.trim('/').split('/').filter(String::isNotBlank).forEach { segment ->
        current = if (current.isEmpty()) segment else "$current/$segment"
        add(FileBreadcrumb(segment, current))
    }
}

fun presentFiles(
    files: List<NextcloudFile>,
    query: String,
    filter: FileWorkspaceFilter = FileWorkspaceFilter.All,
    sortMode: FileSortMode = FileSortMode.Name,
    sortDirection: FileSortDirection = FileSortDirection.Ascending,
    offlinePaths: Set<String> = emptySet(),
): List<NextcloudFile> {
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    val comparator = fileComparator(sortMode, sortDirection)
    return files.asSequence()
        .filter { file ->
            terms.isEmpty() || terms.all { term ->
                file.name.contains(term, ignoreCase = true) ||
                    file.path.contains(term, ignoreCase = true) ||
                    file.mimeType?.contains(term, ignoreCase = true) == true ||
                    file.ownerDisplayName?.contains(term, ignoreCase = true) == true
            }
        }
        .filter { file ->
            when (filter) {
                FileWorkspaceFilter.All -> true
                FileWorkspaceFilter.Favorites -> file.favorite
                FileWorkspaceFilter.Folders -> file.isDirectory
                FileWorkspaceFilter.Documents -> !file.isDirectory && file.isDocumentLike()
                FileWorkspaceFilter.Media -> !file.isDirectory && file.isMediaLike()
                FileWorkspaceFilter.Offline -> file.path in offlinePaths
            }
        }
        .sortedWith(comparator)
        .toList()
}

fun fileWorkspaceFilterCount(
    files: List<NextcloudFile>,
    filter: FileWorkspaceFilter,
    offlinePaths: Set<String> = emptySet(),
): Int = presentFiles(files, "", filter = filter, offlinePaths = offlinePaths).size

private fun fileComparator(
    mode: FileSortMode,
    direction: FileSortDirection,
): Comparator<NextcloudFile> {
    val valueComparator = when (mode) {
        FileSortMode.Name -> compareBy<NextcloudFile, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        FileSortMode.Modified -> compareBy<NextcloudFile, Long?>(nullsLast()) { file ->
            file.lastModified?.let(::parseDavMediaSearchTimestamp)
        }
        FileSortMode.Size -> compareBy<NextcloudFile, Long?>(nullsLast()) { it.size }
    }.let { if (direction == FileSortDirection.Descending) it.reversed() else it }
    return compareByDescending<NextcloudFile> { it.isDirectory }
        .then(valueComparator)
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        .thenBy { it.path }
}

private fun NextcloudFile.isDocumentLike(): Boolean {
    val mime = mimeType.orEmpty().lowercase()
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return mime.startsWith("text/") ||
        mime in setOf("application/pdf", "application/rtf") ||
        extension in setOf("md", "txt", "pdf", "odt", "ods", "odp", "doc", "docx", "xls", "xlsx", "ppt", "pptx")
}

private fun NextcloudFile.isMediaLike(): Boolean {
    val mime = mimeType.orEmpty().lowercase()
    return mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")
}
