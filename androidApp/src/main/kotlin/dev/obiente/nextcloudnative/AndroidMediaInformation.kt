package dev.obiente.nextcloudnative

import androidx.exifinterface.media.ExifInterface
import dev.obiente.nextcloudnative.app.MediaInformation
import dev.obiente.nextcloudnative.app.MediaInformationField
import dev.obiente.nextcloudnative.app.MediaInformationImportance
import dev.obiente.nextcloudnative.app.MediaInformationSection
import java.io.ByteArrayInputStream
import java.util.Locale

internal fun extractAndroidEmbeddedMediaInformation(
    encodedPrefix: ByteArray,
): MediaInformation? {
    if (encodedPrefix.isEmpty()) return null
    val exif = runCatching {
        ByteArrayInputStream(encodedPrefix).use { input ->
            ExifInterface(input)
        }
    }.getOrNull() ?: return null

    val width = exif.positiveInt(
        ExifInterface.TAG_PIXEL_X_DIMENSION,
        ExifInterface.TAG_IMAGE_WIDTH,
    )
    val height = exif.positiveInt(
        ExifInterface.TAG_PIXEL_Y_DIMENSION,
        ExifInterface.TAG_IMAGE_LENGTH,
    )
    val make = exif.text(ExifInterface.TAG_MAKE)
    val model = exif.text(ExifInterface.TAG_MODEL)
    val camera = listOfNotNull(make, model)
        .distinct()
        .joinToString(" ")
        .takeIf(String::isNotBlank)
    val lens = listOfNotNull(
        exif.text(ExifInterface.TAG_LENS_MAKE),
        exif.text(ExifInterface.TAG_LENS_MODEL),
    ).distinct().joinToString(" ").takeIf(String::isNotBlank)

    val overview = buildList {
        if (width != null && height != null) {
            add(
                MediaInformationField(
                    key = "dimensions",
                    label = "Dimensions",
                    value = "$width x $height pixels",
                    importance = MediaInformationImportance.Primary,
                ),
            )
        }
        exif.text(ExifInterface.TAG_DATETIME_ORIGINAL)?.let {
            add(
                MediaInformationField(
                    key = "captured",
                    label = "Captured",
                    value = it,
                    importance = MediaInformationImportance.Primary,
                ),
            )
        }
    }
    val cameraFields = buildList {
        camera?.let { add(MediaInformationField("camera", "Camera", it)) }
        lens?.let { add(MediaInformationField("lens", "Lens", it)) }
        exif.rational(ExifInterface.TAG_EXPOSURE_TIME)?.let {
            add(MediaInformationField("exposure", "Exposure time", "$it s"))
        }
        exif.rational(ExifInterface.TAG_F_NUMBER)?.let {
            add(MediaInformationField("aperture", "Aperture", "f/$it"))
        }
        exif.text(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let {
            add(MediaInformationField("iso", "ISO", it))
        }
        exif.rational(ExifInterface.TAG_FOCAL_LENGTH)?.let {
            add(MediaInformationField("focal-length", "Focal length", "$it mm"))
        }
        exif.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE, -1)
            .takeIf { it >= 0 }
            ?.let {
                add(
                    MediaInformationField(
                        "white-balance",
                        "White balance",
                        if (it == 0) "Automatic" else "Manual",
                    ),
                )
            }
        exif.getAttributeInt(ExifInterface.TAG_FLASH, -1)
            .takeIf { it >= 0 }
            ?.let {
                add(
                    MediaInformationField(
                        "flash",
                        "Flash",
                        if (it and 1 == 1) "Fired" else "Did not fire",
                    ),
                )
            }
        exif.latLong?.let { coordinates ->
            if (coordinates.size >= 2) {
                add(
                    MediaInformationField(
                        "location",
                        "Location",
                        String.format(
                            Locale.ROOT,
                            "%.5f, %.5f",
                            coordinates[0],
                            coordinates[1],
                        ),
                    ),
                )
            }
        }
    }
    val formatFields = buildList {
        exif.text(ExifInterface.TAG_BITS_PER_SAMPLE)?.let {
            add(MediaInformationField("bits-per-sample", "Bits per sample", it))
        }
        exif.getAttributeInt(ExifInterface.TAG_SAMPLES_PER_PIXEL, -1)
            .takeIf { it > 0 }
            ?.let {
                add(MediaInformationField("samples-per-pixel", "Samples per pixel", it.toString()))
            }
        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1)
            .takeIf { it in 1..8 }
            ?.let {
                add(MediaInformationField("orientation", "Orientation", exifOrientationLabel(it)))
            }
        exif.getAttributeInt(ExifInterface.TAG_COLOR_SPACE, -1)
            .takeIf { it >= 0 }
            ?.let {
                add(
                    MediaInformationField(
                        "color-space",
                        "Color space",
                        when (it) {
                            1 -> "sRGB"
                            65_535 -> "Uncalibrated"
                            else -> it.toString()
                        },
                    ),
                )
            }
        exif.getAttributeInt(ExifInterface.TAG_COMPRESSION, -1)
            .takeIf { it >= 0 }
            ?.let {
                add(
                    MediaInformationField(
                        "compression",
                        "Compression",
                        exifCompressionLabel(it),
                        MediaInformationImportance.Technical,
                    ),
                )
            }
    }
    val sections = listOfNotNull(
        overview.asSection("overview", "Overview"),
        cameraFields.asSection("camera", "Camera"),
        formatFields.asSection("format-details", "Format details"),
    )
    return sections.takeIf(List<MediaInformationSection>::isNotEmpty)?.let(::MediaInformation)
}

