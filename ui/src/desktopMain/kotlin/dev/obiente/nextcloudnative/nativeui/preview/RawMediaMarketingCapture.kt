package dev.obiente.nextcloudnative.nativeui.preview

import androidx.compose.runtime.Composable
import dev.obiente.nextcloudnative.app.ExternalFileHandoffSupport
import dev.obiente.nextcloudnative.app.MAX_PHOTO_EDIT_SOURCE_BYTES
import dev.obiente.nextcloudnative.app.MAX_RAW_DISPLAY_PREVIEW_BYTES
import dev.obiente.nextcloudnative.app.MediaDisplayPayloadKind
import dev.obiente.nextcloudnative.app.MediaViewerReadiness
import dev.obiente.nextcloudnative.app.MediaViewerStateObservation
import dev.obiente.nextcloudnative.app.MemoriesLivePhotoCapability
import dev.obiente.nextcloudnative.app.NextcloudApiMethod
import dev.obiente.nextcloudnative.app.NextcloudApiRequest
import dev.obiente.nextcloudnative.app.NextcloudApiResponse
import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.NextcloudFileSharingCapabilities
import dev.obiente.nextcloudnative.app.NextcloudMediaViewer
import dev.obiente.nextcloudnative.app.NextcloudPlatformServices
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.MarketingCaptureScenario
import dev.obiente.nextcloudnative.app.design.NextcloudAppBackground
import dev.obiente.nextcloudnative.app.design.NextcloudNativeTheme
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import org.jetbrains.skia.Image

internal class RawMediaMarketingCapture private constructor(
    private val mode: RawCaptureMode,
    private val renderedPreview: ByteArray,
) {
    private val requests = mutableListOf<NextcloudApiRequest>()
    private val observations = mutableListOf<MediaViewerStateObservation>()
    private val rejectedCalls = mutableListOf<String>()
    private val services: NextcloudPlatformServices = networkInertServices()

    init {
        Image.makeFromEncoded(renderedPreview).use { image ->
            require(image.width == RAW_CAPTURE_FIXTURE_WIDTH && image.height == RAW_CAPTURE_FIXTURE_HEIGHT) {
                "The rendered RAW preview fixture must decode to " +
                    "${RAW_CAPTURE_FIXTURE_WIDTH}x${RAW_CAPTURE_FIXTURE_HEIGHT}."
            }
        }
    }

    @Composable
    fun Content() {
        NextcloudNativeTheme(darkTheme = true) {
            NextcloudAppBackground {
                NextcloudMediaViewer(
                    media = listOf(rawFile),
                    selected = rawFile,
                    session = fixtureSession,
                    userId = FIXTURE_USER_ID,
                    services = services,
                    taggingAvailable = false,
                    memoriesLivePhotoCapability = MemoriesLivePhotoCapability.NotAdvertised,
                    sharingCapabilities = NextcloudFileSharingCapabilities.Unavailable,
                    onSelect = { selected ->
                        check(selected.path == RAW_PATH) {
                            "The isolated RAW fixture cannot select another media path."
                        }
                    },
                    onSourceRemoved = {
                        error("The isolated RAW capture is read-only.")
                    },
                    onClose = {},
                    initialZoom = if (mode == RawCaptureMode.HighDetail) HIGH_DETAIL_CAPTURE_ZOOM else 1f,
                    onStateObserved = { observation -> observations += observation },
                )
            }
        }
    }

    fun verify() {
        check(rejectedCalls.isEmpty()) {
            "The RAW capture attempted unsupported service calls: ${rejectedCalls.joinToString()}."
        }
        check(requests.isNotEmpty()) { "The RAW capture did not request the Memories render route." }
        requests.forEach(::requireExactMemoriesRenderRequest)

        val final = observations.lastOrNull()
            ?: error("The RAW viewer did not expose a semantic readiness observation.")
        check(final.selectedPath == RAW_PATH) { "The RAW action target changed during capture." }

        when (mode) {
            RawCaptureMode.Loading -> {
                check(final.readiness == MediaViewerReadiness.Loading)
                check(final.displayedPath == null)
                check(requests.size == 1)
            }
            RawCaptureMode.Error -> {
                check(final.readiness == MediaViewerReadiness.RenderUnavailable)
                check(final.displayedPath == null)
                check(requests.size == 1)
            }
            RawCaptureMode.Ready -> {
                requireRenderedRawObservation(final, MediaViewerReadiness.RenderReady)
                check(requests.size == 1)
            }
            RawCaptureMode.HighDetail -> {
                requireRenderedRawObservation(final, MediaViewerReadiness.HighDetailReady)
                check(final.requestedZoom == HIGH_DETAIL_CAPTURE_ZOOM)
                check(requests.size == 2)
                check(
                    requests.map(NextcloudApiRequest::maximumResponseBytes).toSet() ==
                        setOf(
                            MAX_RAW_DISPLAY_PREVIEW_BYTES.toLong(),
                            MAX_PHOTO_EDIT_SOURCE_BYTES,
                        ),
                ) {
                    "The zoomed capture must request one preview render and one bounded high-detail render."
                }
            }
        }
    }

    private fun requireRenderedRawObservation(
        observation: MediaViewerStateObservation,
        readiness: MediaViewerReadiness,
    ) {
        check(observation.readiness == readiness)
        check(observation.displayedPath == RAW_PATH) {
            "The rendered path must remain the selected standalone RAW file."
        }
        check(observation.payloadKind == MediaDisplayPayloadKind.MemoriesRawRender) {
            "The fixture must be identified as a Memories-rendered RAW preview."
        }
    }

    private fun networkInertServices(): NextcloudPlatformServices {
        val handler = InvocationHandler { proxy, method, arguments ->
            when (method.name) {
                "toString" -> "NetworkInertRawCaptureServices"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.singleOrNull()
                "getExternalFileHandoffSupport" ->
                    ExternalFileHandoffSupport.Unsupported("Disabled in isolated captures.")
                "executeNextcloudApi" -> executeExactMemoriesRequest(arguments)
                else -> rejectServiceCall(method)
            }
        }
        return Proxy.newProxyInstance(
            NextcloudPlatformServices::class.java.classLoader,
            arrayOf(NextcloudPlatformServices::class.java),
            handler,
        ) as NextcloudPlatformServices
    }

    private fun executeExactMemoriesRequest(arguments: Array<out Any?>?): Any {
        val request = arguments?.filterIsInstance<NextcloudApiRequest>()?.singleOrNull()
            ?: error("The capture transport did not receive one API request.")
        requireExactMemoriesRenderRequest(request)
        requests += request
        return when (mode) {
            RawCaptureMode.Loading -> COROUTINE_SUSPENDED
            RawCaptureMode.Error -> NextcloudApiResponse(
                status = 503,
                body = "Synthetic renderer unavailable".encodeToByteArray(),
                contentType = "text/plain",
                etag = null,
            )
            RawCaptureMode.Ready,
            RawCaptureMode.HighDetail,
            -> NextcloudApiResponse(
                status = 200,
                body = renderedPreview.copyOf(),
                contentType = "image/png",
                etag = FIXTURE_ETAG,
            )
        }
    }

    private fun rejectServiceCall(method: Method): Nothing {
        rejectedCalls += method.name
        error("Network-inert RAW capture rejected ${method.name}.")
    }

    companion object {
        fun forScenarioOrNull(
            scenario: MarketingCaptureScenario,
        ): RawMediaMarketingCapture? {
            val mode = when (scenario) {
                MarketingCaptureScenario.RawPreviewLoadingMobile -> RawCaptureMode.Loading
                MarketingCaptureScenario.RawPreviewErrorMobile -> RawCaptureMode.Error
                MarketingCaptureScenario.RawPreviewMemoriesReadyMobile -> RawCaptureMode.Ready
                MarketingCaptureScenario.RawPreviewHighDetailDesktop -> RawCaptureMode.HighDetail
                else -> return null
            }
            val bytes = loadRawCaptureFixture()
            return RawMediaMarketingCapture(mode, bytes)
        }
    }
}

