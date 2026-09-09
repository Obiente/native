package dev.obiente.nextcloudnative.app

import androidx.compose.ui.graphics.ImageBitmap

data class MarketingCaptureAssets(
    val avatar: ImageBitmap,
    val mediaPreview: ImageBitmap,
    val services: NextcloudPlatformServices,
)

val rawPreviewCaptureScenarios: List<MarketingCaptureScenario> = listOf(
    MarketingCaptureScenario.RawPreviewLoadingMobile,
    MarketingCaptureScenario.RawPreviewErrorMobile,
    MarketingCaptureScenario.RawPreviewMemoriesReadyMobile,
    MarketingCaptureScenario.RawPreviewHighDetailDesktop,
)

val photoMediaReviewCaptureScenarios: List<MarketingCaptureScenario> = listOf(
    MarketingCaptureScenario.LivePhotoMotionFailureMobile,
    MarketingCaptureScenario.NativeTiffPreviewMobile,
)

internal fun MarketingCaptureScenario.guideCaptureSourceScenarioOrNull(): MarketingCaptureScenario? =
    when (this) {
        MarketingCaptureScenario.GuideAndroidGettingStartedHome -> MarketingCaptureScenario.HomepageOverviewMobileDark
        MarketingCaptureScenario.GuideAndroidGettingStartedFiles -> MarketingCaptureScenario.HomepageFilesMobileDark
        MarketingCaptureScenario.GuideAndroidGettingStartedCalendar -> MarketingCaptureScenario.CalendarWorkspaceMobileDark
        MarketingCaptureScenario.GuideDesktopGettingStartedHome -> MarketingCaptureScenario.HomepageOverviewDesktopDark
        MarketingCaptureScenario.GuideDesktopGettingStartedApps -> MarketingCaptureScenario.AppsWorkspaceDesktopDark
        MarketingCaptureScenario.GuideDesktopGettingStartedSettings -> MarketingCaptureScenario.DesktopStartupSettings
        MarketingCaptureScenario.GuideAndroidOfflineFilesBrowse -> MarketingCaptureScenario.HomepageFilesMobileDark
        MarketingCaptureScenario.GuideAndroidOfflineFilesStorage -> null
        MarketingCaptureScenario.GuideAndroidOfflineFilesTransfers -> null
        MarketingCaptureScenario.GuideAndroidFolderSyncLocations,
        MarketingCaptureScenario.GuideAndroidFolderSyncRules,
        -> null
        MarketingCaptureScenario.GuideAndroidFolderSyncStatus -> MarketingCaptureScenario.FileSyncStatusMobile
        MarketingCaptureScenario.GuideLinuxFolderSyncWorkspace -> MarketingCaptureScenario.FileSyncStatusDesktop
        MarketingCaptureScenario.GuideLinuxFolderSyncLocations -> null
        MarketingCaptureScenario.GuideLinuxFolderSyncRules -> null
        MarketingCaptureScenario.GuideWindowsCloudFilesSettings -> null
        MarketingCaptureScenario.GuideWindowsCloudFilesStorage -> MarketingCaptureScenario.WindowsCloudFilesStorageDesktop
        MarketingCaptureScenario.GuideWindowsCloudFilesRecovery -> MarketingCaptureScenario.WindowsCloudFilesRecoveryDesktop
        MarketingCaptureScenario.GuideAndroidPhotoBackupFolders -> MarketingCaptureScenario.MediaBackup
        MarketingCaptureScenario.GuideAndroidPhotoBackupQueue -> MarketingCaptureScenario.TransferMobilePending
        MarketingCaptureScenario.GuideAndroidPhotoBackupLibrary -> null
        MarketingCaptureScenario.GuideAndroidCalendarMonth -> MarketingCaptureScenario.CalendarMonthMobile
        MarketingCaptureScenario.GuideAndroidCalendarAgenda -> MarketingCaptureScenario.CalendarWorkspaceMobileDark
        MarketingCaptureScenario.GuideAndroidCalendarEdit -> null
        MarketingCaptureScenario.GuideDesktopCalendarMonth,
        MarketingCaptureScenario.GuideDesktopCalendarSources,
        -> MarketingCaptureScenario.CalendarWorkspaceDesktopDark
        MarketingCaptureScenario.GuideDesktopCalendarEdit -> MarketingCaptureScenario.CalendarEventEditorDesktop
        MarketingCaptureScenario.GuideDesktopSwitchAppsCatalog -> MarketingCaptureScenario.AppsWorkspaceDesktopDark
        MarketingCaptureScenario.GuideDesktopSwitchAppsSidebar -> MarketingCaptureScenario.PhotoFolderBrowserDesktop
        MarketingCaptureScenario.GuideDesktopSwitchAppsNested -> MarketingCaptureScenario.AdaptiveApp
        else -> null
    }

