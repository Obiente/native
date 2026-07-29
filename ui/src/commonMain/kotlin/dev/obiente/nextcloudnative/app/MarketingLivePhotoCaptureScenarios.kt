package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

@Composable
internal fun MarketingLivePhotoMotionFailureScenario(
    still: ImageBitmap,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Image(
            bitmap = still,
            contentDescription = "Synthetic Live Photo still",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        NativeVideoFailureOverlay(
            failure = NativeVideoPlaybackFailure.DecodeFailed(
                NativeVideoFormatSummary(
                    mimeType = "video/hevc",
                    codec = "hvc1",
                    width = 1_728,
                    height = 1_296,
                    frameRate = 30f,
                ),
            ),
            motionOnly = true,
            showCompatibilityAction = true,
            showExternalAction = false,
            externalActionEnabled = false,
            externalOpening = false,
            onCompatibilityPlayback = {},
            onOpenExternal = {},
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = NextcloudSpacing.XLarge),
        )
    }
}
