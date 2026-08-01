package dev.obiente.nextcloudnative.nativeui.preview

import androidx.compose.runtime.Composable
import androidx.compose.material3.Typography
import dev.obiente.nextcloudnative.app.ExternalFileHandoffSupport
import dev.obiente.nextcloudnative.app.MAX_PHOTO_EDIT_SOURCE_BYTES
import dev.obiente.nextcloudnative.app.MAX_RAW_DISPLAY_PREVIEW_BYTES
import dev.obiente.nextcloudnative.app.MarketingCaptureScenario
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
import dev.obiente.nextcloudnative.app.design.NextcloudAppBackground
import dev.obiente.nextcloudnative.app.design.NextcloudNativeTheme
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

internal class NativeTiffMarketingCapture private constructor(
    private val renderedPreview: ByteArray,
    captureIdentity: String,
) {
    private val previewRequests = mutableListOf<Int>()
    private val memoriesRequests = mutableListOf<NextcloudApiRequest>()
    private val davDownloadBounds = mutableListOf<Long>()
    private val observations = mutableListOf<MediaViewerStateObservation>()
    private val rejectedCalls = mutableListOf<String>()
    private val services = networkInertServices()
    private val fixtureSession = NextcloudSession(
        serverUrl = "https://fixture.invalid",
        loginName = "$FIXTURE_USER_ID-$captureIdentity",
        appPassword = "synthetic-capture-password",
    )

    @Composable
    fun Content(darkTheme: Boolean, typography: Typography) {
        NextcloudNativeTheme(darkTheme = darkTheme, typography = typography) {
            NextcloudAppBackground {
                NextcloudMediaViewer(
                    media = listOf(tiffFile),
                    selected = tiffFile,
                    session = fixtureSession,
                    userId = FIXTURE_USER_ID,
                    services = services,
                    taggingAvailable = false,
                    memoriesLivePhotoCapability = MemoriesLivePhotoCapability.NotAdvertised,
                    sharingCapabilities = NextcloudFileSharingCapabilities.Unavailable,
                    onSelect = {},
                    onSourceRemoved = {
                        error("The isolated TIFF capture is read-only.")
                    },
                    onClose = {},
                    initialZoom = HIGH_DETAIL_CAPTURE_ZOOM,
                    onStateObserved = { observation -> observations += observation },
                )
            }
        }
    }

    fun verify() {
        check(rejectedCalls.isEmpty()) {
            "The TIFF capture attempted unsupported service calls: ${rejectedCalls.joinToString()}."
        }
        check(previewRequests.size == 2) {
            "The TIFF capture must request one native preview and one high-detail render."
        }
        check(previewRequests.distinct().size == 2) {
            "The TIFF capture did not request distinct preview and high-detail bounds."
        }
        check(memoriesRequests.size == 2) {
            "The TIFF fallback must probe one bounded preview and one high-detail Memories render."
        }
        memoriesRequests.forEach(::requireExactTiffMemoriesRequest)
        check(
            memoriesRequests.map(NextcloudApiRequest::maximumResponseBytes).toSet() ==
                setOf(
                    MAX_RAW_DISPLAY_PREVIEW_BYTES.toLong(),
                    MAX_PHOTO_EDIT_SOURCE_BYTES,
                ),
        ) {
            "The TIFF Memories probes did not preserve both response bounds."
        }
        check(davDownloadBounds == listOf(MAX_PHOTO_EDIT_SOURCE_BYTES)) {
            "The TIFF high-detail fallback must make one bounded authoritative DAV attempt."
        }
        val final = observations.lastOrNull()
            ?: error("The TIFF viewer did not expose a semantic readiness observation.")
        check(final.readiness == MediaViewerReadiness.HighDetailReady)
        check(final.selectedPath == TIFF_PATH)
        check(final.displayedPath == TIFF_PATH)
        check(final.payloadKind == MediaDisplayPayloadKind.NativeGeneratedPreview)
    }

    private fun networkInertServices(): NextcloudPlatformServices {
        val handler = InvocationHandler { proxy, method, arguments ->
            when (method.name) {
                "toString" -> "NetworkInertTiffCaptureServices"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.singleOrNull()
                "getExternalFileHandoffSupport" ->
                    ExternalFileHandoffSupport.Unsupported("Disabled in isolated captures.")
                "loadNativeMediaPreview" -> {
                    val callArguments = arguments
                        ?: error("The TIFF capture did not receive render arguments.")
                    val file = callArguments.filterIsInstance<NextcloudFile>().singleOrNull()
                        ?: error("The TIFF capture did not receive one media file.")
                    check(file == tiffFile) { "The TIFF capture requested an unexpected file." }
                    val maximumDimension = callArguments.filterIsInstance<Int>().singleOrNull()
                        ?: error("The TIFF capture did not receive one render bound.")
                    previewRequests += maximumDimension
                    renderedPreview.copyOf()
                }
                "executeNextcloudApi" -> {
                    val request = arguments?.filterIsInstance<NextcloudApiRequest>()?.singleOrNull()
                        ?: error("The TIFF capture did not receive one Memories request.")
                    requireExactTiffMemoriesRequest(request)
                    memoriesRequests += request
                    NextcloudApiResponse(
                        status = 404,
                        body = byteArrayOf(),
                        contentType = null,
                        etag = null,
                    )
                }
                "downloadFile" -> {
                    val callArguments = arguments
                        ?: error("The TIFF capture did not receive DAV download arguments.")
                    check(callArguments.filterIsInstance<NextcloudSession>().singleOrNull() == fixtureSession)
                    val stringArguments = callArguments.filterIsInstance<String>()
                    check(FIXTURE_USER_ID in stringArguments)
                    check(TIFF_PATH in stringArguments)
                    val maximumBytes = callArguments.filterIsInstance<Long>().singleOrNull()
                        ?: error("The TIFF capture did not receive one DAV response bound.")
                    davDownloadBounds += maximumBytes
                    error("Synthetic TIFF DAV original unavailable.")
                }
                else -> rejectServiceCall(method)
            }
        }
        return Proxy.newProxyInstance(
            NextcloudPlatformServices::class.java.classLoader,
            arrayOf(NextcloudPlatformServices::class.java),
            handler,
        ) as NextcloudPlatformServices
    }

    private fun rejectServiceCall(method: Method): Nothing {
        rejectedCalls += method.name
        error("Network-inert TIFF capture rejected ${method.name}.")
    }

    companion object {
        fun forScenarioOrNull(
            scenario: MarketingCaptureScenario,
            captureIdentity: String,
        ): NativeTiffMarketingCapture? = if (
            scenario == MarketingCaptureScenario.NativeTiffPreviewMobile
        ) {
            NativeTiffMarketingCapture(loadRawCaptureFixture(), captureIdentity)
        } else {
            null
        }
    }
}

