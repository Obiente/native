package dev.obiente.nextcloudnative.app

import androidx.compose.ui.graphics.ImageBitmap

expect fun decodePlatformImage(bytes: ByteArray): ImageBitmap?

data class PlatformDecodedImage(
    val image: ImageBitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

/** Decodes a display-safe bitmap while retaining the source dimensions used for export. */
expect fun decodePlatformImageSampled(bytes: ByteArray, maximumDimension: Int): PlatformDecodedImage?