data class MarketingCaptureVariant(
    val scenario: MarketingCaptureScenario,
    val baseScenario: String,
    val id: String,
    val fileName: String,
    val theme: MarketingCaptureTheme,
) {
    val width: Int get() = scenario.width
    val height: Int get() = scenario.height
    val density: Float get() = scenario.density
}

internal data class MarketingCaptureRegistryEntry(
    val id: String,
    val baseScenario: String,
    val fileName: String,
    val theme: String,
    val feature: String,
    val surface: String,
    val state: String,
    val purpose: String,
    val platform: String,
    val viewport: String,
    val pullRequest: Int?,
    val issue: Int?,
    val width: Int,
    val height: Int,
    val density: Float,
)

private val marketingCaptureSlug = Regex("[a-z0-9-]+")
private val marketingCapturePngFileName = Regex("[a-z0-9-]+\\.png")

internal fun MarketingCaptureVariant.registryEntry(): MarketingCaptureRegistryEntry =
    MarketingCaptureRegistryEntry(
        id = id,
        baseScenario = baseScenario,
        fileName = fileName,
        theme = theme.manifestValue,
        feature = scenario.feature,
        surface = scenario.surface,
        state = scenario.state,
        purpose = scenario.purpose.manifestValue,
        platform = scenario.platform,
        viewport = scenario.viewport,
        pullRequest = scenario.pullRequest,
        issue = scenario.issue,
        width = scenario.width,
        height = scenario.height,
        density = scenario.density,
    )

internal fun validateMarketingCaptureRegistry(
    entries: List<MarketingCaptureRegistryEntry>,
) {
    require(entries.isNotEmpty()) {
        "The marketing capture registry must not be empty."
    }
    require(entries.map(MarketingCaptureRegistryEntry::id).toSet().size == entries.size) {
        "Marketing capture scenario IDs must be unique."
    }
    require(entries.map(MarketingCaptureRegistryEntry::fileName).toSet().size == entries.size) {
        "Marketing capture file names must be unique."
    }
    entries.forEach { entry ->
        require(entry.id.matches(marketingCaptureSlug)) {
            "Invalid marketing capture scenario ID: ${entry.id}"
        }
        require(entry.baseScenario.matches(marketingCaptureSlug)) {
            "${entry.id} has an invalid base scenario slug."
        }
        require(entry.fileName.matches(marketingCapturePngFileName)) {
            "Invalid marketing capture PNG file name: ${entry.fileName}"
        }
        require(entry.theme in MarketingCaptureTheme.entries.map(MarketingCaptureTheme::manifestValue)) {
            "${entry.id} has an unsupported capture theme."
        }
        require(entry.width > 0 && entry.height > 0) {
            "${entry.id} must have positive pixel dimensions."
        }
        require(entry.density.isFinite() && entry.density > 0f) {
            "${entry.id} must have a positive finite density."
        }
        listOf(
            "feature" to entry.feature,
            "surface" to entry.surface,
            "state" to entry.state,
        ).forEach { (label, value) ->
            require(value.isNotEmpty() && value == value.trim()) {
                "${entry.id} $label must be a non-empty trimmed label."
            }
        }
        require(
            entry.purpose == MarketingCapturePurpose.Showcase.manifestValue ||
                entry.purpose == MarketingCapturePurpose.StateCoverage.manifestValue,
        ) {
            "${entry.id} has an unsupported capture purpose."
        }
        require(entry.platform.matches(marketingCaptureSlug)) {
            "${entry.id} has an invalid platform slug."
        }
        require(entry.viewport.matches(marketingCaptureSlug)) {
            "${entry.id} has an invalid viewport slug."
        }
        require(entry.pullRequest == null || entry.pullRequest > 0) {
            "${entry.id} must use a positive pull request number."
        }
        require(entry.issue == null || entry.issue > 0) {
            "${entry.id} must use a positive issue number."
        }
    }
    entries.groupBy(MarketingCaptureRegistryEntry::baseScenario).forEach { (baseScenario, pair) ->
        require(pair.size == MarketingCaptureTheme.entries.size) {
            "$baseScenario must declare exactly one dark and one light capture."
        }
        require(pair.map(MarketingCaptureRegistryEntry::theme).toSet() ==
            MarketingCaptureTheme.entries.map(MarketingCaptureTheme::manifestValue).toSet()) {
            "$baseScenario must declare exactly one dark and one light capture."
        }
        val reference = pair.first()
        pair.drop(1).forEach { candidate ->
            require(
                candidate.feature == reference.feature &&
                    candidate.surface == reference.surface &&
                    candidate.state == reference.state &&
                    candidate.purpose == reference.purpose &&
                    candidate.platform == reference.platform &&
                    candidate.viewport == reference.viewport &&
                    candidate.pullRequest == reference.pullRequest &&
                    candidate.issue == reference.issue &&
                    candidate.width == reference.width &&
                    candidate.height == reference.height &&
                    candidate.density == reference.density,
            ) {
                "$baseScenario theme variants must share capture metadata and dimensions."
            }
        }
    }
}

