package dev.obiente.nextcloudnative.app

import androidx.compose.ui.graphics.ImageBitmap

enum class EncodedImageOrientationPolicy {
    /** Pixel storage still uses the file's EXIF orientation and must be normalized exactly once. */
    ApplyExif,

    /** A trusted server renderer has already returned upright pixels. Ignore stale copied EXIF. */
    PixelsAlreadyUpright,
}

expect fun decodePlatformImage(
    bytes: ByteArray,
    orientationPolicy: EncodedImageOrientationPolicy = EncodedImageOrientationPolicy.ApplyExif,
): ImageBitmap?

data class PlatformDecodedImage(
    val image: ImageBitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

/** Decodes a display-safe bitmap while retaining the source dimensions used for export. */
expect fun decodePlatformImageSampled(
    bytes: ByteArray,
    maximumDimension: Int,
    orientationPolicy: EncodedImageOrientationPolicy = EncodedImageOrientationPolicy.ApplyExif,
): PlatformDecodedImage?
