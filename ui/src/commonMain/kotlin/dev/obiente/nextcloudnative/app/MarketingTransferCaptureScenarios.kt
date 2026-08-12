package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable

/**
 * Deterministic, privacy-safe transfer-center states rendered through the production screen.
 *
 * These scenarios deliberately contain no account, device, server, or personal media data.
 */
@Composable
internal fun MarketingMediaTransferScenario(scenario: MarketingCaptureScenario) {
    val fixture = marketingMediaTransferFixture(scenario)
    MediaTransferCenterScreen(
        state = fixture.state,
        loading = fixture.loading,
        busyLocalKey = fixture.busyLocalKey,
        clearingCompleted = false,
        statusMessage = fixture.statusMessage,
        statusMessageIsError = fixture.statusMessageIsError,
        onBack = {},
        onSelectSection = {},
        onLoadNewer = {},
        onLoadOlder = {},
        onRetry = {},
        onAction = { _, _ -> },
        onClearCompleted = {},
        visibleActions = { PRODUCTION_MEDIA_TRANSFER_ACTIONS },
    )
}

internal data class MarketingMediaTransferFixture(
    val state: MediaTransferCenterState,
    val loading: Boolean = false,
    val busyLocalKey: String? = null,
    val statusMessage: String? = null,
    val statusMessageIsError: Boolean = false,
)

internal fun marketingMediaTransferFixture(
    scenario: MarketingCaptureScenario,
): MarketingMediaTransferFixture = when (scenario) {
    MarketingCaptureScenario.TransferMobilePending -> MarketingMediaTransferFixture(
        state = marketingTransferState(
            section = MediaTransferSection.Pending,
            summary = marketingTransferSummary,
            records = listOf(
                marketingTransferRecord(
                    state = MediaBackupTransferState.Pending,
                    index = 1,
                    displayName = "Morning-walk.jpg",
                    size = 4_812_736,
                    attempts = 0,
                ),
                marketingTransferRecord(
                    state = MediaBackupTransferState.Pending,
                    index = 2,
                    displayName = "Garden-video.mp4",
                    size = 82_837_504,
                    attempts = 0,
                ),
                marketingTransferRecord(
                    state = MediaBackupTransferState.Pending,
                    index = 3,
                    displayName = "Recipe-notes.png",
                    size = 1_843_200,
                    attempts = 0,
                ),
            ),
        ),
    )

    MarketingCaptureScenario.TransferMobileFailed -> MarketingMediaTransferFixture(
        state = marketingTransferState(
            section = MediaTransferSection.Failed,
            summary = marketingTransferSummary,
            records = listOf(
                marketingTransferRecord(
                    state = MediaBackupTransferState.Failed,
                    index = 4,
                    displayName = "Train-window.jpg",
                    size = 6_291_456,
                    attempts = 2,
                    failureMessage = "The connection ended before the upload completed.",
                ),
                marketingTransferRecord(
                    state = MediaBackupTransferState.Failed,
                    index = 5,
                    displayName = "Workshop-clip.mp4",
                    size = 148_897_792,
                    attempts = 1,
                    failureMessage = "Nextcloud could not be reached.",
                ),
            ),
        ),
        statusMessage = "Could not refresh. Showing saved transfer history.",
        statusMessageIsError = true,
    )

    MarketingCaptureScenario.TransferDesktopActive -> MarketingMediaTransferFixture(
        state = marketingTransferState(
            section = MediaTransferSection.Active,
            summary = marketingTransferSummary,
            records = listOf(
                marketingTransferRecord(
                    state = MediaBackupTransferState.Uploading,
                    index = 6,
                    displayName = "Summer-trip.mp4",
                    size = 524_288_000,
                    attempts = 1,
                ),
                marketingTransferRecord(
                    state = MediaBackupTransferState.Uploading,
                    index = 7,
                    displayName = "Studio-session.wav",
                    size = 188_743_680,
                    attempts = 1,
                ),
            ),
        ),
        busyLocalKey = "fixture-media:6",
    )

    MarketingCaptureScenario.TransferDesktopCompleted -> MarketingMediaTransferFixture(
        state = marketingTransferState(
            section = MediaTransferSection.Completed,
            summary = marketingTransferSummary,
            records = listOf(
                marketingTransferRecord(
                    state = MediaBackupTransferState.Succeeded,
                    index = 8,
                    displayName = "Family-album.jpg",
                    size = 7_340_032,
                    attempts = 1,
                ),
                marketingTransferRecord(
                    state = MediaBackupTransferState.Succeeded,
                    index = 9,
                    displayName = "Scanned-letter.png",
                    size = 2_621_440,
                    attempts = 1,
                ),
                marketingTransferRecord(
                    state = MediaBackupTransferState.Succeeded,
                    index = 10,
                    displayName = "Concert.mp4",
                    size = 231_735_296,
                    attempts = 1,
                ),
            ),
            canLoadNewer = true,
            nextCursor = MediaBackupLedgerCursor(
                updatedAtEpochMillis = 1_720_000_000_000,
                localKey = "fixture-media:10",
            ),
        ),
    )

    MarketingCaptureScenario.GuideAndroidPhotoBackupLibrary -> MarketingMediaTransferFixture(
        state = marketingTransferState(
            section = MediaTransferSection.Completed,
            summary = marketingTransferSummary,
            records = listOf(
                marketingTransferRecord(
                    state = MediaBackupTransferState.Succeeded,
                    index = 11,
                    displayName = "Camera-20260812-0915.jpg",
                    size = 7_340_032,
                    attempts = 1,
                ),
                marketingTransferRecord(
                    state = MediaBackupTransferState.Succeeded,
                    index = 12,
                    displayName = "Camera-20260812-0902.mp4",
                    size = 82_621_440,
                    attempts = 1,
                ),
                marketingTransferRecord(
                    state = MediaBackupTransferState.Succeeded,
                    index = 13,
                    displayName = "Camera-20260811-1840.jpg",
                    size = 5_735_296,
                    attempts = 1,
                ),
            ),
        ),
    )

    else -> error("${scenario.id} is not a media transfer capture scenario.")
}

