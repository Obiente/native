package dev.obiente.nextcloudnative.app

enum class MediaInformationImportance {
    Primary,
    Detail,
    Technical,
}

data class MediaInformationField(
    val key: String,
    val label: String,
    val value: String,
    val importance: MediaInformationImportance = MediaInformationImportance.Detail,
) {
    init {
        require(key.isNotBlank())
        require(label.isNotBlank())
        require(value.isNotBlank())
    }
}

data class MediaInformationSection(
    val key: String,
    val title: String,
    val fields: List<MediaInformationField>,
) {
    init {
        require(key.isNotBlank())
        require(title.isNotBlank())
        require(fields.isNotEmpty())
        require(fields.map(MediaInformationField::key).distinct().size == fields.size)
    }
}

data class MediaInformation(
    val sections: List<MediaInformationSection>,
) {
    init {
        require(sections.map(MediaInformationSection::key).distinct().size == sections.size)
    }

    fun mergedWith(other: MediaInformation): MediaInformation {
        val sectionOrder = (sections + other.sections).map(MediaInformationSection::key).distinct()
        val allSections = (sections + other.sections).groupBy(MediaInformationSection::key)
        return MediaInformation(
            sectionOrder.mapNotNull { sectionKey ->
                val matching = allSections[sectionKey].orEmpty()
                val first = matching.firstOrNull() ?: return@mapNotNull null
                val fieldOrder = matching.flatMap(MediaInformationSection::fields)
                    .map(MediaInformationField::key)
                    .distinct()
                val fieldsByKey = matching.flatMap(MediaInformationSection::fields)
                    .associateBy(MediaInformationField::key)
                MediaInformationSection(
                    key = sectionKey,
                    title = matching.lastOrNull()?.title ?: first.title,
                    fields = fieldOrder.mapNotNull(fieldsByKey::get),
                )
            },
        )
    }
}

fun NextcloudFile.basicMediaInformation(): MediaInformation {
    val overview = buildList {
        add(
            MediaInformationField(
                key = "format",
                label = "Format",
                value = mediaFormatLabel(),
                importance = MediaInformationImportance.Primary,
            ),
        )
        if (mediaWidth != null && mediaHeight != null) {
            add(
                MediaInformationField(
                    key = "dimensions",
                    label = "Dimensions",
                    value = "$mediaWidth x $mediaHeight pixels",
                    importance = MediaInformationImportance.Primary,
                ),
            )
        }
        this@basicMediaInformation.size?.let {
            add(
                MediaInformationField(
                    key = "size",
                    label = "File size",
                    value = formatMediaInformationBytes(it),
                    importance = MediaInformationImportance.Primary,
                ),
            )
        }
        capturedAtEpochSeconds?.let {
            add(
                MediaInformationField(
                    key = "captured",
                    label = "Captured",
                    value = formatMediaEpochSeconds(it),
                    importance = MediaInformationImportance.Primary,
                ),
            )
        }
        mediaDurationSeconds?.let {
            add(
                MediaInformationField(
                    key = "duration",
                    label = "Duration",
                    value = formatMediaDuration(it),
                    importance = MediaInformationImportance.Primary,
                ),
            )
        }
    }
    val fileDetails = buildList {
        add(MediaInformationField("path", "Path", path))
        lastModified?.takeIf(String::isNotBlank)?.let {
            add(MediaInformationField("modified", "Modified", it))
        }
        fileId?.let {
            add(
                MediaInformationField(
                    "file-id",
                    "File ID",
                    it.toString(),
                    MediaInformationImportance.Technical,
                ),
            )
        }
        etag?.takeIf(String::isNotBlank)?.let {
            add(
                MediaInformationField(
                    "etag",
                    "Version",
                    it,
                    MediaInformationImportance.Technical,
                ),
            )
        }
        permissions?.takeIf(String::isNotBlank)?.let {
            add(
                MediaInformationField(
                    "permissions",
                    "DAV permissions",
                    it,
                    MediaInformationImportance.Technical,
                ),
            )
        }
        if (checksums.isNotEmpty()) {
            add(
                MediaInformationField(
                    "checksums",
                    "Checksums",
                    checksums.joinToString("\n"),
                    MediaInformationImportance.Technical,
                ),
            )
        }
    }
    return MediaInformation(
        listOfNotNull(
            overview.takeIf(List<MediaInformationField>::isNotEmpty)?.let {
                MediaInformationSection("overview", "Overview", it)
            },
            fileDetails.takeIf(List<MediaInformationField>::isNotEmpty)?.let {
                MediaInformationSection("file", "File", it)
            },
        ),
    )
}

private fun NextcloudFile.mediaFormatLabel(): String {
    val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        .trim()
        .uppercase()
        .takeIf(String::isNotBlank)
    val family = when {
        extension in setOf("TIF", "TIFF") -> "TIFF image"
        mediaAssetFormat() == MediaAssetFormat.Raw -> "RAW image"
        mediaAssetFormat() in setOf(MediaAssetFormat.Jpeg, MediaAssetFormat.Image) -> "Image"
        mediaAssetFormat() == MediaAssetFormat.Video -> "Video"
        else -> if (isDirectory) "Folder" else "File"
    }
    val type = mimeType?.takeIf(String::isNotBlank)
    return listOfNotNull(extension, family, type)
        .distinct()
        .joinToString(" - ")
}

internal fun formatMediaInformationBytes(bytes: Long): String {
    if (bytes < 0L) return "Unknown"
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return if (unitIndex == 0) {
        "$bytes ${units[unitIndex]}"
    } else {
        val rounded = kotlin.math.round(value * 10.0) / 10.0
        "${if (rounded % 1.0 == 0.0) rounded.toLong() else rounded} ${units[unitIndex]}"
    }
}

internal fun formatMediaDuration(seconds: Int): String {
    require(seconds >= 0)
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val remainingSeconds = seconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${remainingSeconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${remainingSeconds.toString().padStart(2, '0')}"
    }
}

private fun formatMediaEpochSeconds(epochSeconds: Long): String {
    require(epochSeconds >= 0L)
    return epochSeconds.toString()
}