private fun requireExactTiffMemoriesRequest(request: NextcloudApiRequest) {
    check(request.method == NextcloudApiMethod.GET)
    check(request.relativePath == TIFF_MEMORIES_RENDER_PATH)
    check(request.queryParameters == mapOf("etag" to TIFF_ETAG))
    check(request.body == null)
    check(!request.ocsApiRequest)
    check(
        request.maximumResponseBytes == MAX_RAW_DISPLAY_PREVIEW_BYTES.toLong() ||
            request.maximumResponseBytes == MAX_PHOTO_EDIT_SOURCE_BYTES,
    )
}

private const val FIXTURE_USER_ID = "fixture-user"
private const val FIXTURE_FILE_ID = 2_490L
private const val TIFF_ETAG = "fixture-tiff-native-render"
private const val TIFF_PATH = "Photos/Fixtures/archival-scan.tif"
private const val TIFF_MEMORIES_RENDER_PATH =
    "/index.php/apps/memories/api/image/decodable/$FIXTURE_FILE_ID"
private const val HIGH_DETAIL_CAPTURE_ZOOM = 2.5f


private val tiffFile = NextcloudFile(
    path = TIFF_PATH,
    name = "archival-scan.tif",
    isDirectory = false,
    mimeType = "image/tiff",
    size = 48L * 1024L * 1024L,
    lastModified = "2026-07-29T09:30:00Z",
    fileId = FIXTURE_FILE_ID,
    hasPreview = false,
    etag = TIFF_ETAG,
    originalAccessAllowed = true,
    permissions = "RG",
)
