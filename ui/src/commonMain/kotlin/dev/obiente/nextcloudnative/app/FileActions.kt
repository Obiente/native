package dev.obiente.nextcloudnative.app

internal enum class FileAction {
    Open,
    Preview,
    Details,
    EditText,
}

internal fun availableFileActions(file: NextcloudFile): List<FileAction> = buildList {
    if (file.isDirectory) {
        add(FileAction.Open)
    } else if (
        describeDocument(file).method != DocumentPreviewMethod.Unsupported ||
        (file.hasPreview && file.fileId != null)
    ) {
        add(FileAction.Preview)
    }

    add(FileAction.Details)

    if (!file.isDirectory && file.isEditableText()) {
        add(FileAction.EditText)
    }
}

internal fun primaryFileActionLabel(file: NextcloudFile): String = when {
    file.isDirectory -> "Open folder ${file.name}"
    file.isEditableText() -> "Edit ${file.name}"
    file.hasPreview && file.fileId != null -> "Preview ${file.name}"
    else -> "Show details for ${file.name}"
}

internal fun NextcloudFile.isEditableText(): Boolean {
    if (mimeType?.startsWith("text/") == true) return true
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in editableTextExtensions
}

private val editableTextExtensions = setOf(
    "txt", "md", "markdown", "json", "xml", "yaml", "yml", "toml", "ini", "conf", "csv", "log",
    "html", "css", "scss", "js", "jsx", "ts", "tsx", "kt", "kts", "java", "py", "go", "rs", "sql",
    "sh", "fish", "properties", "gradle",
)
