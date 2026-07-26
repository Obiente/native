package dev.obiente.nextcloudnative.nativeui.preview

import dev.obiente.nextcloudnative.app.MAX_RAW_DISPLAY_PREVIEW_BYTES
import dev.obiente.nextcloudnative.app.NextcloudApiMethod
import dev.obiente.nextcloudnative.app.NextcloudApiRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.jetbrains.skia.Image

class RawMediaMarketingCaptureTest {
    @Test
    fun repositoryFixtureDecodesToItsDeclaredRenderedPreviewSize() {
        Image.makeFromEncoded(loadRawCaptureFixture()).use { image ->
            assertEquals(RAW_CAPTURE_FIXTURE_WIDTH, image.width)
            assertEquals(RAW_CAPTURE_FIXTURE_HEIGHT, image.height)
        }
    }

    @Test
    fun captureContractAcceptsOnlyTheExactMemoriesRawRenderRoute() {
        val exact = rawRenderRequest()

        requireExactMemoriesRenderRequest(exact)

        assertFailsWith<IllegalStateException> {
            requireExactMemoriesRenderRequest(exact.copy(relativePath = "$MEMORIES_RAW_RENDER_PATH/extra"))
        }
        assertFailsWith<IllegalStateException> {
            requireExactMemoriesRenderRequest(exact.copy(queryParameters = emptyMap()))
        }
        assertFailsWith<IllegalStateException> {
            requireExactMemoriesRenderRequest(exact.copy(method = NextcloudApiMethod.POST))
        }
        assertFailsWith<IllegalStateException> {
            requireExactMemoriesRenderRequest(exact.copy(body = byteArrayOf(1)))
        }
    }

    private fun rawRenderRequest() = NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = MEMORIES_RAW_RENDER_PATH,
        queryParameters = mapOf("etag" to "fixture-raw-render"),
        maximumResponseBytes = MAX_RAW_DISPLAY_PREVIEW_BYTES.toLong(),
    )
}
