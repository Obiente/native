package dev.obiente.nextcloudnative.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun decodePlatformImage(bytes: ByteArray): ImageBitmap? {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    return bitmap.applyEncodedOrientation(encodedImageOrientation(bytes)).asImageBitmap()
}

actual fun decodePlatformImageSampled(bytes: ByteArray, maximumDimension: Int): PlatformDecodedImage? {
    require(maximumDimension > 0)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maximumDimension || bounds.outHeight / sampleSize > maximumDimension) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    val orientation = encodedImageOrientation(bytes)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        ?.applyEncodedOrientation(orientation)
        ?: return null
    return PlatformDecodedImage(
        image = bitmap.asImageBitmap(),
        sourceWidth = if (orientationSwapsDimensions(orientation)) bounds.outHeight else bounds.outWidth,
        sourceHeight = if (orientationSwapsDimensions(orientation)) bounds.outWidth else bounds.outHeight,
    )
}

private fun Bitmap.applyEncodedOrientation(orientation: Int): Bitmap {
    if (orientation == 1) return this
    val matrix = Matrix().apply {
        when (orientation) {
            2 -> setScale(-1f, 1f)
            3 -> setRotate(180f)
            4 -> {
                setRotate(180f)
                postScale(-1f, 1f)
            }
            5 -> {
                setRotate(90f)
                postScale(-1f, 1f)
            }
            6 -> setRotate(90f)
            7 -> {
                setRotate(-90f)
                postScale(-1f, 1f)
            }
            8 -> setRotate(-90f)
        }
    }
    return runCatching {
        Bitmap.createBitmap(this, 0, 0, width, height, matrix, true).also { transformed ->
            if (transformed !== this) recycle()
        }
    }.getOrElse { this }
}