private val marketingTransferSummary = MediaBackupLedgerSummary(
    pending = 5,
    uploading = 2,
    failed = 2,
    succeeded = 248,
)

private fun marketingTransferState(
    section: MediaTransferSection,
    summary: MediaBackupLedgerSummary,
    records: List<MediaBackupLedgerRecord>,
    canLoadNewer: Boolean = false,
    nextCursor: MediaBackupLedgerCursor? = null,
): MediaTransferCenterState = MediaTransferCenterState(
    summary = summary,
    page = MediaTransferCenterPage(
        section = section,
        records = records,
        nextCursor = nextCursor,
    ),
    canLoadNewer = canLoadNewer,
)

private fun marketingTransferRecord(
    state: MediaBackupTransferState,
    index: Int,
    displayName: String,
    size: Long,
    attempts: Int,
    failureMessage: String? = null,
): MediaBackupLedgerRecord {
    val local = LocalMediaObject(
        key = "fixture-media:$index",
        displayName = displayName,
        size = size,
        revision = "fixture-revision-$index",
    )
    val updatedAt = 1_720_000_000_000L - index * 60_000L
    val receipt = if (state == MediaBackupTransferState.Succeeded) {
        MediaBackupReceipt(
            localKey = local.key,
            localRevision = local.revision,
            localSize = local.size,
            remotePath = "Photos/Phone/${local.displayName}",
            remoteEtag = "\"fixture-etag-$index\"",
            verifiedAtEpochMillis = updatedAt,
        )
    } else {
        null
    }
    return MediaBackupLedgerRecord(
        accountId = "00000000000000000000000000000000",
        local = local,
        receipt = receipt,
        transferState = state,
        attemptCount = attempts,
        updatedAtEpochMillis = updatedAt,
        failureMessage = failureMessage,
    )
}