internal fun AndroidTiffInformation.toMediaInformation(): MediaInformation {
    val bits = bitsPerSample.distinct().joinToString(", ")
    return MediaInformation(
        sections = listOf(
            MediaInformationSection(
                key = "overview",
                title = "Overview",
                fields = listOf(
                    MediaInformationField(
                        key = "dimensions",
                        label = "Dimensions",
                        value = "$width x $height pixels",
                        importance = MediaInformationImportance.Primary,
                    ),
                ),
            ),
            MediaInformationSection(
                key = "format-details",
                title = "TIFF details",
                fields = listOf(
                    MediaInformationField("bits-per-sample", "Bits per sample", bits),
                    MediaInformationField("samples-per-pixel", "Samples per pixel", samplesPerPixel.toString()),
                    MediaInformationField("compression", "Compression", exifCompressionLabel(compression)),
                    MediaInformationField(
                        "photometric",
                        "Color interpretation",
                        tiffPhotometricLabel(photometricInterpretation),
                    ),
                    MediaInformationField("orientation", "Orientation", exifOrientationLabel(orientation)),
                    MediaInformationField(
                        "layout",
                        "Storage layout",
                        when {
                            tiled -> "Tiles"
                            planarConfiguration == 2 -> "Separate color planes"
                            else -> "Interleaved strips"
                        },
                    ),
                    MediaInformationField(
                        "pages",
                        "Pages",
                        if (hasAdditionalPages) "Multiple pages" else "1",
                    ),
                ),
            ),
        ),
    )
}

private fun ExifInterface.text(tag: String): String? =
    getAttribute(tag)?.trim()?.takeIf(String::isNotBlank)?.take(512)

private fun ExifInterface.positiveInt(vararg tags: String): Int? =
    tags.firstNotNullOfOrNull { tag ->
        getAttributeInt(tag, -1).takeIf { it > 0 }
    }

private fun ExifInterface.rational(tag: String): String? {
    val value = getAttributeDouble(tag, Double.NaN)
    if (!value.isFinite() || value <= 0.0) return null
    val rounded = kotlin.math.round(value * 10_000.0) / 10_000.0
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
}

private fun List<MediaInformationField>.asSection(
    key: String,
    title: String,
): MediaInformationSection? = takeIf(List<MediaInformationField>::isNotEmpty)
    ?.let { MediaInformationSection(key, title, it) }

private fun exifOrientationLabel(value: Int): String = when (value) {
    1 -> "Normal"
    2 -> "Mirrored horizontally"
    3 -> "Rotated 180 degrees"
    4 -> "Mirrored vertically"
    5 -> "Mirrored and rotated 270 degrees"
    6 -> "Rotated 90 degrees"
    7 -> "Mirrored and rotated 90 degrees"
    8 -> "Rotated 270 degrees"
    else -> value.toString()
}

private fun exifCompressionLabel(value: Int): String = when (value) {
    1 -> "Uncompressed"
    5 -> "LZW"
    6, 7 -> "JPEG"
    8, 32946 -> "Deflate"
    32773 -> "PackBits"
    else -> "Code $value"
}

private fun tiffPhotometricLabel(value: Int): String = when (value) {
    0 -> "White is zero"
    1 -> "Black is zero"
    2 -> "RGB"
    3 -> "Palette"
    5 -> "CMYK"
    6 -> "YCbCr"
    8 -> "CIELAB"
    else -> "Code $value"
}
