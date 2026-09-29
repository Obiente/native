package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap

internal data class DecodedImageState(val loading: Boolean, val image: ImageBitmap?)

/** Each source owns separate presentation state, so replacement never paints the previous image. */
@Composable
internal fun rememberDecodedImage(
    bytes: ByteArray?,
    maximumDimension: Int? = null,
    orientationPolicy: EncodedImageOrientationPolicy = EncodedImageOrientationPolicy.ApplyExif,
): DecodedImageState {
    val state = remember(bytes, maximumDimension, orientationPolicy) {
        mutableStateOf(DecodedImageState(loading = bytes != null, image = null))
    }
    LaunchedEffect(bytes, maximumDimension, orientationPolicy) {
        val image = bytes?.let {
            sharedMediaImageDecoder.decode {
                if (maximumDimension == null) decodePlatformImage(it, orientationPolicy)
                else decodePlatformImageSampled(it, maximumDimension, orientationPolicy)?.image
            }
        }
        state.value = DecodedImageState(loading = false, image = image)
    }
    return state.value
}
