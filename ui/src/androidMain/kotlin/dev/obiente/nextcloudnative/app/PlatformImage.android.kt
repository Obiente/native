package dev.obiente.nextcloudnative.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

actual fun decodePlatformImage(
    bytes: ByteArray,
    orientationPolicy: EncodedImageOrientationPolicy,
): ImageBitmap? = decodePlatformImageSampled(
    bytes = bytes,
    maximumDimension = DEFAULT_PLATFORM_IMAGE_DIMENSION,
    orientationPolicy = orientationPolicy,
)?.image

actual fun decodePlatformImageSampled(
    bytes: ByteArray,
    maximumDimension: Int,
    orientationPolicy: EncodedImageOrientationPolicy,
): PlatformDecodedImage? {
    require(maximumDimension > 0)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val plan = boundedImageDecodePlan(
        sourceWidth = bounds.outWidth,
        sourceHeight = bounds.outHeight,
        maximumDimension = maximumDimension,
        maximumPixels = minOf(
            DEFAULT_MAXIMUM_DECODED_IMAGE_PIXELS,
            maximumDimension.toLong() * maximumDimension.toLong(),
        ),
    )
    val options = BitmapFactory.Options().apply {
        inSampleSize = plan.sampleSize
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    val orientation = bytes.orientationFor(orientationPolicy)
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    val bounded = decoded.scaleTo(plan.targetWidth, plan.targetHeight) ?: run {
        decoded.recycle()
        return null
    }
    val bitmap = bounded.applyEncodedOrientation(orientation) ?: run {
        bounded.recycle()
        return null
    }
    return PlatformDecodedImage(
        image = bitmap.asImageBitmap(),
        sourceWidth = if (orientationSwapsDimensions(orientation)) bounds.outHeight else bounds.outWidth,
        sourceHeight = if (orientationSwapsDimensions(orientation)) bounds.outWidth else bounds.outHeight,
    )
}

private fun Bitmap.scaleTo(targetWidth: Int, targetHeight: Int): Bitmap? {
    if (width == targetWidth && height == targetHeight) return this
    return runCatching {
        Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true).also { scaled ->
            if (scaled !== this) recycle()
        }
    }.getOrNull()
}

private fun Bitmap.applyEncodedOrientation(orientation: Int): Bitmap? {
    if (orientation == 1) return this
    return runCatching {
        val (source, destination) = exifOrientationAffinePoints(width, height, orientation)
        val matrix = Matrix().apply {
            check(setPolyToPoly(source, 0, destination, 0, 3)) {
                "Could not construct the EXIF orientation transform."
            }
        }
        Bitmap.createBitmap(this, 0, 0, width, height, matrix, true).also { transformed ->
            if (transformed !== this) recycle()
        }
    }.getOrNull()
}

private fun ByteArray.orientationFor(policy: EncodedImageOrientationPolicy): Int = when (policy) {
    EncodedImageOrientationPolicy.ApplyExif -> runCatching {
        ByteArrayInputStream(this).use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.takeIf { it in 1..8 } ?: 1
    }.getOrElse {
        // The bounded common parser keeps JPEG/TIFF orientation available if a vendor-specific
        // payload is rejected by ExifInterface.
        encodedImageOrientation(this)
    }
    EncodedImageOrientationPolicy.PixelsAlreadyUpright -> 1
}

private const val DEFAULT_PLATFORM_IMAGE_DIMENSION = 2_048
