package dev.obiente.nextcloudnative.app

fun presentFiles(
    files: List<NextcloudFile>,
    query: String,
): List<NextcloudFile> {
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    return files.asSequence()
        .filter { file ->
            terms.isEmpty() || terms.all { term ->
                file.name.contains(term, ignoreCase = true)
            }
        }
        .sortedWith(
            compareByDescending<NextcloudFile> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.path },
        )
        .toList()
}
