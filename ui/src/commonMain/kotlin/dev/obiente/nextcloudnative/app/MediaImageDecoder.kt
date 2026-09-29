package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Limits native decoder pressure and keeps synchronous decoding off presentation dispatchers. */
internal class MediaImageDecoder(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    maximumConcurrentDecodes: Int = 2,
) {
    private val permits = Semaphore(maximumConcurrentDecodes)

    suspend fun <T> decode(block: () -> T): T = permits.withPermit {
        withContext(dispatcher) { block() }
    }
}

internal val sharedMediaImageDecoder = MediaImageDecoder()

internal suspend fun decodePlatformImageInBackground(
    bytes: ByteArray,
    orientationPolicy: EncodedImageOrientationPolicy = EncodedImageOrientationPolicy.ApplyExif,
) = sharedMediaImageDecoder.decode { decodePlatformImage(bytes, orientationPolicy) }

internal suspend fun decodePlatformImageSampledInBackground(
    bytes: ByteArray,
    maximumDimension: Int,
    orientationPolicy: EncodedImageOrientationPolicy = EncodedImageOrientationPolicy.ApplyExif,
) = sharedMediaImageDecoder.decode { decodePlatformImageSampled(bytes, maximumDimension, orientationPolicy) }