internal val fileShareCaptureScenarios: List<MarketingCaptureScenario> = listOf(
    MarketingCaptureScenario.FileShareUserMobile,
    MarketingCaptureScenario.FileShareGroupDesktop,
    MarketingCaptureScenario.FileShareLoadingMobile,
    MarketingCaptureScenario.FileShareErrorMobile,
)

val marketingCaptureScenarios: List<MarketingCaptureScenario> =
    MarketingCaptureScenario.entries

private fun MarketingCaptureScenario.explicitThemeOrNull(): MarketingCaptureTheme? = when {
    id.endsWith("-dark") -> MarketingCaptureTheme.Dark
    id.endsWith("-light") -> MarketingCaptureTheme.Light
    else -> null
}

private fun MarketingCaptureScenario.baseScenarioId(): String =
    id.removeSuffix("-dark").removeSuffix("-light")

val marketingCaptureVariants: List<MarketingCaptureVariant> =
    marketingCaptureScenarios
        .groupBy(MarketingCaptureScenario::baseScenarioId)
        .flatMap { (baseScenario, scenarios) ->
            when (scenarios.size) {
                1 -> {
                    val scenario = scenarios.single()
                    require(scenario.explicitThemeOrNull() == null) {
                        "$baseScenario has only one explicit theme variant."
                    }
                    listOf(
                        MarketingCaptureVariant(
                            scenario = scenario,
                            baseScenario = baseScenario,
                            id = scenario.id,
                            fileName = scenario.fileName,
                            theme = MarketingCaptureTheme.Dark,
                        ),
                        MarketingCaptureVariant(
                            scenario = scenario,
                            baseScenario = baseScenario,
                            id = "${scenario.id}-light",
                            fileName = "${scenario.fileName.removeSuffix(".png")}-light.png",
                            theme = MarketingCaptureTheme.Light,
                        ),
                    )
                }
                MarketingCaptureTheme.entries.size -> scenarios.map { scenario ->
                    val theme = requireNotNull(scenario.explicitThemeOrNull()) {
                        "$baseScenario mixes explicit and implicit theme variants."
                    }
                    require(scenario.darkTheme == theme.darkTheme) {
                        "${scenario.id} theme suffix does not match its renderer theme."
                    }
                    MarketingCaptureVariant(
                        scenario = scenario,
                        baseScenario = baseScenario,
                        id = scenario.id,
                        fileName = scenario.fileName,
                        theme = theme,
                    )
                }
                else -> error("$baseScenario has an unsupported number of capture scenarios.")
            }
        }