internal fun requireExactMemoriesRenderRequest(request: NextcloudApiRequest) {
    check(request.method == NextcloudApiMethod.GET)
    check(request.relativePath == MEMORIES_RAW_RENDER_PATH)
    check(request.queryParameters == mapOf("etag" to FIXTURE_ETAG))
    check(request.body == null)
    check(!request.ocsApiRequest)
    check(
        request.maximumResponseBytes == MAX_RAW_DISPLAY_PREVIEW_BYTES.toLong() ||
            request.maximumResponseBytes == MAX_PHOTO_EDIT_SOURCE_BYTES,
    )
}

private enum class RawCaptureMode {
    Loading,
    Error,
    Ready,
    HighDetail,
}

internal fun loadRawCaptureFixture(): ByteArray = requireNotNull(
    RawMediaMarketingCapture::class.java.getResourceAsStream(RAW_FIXTURE_RESOURCE),
) { "The repository-owned rendered RAW preview fixture is missing." }.use { it.readBytes() }

internal const val RAW_CAPTURE_FIXTURE_WIDTH = 720
internal const val RAW_CAPTURE_FIXTURE_HEIGHT = 540
private const val FIXTURE_USER_ID = "fixture-user"
private const val FIXTURE_FILE_ID = 2_180L
private const val FIXTURE_ETAG = "fixture-raw-render"
private const val RAW_PATH = "Photos/Fixtures/SAMPLE0001.DNG"
private const val RAW_FIXTURE_RESOURCE = "/marketing/raw-render-fixture.png"
internal const val MEMORIES_RAW_RENDER_PATH =
    "/index.php/apps/memories/api/image/decodable/$FIXTURE_FILE_ID"
private const val HIGH_DETAIL_CAPTURE_ZOOM = 2.5f

private val fixtureSession = NextcloudSession(
    serverUrl = "https://fixture.invalid",
    loginName = FIXTURE_USER_ID,
    appPassword = "synthetic-capture-password",
)

private val rawFile = NextcloudFile(
    path = RAW_PATH,
    name = "SAMPLE0001.DNG",
    isDirectory = false,
    mimeType = "image/x-adobe-dng",
    size = 64L * 1024L * 1024L,
    lastModified = "2026-07-26T10:15:00Z",
    fileId = FIXTURE_FILE_ID,
    hasPreview = false,
    etag = FIXTURE_ETAG,
    originalAccessAllowed = true,
    permissions = "RG",
)
