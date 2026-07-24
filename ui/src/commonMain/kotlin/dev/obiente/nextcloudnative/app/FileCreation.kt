package dev.obiente.nextcloudnative.app

enum class FileCreationKind {
    Folder,
    Markdown,
    Text,
}

data class FileCreationPlan(
    val kind: FileCreationKind,
    val parentPath: String,
    val name: String,
    val path: String,
)

fun planFileCreation(
    kind: FileCreationKind,
    parentPath: String,
    requestedName: String,
): FileCreationPlan {
    val parent = requireNormalizedCreationPath(parentPath, allowEmpty = true)
    val requested = requestedName.trim()
    require(requested.isNotEmpty()) { "Enter a name." }
    require(requested.none(Char::isISOControl)) { "The name contains invalid characters." }
    require(requested != "." && requested != ".." && '/' !in requested && '\\' !in requested) {
        "The name contains invalid path characters."
    }
    require(requested.encodeToByteArray().size <= MAX_FILE_CREATION_NAME_BYTES) {
        "The name is too long."
    }
    val name = when (kind) {
        FileCreationKind.Folder -> requested
        FileCreationKind.Markdown -> requested.withExtensionIfMissing(".md")
        FileCreationKind.Text -> requested.withExtensionIfMissing(".txt")
    }
    val path = if (parent.isEmpty()) name else "$parent/$name"
    require(path.encodeToByteArray().size <= MAX_FILE_CREATION_PATH_BYTES) {
        "The resulting path is too long."
    }
    return FileCreationPlan(kind, parent, name, path)
}

private fun String.withExtensionIfMissing(extension: String): String =
    if (substringAfterLast('.', missingDelimiterValue = "").isEmpty()) "$this$extension" else this

private fun requireNormalizedCreationPath(path: String, allowEmpty: Boolean): String {
    require('\u0000' !in path && '\\' !in path) { "The folder path contains invalid characters." }
    require(!path.startsWith('/') && !path.endsWith('/')) { "The folder path must be relative and normalized." }
    if (path.isEmpty()) {
        require(allowEmpty) { "A folder path is required." }
        return path
    }
    require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
        "The folder path contains an invalid segment."
    }
    return path
}

private const val MAX_FILE_CREATION_NAME_BYTES = 255
private const val MAX_FILE_CREATION_PATH_BYTES = 4 * 1024
