package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudPresentation
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopShell
import dev.obiente.nextcloudnative.app.design.NextcloudDestination
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.DynamicForm
import dev.obiente.nextcloudnative.nativeui.model.DynamicHttpBinding
import dev.obiente.nextcloudnative.nativeui.model.EndpointPolicy
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import dev.obiente.nextcloudnative.nativeui.runtime.GenericNativeAppScreen
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutionResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutor
import dev.obiente.nextcloudnative.nativeui.runtime.NativeDatasetContext
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.NativeScreenState
import dev.obiente.nextcloudnative.nativeui.runtime.preferredNativeMailComposeAction

enum class MarketingCapturePurpose(val manifestValue: String) {
    Showcase("showcase"),
    StateCoverage("state-coverage"),
}

enum class MarketingCaptureTheme(
    val manifestValue: String,
    val darkTheme: Boolean,
) {
    Dark("dark", true),
    Light("light", false),
}

enum class MarketingCaptureScenario(
    val id: String,
    val fileName: String,
    val presentation: NextcloudPresentation,
    val feature: String,
    val surface: String,
    val state: String,
    val purpose: MarketingCapturePurpose,
    val platform: String,
    val viewport: String,
    val pullRequest: Int? = null,
    val issue: Int? = null,
    val width: Int,
    val height: Int,
    val density: Float,
    val darkTheme: Boolean = true,
) {
    HomepageOverviewDesktopDark(
        "homepage-overview-desktop-dark",
        "homepage-overview-desktop-dark.png",
        NextcloudPresentation.Desktop,
        "Homepage",
        "Complete account overview",
        "Ready",
        MarketingCapturePurpose.Showcase,
        "desktop",
        "wide",
        width = 1_440,
        height = 900,
        density = 1f,
    ),
    HomepageOverviewDesktopLight(
        "homepage-overview-desktop-light",
        "homepage-overview-desktop-light.png",
        NextcloudPresentation.Desktop,
        "Homepage",
        "Complete account overview",
        "Ready",
        MarketingCapturePurpose.Showcase,
        "desktop",
        "wide",
        width = 1_440,
        height = 900,
        density = 1f,
        darkTheme = false,
    ),
    HomepageOverviewMobileDark(
        "homepage-overview-mobile-dark",
        "homepage-overview-mobile-dark.png",
        NextcloudPresentation.Adaptive,
        "Homepage",
        "Complete account overview",
        "Ready",
        MarketingCapturePurpose.Showcase,
        "mobile",
        "phone-portrait",
        width = 1_080,
        height = 2_400,
        density = 2.625f,
    ),
    HomepageOverviewMobileLight(
        "homepage-overview-mobile-light",
        "homepage-overview-mobile-light.png",
        NextcloudPresentation.Adaptive,
        "Homepage",
        "Complete account overview",
        "Ready",
        MarketingCapturePurpose.Showcase,
        "mobile",
        "phone-portrait",
        width = 1_080,
        height = 2_400,
        density = 2.625f,
        darkTheme = false,
    ),
    HomepageFilesDesktopDark(
        "homepage-files-desktop-dark",
        "homepage-files-desktop-dark.png",
        NextcloudPresentation.Desktop,
        "Homepage",
        "Files workspace",
        "Synced with offline content",
        MarketingCapturePurpose.Showcase,
        "desktop",
        "wide",
        width = 1_440,
        height = 900,
        density = 1f,
    ),
    HomepageFilesDesktopLight(
        "homepage-files-desktop-light",
        "homepage-files-desktop-light.png",
        NextcloudPresentation.Desktop,
        "Homepage",
        "Files workspace",
        "Synced with offline content",
        MarketingCapturePurpose.Showcase,
        "desktop",
        "wide",
        width = 1_440,
        height = 900,
        density = 1f,
        darkTheme = false,
    ),
    HomepageFilesMobileDark(
        "homepage-files-mobile-dark",
        "homepage-files-mobile-dark.png",
        NextcloudPresentation.Adaptive,
        "Homepage",
        "Files workspace",
        "Synced with offline content",
        MarketingCapturePurpose.Showcase,
        "mobile",
        "phone-portrait",
        width = 1_080,
        height = 2_400,
        density = 2.625f,
    ),
    HomepageFilesMobileLight(
        "homepage-files-mobile-light",
        "homepage-files-mobile-light.png",
        NextcloudPresentation.Adaptive,
        "Homepage",
        "Files workspace",
        "Synced with offline content",
        MarketingCapturePurpose.Showcase,
        "mobile",
        "phone-portrait",
        width = 1_080,
        height = 2_400,
        density = 2.625f,
        darkTheme = false,
    ),
    HomepagePhotosDesktopDark(
        "homepage-photos-desktop-dark",
        "homepage-photos-desktop-dark.png",
        NextcloudPresentation.Desktop,
        "Homepage",
        "Photos and memories workspace",
        "Library ready",
        MarketingCapturePurpose.Showcase,
        "desktop",
        "wide",
        width = 1_440,
        height = 900,
        density = 1f,
    ),
    HomepagePhotosDesktopLight(
        "homepage-photos-desktop-light",
        "homepage-photos-desktop-light.png",
        NextcloudPresentation.Desktop,
        "Homepage",
        "Photos and memories workspace",
        "Library ready",
        MarketingCapturePurpose.Showcase,
        "desktop",
        "wide",
        width = 1_440,
        height = 900,
        density = 1f,
        darkTheme = false,
    ),
    HomepageConversationsDesktopDark(
        "homepage-conversations-desktop-dark",
        "homepage-conversations-desktop-dark.png",
        NextcloudPresentation.Desktop,
        "Homepage",
        "Conversation workspace",
        "Active conversation",
        MarketingCapturePurpose.Showcase,
        "desktop",
        "wide",
        width = 1_440,
        height = 900,
        density = 1f,
    ),
    HomepageConversationsDesktopLight(
        "homepage-conversations-desktop-light",
        "homepage-conversations-desktop-light.png",
        NextcloudPresentation.Desktop,
        "Homepage",
        "Conversation workspace",
        "Active conversation",
        MarketingCapturePurpose.Showcase,
        "desktop",
        "wide",
        width = 1_440,
        height = 900,
        density = 1f,
        darkTheme = false,
    ),
    HomepagePlanningDesktopDark(
        "homepage-planning-desktop-dark",
        "homepage-planning-desktop-dark.png",
        NextcloudPresentation.Desktop,
        "Homepage",
        "Planning workspace",
        "Board ready",
        MarketingCapturePurpose.Showcase,
        "desktop",
        "wide",
        width = 1_440,
        height = 900,
        density = 1f,
    ),
    HomepagePlanningDesktopLight(
        "homepage-planning-desktop-light",
        "homepage-planning-desktop-light.png",
        NextcloudPresentation.Desktop,
        "Homepage",
        "Planning workspace",
        "Board ready",
        MarketingCapturePurpose.Showcase,
        "desktop",
        "wide",
        width = 1_440,
        height = 900,
        density = 1f,
        darkTheme = false,
    ),
    HomepageAppsDesktopDark(
        "homepage-apps-desktop-dark",
        "homepage-apps-desktop-dark.png",
        NextcloudPresentation.Desktop,
        "Homepage",
        "Installed app workspace",
        "Verified native data",
        MarketingCapturePurpose.Showcase,
        "desktop",
        "wide",
        width = 1_440,
        height = 900,
        density = 1f,
    ),
    HomepageAppsDesktopLight(
        "homepage-apps-desktop-light",
        "homepage-apps-desktop-light.png",
        NextcloudPresentation.Desktop,
        "Homepage",
        "Installed app workspace",
        "Verified native data",
        MarketingCapturePurpose.Showcase,
        "desktop",
        "wide",
        width = 1_440,
        height = 900,
        density = 1f,
        darkTheme = false,
    ),
    AppsWorkspaceDesktopDark(
        "apps-workspace-desktop-dark",
        "apps-workspace-desktop-dark.png",
        NextcloudPresentation.Desktop,
        "Apps",
        "Installed app command center",
        "Active account with pinned and recent workspaces",
        MarketingCapturePurpose.Showcase,
        "desktop",
        "wide",
        width = 1_440,
        height = 900,
        density = 1f,
    ),
    AppsWorkspaceDesktopLight(
        "apps-workspace-desktop-light",
        "apps-workspace-desktop-light.png",
        NextcloudPresentation.Desktop,
        "Apps",
        "Installed app command center",
        "Active account with pinned and recent workspaces",
        MarketingCapturePurpose.Showcase,
        "desktop",
        "wide",
        width = 1_440,
        height = 900,
        density = 1f,
        darkTheme = false,
    ),
    CalendarWorkspaceDesktopDark(
        "calendar-workspace-desktop-dark",
        "calendar-workspace-desktop-dark.png",
        NextcloudPresentation.Desktop,
        "Calendar",
        "Month planning workspace",
        "Four calendars, selected event, and active month",
        MarketingCapturePurpose.Showcase,
        "desktop",
        "wide",
        pullRequest = 273,
        width = 1_440,
        height = 900,
        density = 1f,
    ),
    CalendarWorkspaceDesktopLight(
        "calendar-workspace-desktop-light",
        "calendar-workspace-desktop-light.png",
        NextcloudPresentation.Desktop,
        "Calendar",
        "Month planning workspace",
        "Four calendars, selected event, and active month",
        MarketingCapturePurpose.Showcase,
        "desktop",
        "wide",
        pullRequest = 273,
        width = 1_440,
        height = 900,
        density = 1f,
        darkTheme = false,
    ),
    CalendarWorkspaceMobileDark(
        "calendar-workspace-mobile-dark",
        "calendar-workspace-mobile-dark.png",
        NextcloudPresentation.Adaptive,
        "Calendar",
        "Mobile agenda",
        "Active week with personal, team, and community events",
        MarketingCapturePurpose.Showcase,
        "mobile",
        "phone-portrait",
        pullRequest = 273,
        width = 1_080,
        height = 1_800,
        density = 2.625f,
    ),
    CalendarWorkspaceMobileLight(
        "calendar-workspace-mobile-light",
        "calendar-workspace-mobile-light.png",
        NextcloudPresentation.Adaptive,
        "Calendar",
        "Mobile agenda",
        "Active week with personal, team, and community events",
        MarketingCapturePurpose.Showcase,
        "mobile",
        "phone-portrait",
        pullRequest = 273,
        width = 1_080,
        height = 1_800,
        density = 2.625f,
        darkTheme = false,
    ),
    CalendarMonthMobile(
        "calendar-month-mobile", "calendar-month-mobile.png", NextcloudPresentation.Adaptive,
        "Calendar", "Mobile month", "Selected date with writable and read-only sources",
        MarketingCapturePurpose.Showcase, "mobile", "phone-portrait",
        width = 1_080, height = 1_800, density = 2.625f,
    ),
    CalendarEventEditorMobile(
        "calendar-event-editor-mobile", "calendar-event-editor-mobile.png", NextcloudPresentation.Adaptive,
        "Calendar", "Mobile event editor", "New event with calendar and recurrence choices",
        MarketingCapturePurpose.Showcase, "mobile", "phone-portrait",
        width = 1_080, height = 1_800, density = 2.625f,
    ),
    CalendarEventEditorDesktop(
        "calendar-event-editor-desktop", "calendar-event-editor-desktop.png", NextcloudPresentation.Desktop,
        "Calendar", "Desktop event editor", "New event with calendar and recurrence choices",
        MarketingCapturePurpose.Showcase, "desktop", "wide",
        width = 1_440, height = 900, density = 1f,
    ),
    DesktopHome(
        "desktop-home", "desktop-home.png", NextcloudPresentation.Desktop,
        "Workspace", "Home dashboard", "Ready", MarketingCapturePurpose.Showcase,
        "desktop", "wide", width = 1_440, height = 900, density = 1f,
    ),
    MobileHome(
        "mobile-home", "mobile-home.png", NextcloudPresentation.Adaptive,
        "Workspace", "Home dashboard", "Ready", MarketingCapturePurpose.Showcase,
        "mobile", "phone-portrait", width = 1_080, height = 2_400, density = 2.625f,
    ),
    ObsidianSync(
        "obsidian-vault-sync", "obsidian-vault-sync.png", NextcloudPresentation.Adaptive,
        "File sync", "Folder pair", "One pending upload", MarketingCapturePurpose.Showcase,
        "mobile", "phone-compact", width = 1_080, height = 1_000, density = 2.625f,
    ),
    MediaBackup(
        "media-backup-queue", "media-backup-queue.png", NextcloudPresentation.Adaptive,
        "Media backup", "Folder discovery", "Ready with pending uploads",
        MarketingCapturePurpose.Showcase, "mobile", "phone-portrait",
        width = 1_080, height = 2_200, density = 2.625f,
    ),
    FileSyncRulesMobile(
        "file-sync-rules-mobile", "file-sync-rules-mobile.png", NextcloudPresentation.Adaptive,
        "File sync", "Folder pair configuration", "Selective, ignore, and priority rules",
        MarketingCapturePurpose.StateCoverage, "mobile", "phone-portrait",
        width = 1_080, height = 2_200, density = 2.625f,
    ),
    FileSyncStatusMobile(
        "file-sync-status-mobile", "file-sync-status-mobile.png", NextcloudPresentation.Adaptive,
        "File sync", "Folder sync center", "Conflict recovery and mapping health",
        MarketingCapturePurpose.StateCoverage, "mobile", "phone-portrait",
        width = 1_080, height = 2_200, density = 2.625f,
    ),
    FileSyncStatusDesktop(
        "file-sync-status-desktop", "file-sync-status-desktop.png", NextcloudPresentation.Desktop,
        "File sync", "Folder sync center", "Priority queue, conflict, and failure",
        MarketingCapturePurpose.Showcase, "linux", "wide",
        width = 1_440, height = 1_145, density = 1f,
    ),
    ActivityWorkspaceDesktop(
        "activity-workspace-desktop", "activity-workspace-desktop.png", NextcloudPresentation.Desktop,
        "Activity", "Attention-first activity workspace", "Grouped automation and actionable events",
        MarketingCapturePurpose.Showcase, "desktop", "wide",
        width = 1_721, height = 914, density = 1f,
    ),
    FileSyncSetupDesktop(
        "file-sync-setup-desktop", "file-sync-setup-desktop.png", NextcloudPresentation.Desktop,
        "File sync", "Folder pair configuration", "Guided RAW-first setup",
        MarketingCapturePurpose.StateCoverage, "linux", "wide",
        width = 1_440, height = 900, density = 1f,
    ),
    FileSyncSelectionDesktop(
        "file-sync-selection-desktop", "file-sync-selection-desktop.png", NextcloudPresentation.Desktop,
        "File sync", "Selective sync browser", "Verified folders and files",
        MarketingCapturePurpose.StateCoverage, "linux", "wide",
        width = 1_440, height = 900, density = 1f,
    ),
    FileSyncSelectionMobile(
        "file-sync-selection-mobile", "file-sync-selection-mobile.png", NextcloudPresentation.Adaptive,
        "File sync", "Selective sync browser", "Verified folders and files",
        MarketingCapturePurpose.StateCoverage, "mobile", "phone-portrait",
        width = 1_080, height = 2_200, density = 2.625f,
    ),
    VirtualFileStorageMobile(
        "virtual-file-storage-mobile", "virtual-file-storage-mobile.png", NextcloudPresentation.Adaptive,
        "Virtual files", "Storage rules", "Automatic cleanup with protected pins",
        MarketingCapturePurpose.StateCoverage, "mobile", "phone-portrait",
        width = 1_080, height = 2_200, density = 2.625f,
    ),
    VirtualFileStorageDesktop(
        "virtual-file-storage-desktop", "virtual-file-storage-desktop.png", NextcloudPresentation.Desktop,
        "Virtual files", "Storage overview", "Hydrated cache, pins, and free-up action",
        MarketingCapturePurpose.StateCoverage, "linux", "wide",
        width = 1_440, height = 900, density = 1f,
    ),
    WindowsCloudFilesStorageDesktop(
        "windows-cloud-files-storage-desktop", "windows-cloud-files-storage-desktop.png",
        NextcloudPresentation.Desktop, "Windows Cloud Files", "File Explorer storage", "Active placeholders, hydrated files, and pins",
        MarketingCapturePurpose.Showcase, "windows", "wide",
        width = 1_440, height = 900, density = 1f,
    ),
    WindowsCloudFilesRecoveryDesktop(
        "windows-cloud-files-recovery-desktop", "windows-cloud-files-recovery-desktop.png",
        NextcloudPresentation.Desktop, "Windows Cloud Files", "Writeback recovery", "Local edits retained after a remote generation conflict",
        MarketingCapturePurpose.Showcase, "windows", "wide",
        width = 1_440, height = 900, density = 1f,
    ),
    DesktopStartupSettings(
        "desktop-startup-settings", "desktop-startup-settings.png", NextcloudPresentation.Desktop,
        "File sync", "Desktop settings", "Start on login enabled",
        MarketingCapturePurpose.StateCoverage, "desktop", "wide",
        width = 1_440, height = 900, density = 1f,
    ),
    AdaptiveApp(
        "adaptive-dynamic-data", "adaptive-dynamic-data.png", NextcloudPresentation.Desktop,
        "Dynamic apps", "Nested collection and semantic form", "Synthetic visual QA",
        MarketingCapturePurpose.Showcase,
        "desktop", "wide", width = 1_440, height = 900, density = 1f,
    ),
    AdaptiveAppMobile(
        "adaptive-dynamic-data-mobile", "adaptive-dynamic-data-mobile.png", NextcloudPresentation.Adaptive,
        "Dynamic apps", "Nested collection and semantic form", "Synthetic visual QA",
        MarketingCapturePurpose.StateCoverage,
        "mobile", "phone-portrait", width = 1_080, height = 1_800, density = 2.625f,
    ),
    AdaptiveAppCollectionMobile(
        "adaptive-dynamic-collection-mobile",
        "adaptive-dynamic-collection-mobile.png",
        NextcloudPresentation.Adaptive,
        "Dynamic apps",
        "Nested collection actions",
        "Synthetic visual QA",
        MarketingCapturePurpose.StateCoverage,
        "mobile",
        "phone-portrait",
        width = 1_080,
        height = 1_800,
        density = 2.625f,
    ),
    AdaptiveAppContextMenuMobile(
        "adaptive-dynamic-context-menu-mobile",
        "adaptive-dynamic-context-menu-mobile.png",
        NextcloudPresentation.Adaptive,
        "Dynamic apps",
        "Context workspace menu",
        "Synthetic visual QA",
        MarketingCapturePurpose.StateCoverage,
        "mobile",
        "phone-portrait",
        width = 1_080,
        height = 1_800,
        density = 2.625f,
    ),
    MailWorkspaceDesktop(
        "mail-workspace-desktop", "mail-workspace-desktop.png", NextcloudPresentation.Desktop,
        "Mail", "Adaptive mailbox workspace", "Inbox message selected", MarketingCapturePurpose.Showcase,
        "desktop", "wide", issue = 54, width = 1_440, height = 900, density = 1f,
    ),
    MailWorkspaceMobile(
        "mail-workspace-mobile", "mail-workspace-mobile.png", NextcloudPresentation.Adaptive,
        "Mail", "Adaptive mailbox workspace", "Inbox message list", MarketingCapturePurpose.Showcase,
        "mobile", "phone-portrait", issue = 54, width = 1_080, height = 1_800, density = 2.625f,
    ),
    MailWorkspaceLoadingMobile(
        "mail-workspace-loading-mobile", "mail-workspace-loading-mobile.png", NextcloudPresentation.Adaptive,
        "Mail", "Adaptive mailbox workspace", "Loading inbox", MarketingCapturePurpose.StateCoverage,
        "mobile", "phone-portrait", issue = 54, width = 1_080, height = 1_800, density = 2.625f,
    ),
    MailWorkspaceEmptyMobile(
        "mail-workspace-empty-mobile", "mail-workspace-empty-mobile.png", NextcloudPresentation.Adaptive,
        "Mail", "Adaptive mailbox workspace", "Empty inbox", MarketingCapturePurpose.StateCoverage,
        "mobile", "phone-portrait", issue = 54, width = 1_080, height = 1_800, density = 2.625f,
    ),
    MailWorkspaceErrorDesktop(
        "mail-workspace-error-desktop", "mail-workspace-error-desktop.png", NextcloudPresentation.Desktop,
        "Mail", "Adaptive mailbox workspace", "Message body error", MarketingCapturePurpose.StateCoverage,
        "desktop", "wide", issue = 54, width = 1_440, height = 900, density = 1f,
    ),
    PhotoTimelineRevalidationErrorMobile(
        "photo-timeline-revalidation-error-mobile",
        "photo-timeline-revalidation-error-mobile.png",
        NextcloudPresentation.Adaptive,
        "Photos",
        "Photo timeline",
        "Cached photos with revalidation error",
        MarketingCapturePurpose.StateCoverage,
        "mobile",
        "phone-portrait",
        pullRequest = 246,
        issue = 242,
        width = 1_080,
        height = 1_800,
        density = 2.625f,
    ),
    PhotoTimelineReturnToNewestErrorMobile(
        "photo-timeline-return-to-newest-error-mobile",
        "photo-timeline-return-to-newest-error-mobile.png",
        NextcloudPresentation.Adaptive,
        "Photos",
        "Photo timeline",
        "Older window with failed return to newest",
        MarketingCapturePurpose.StateCoverage,
        "mobile",
        "phone-portrait",
        pullRequest = 246,
        issue = 242,
        width = 1_080,
        height = 1_800,
        density = 2.625f,
    ),
    PhotoTimelineRawRetryMobile(
        "photo-timeline-raw-retry-mobile",
        "photo-timeline-raw-retry-mobile.png",
        NextcloudPresentation.Adaptive,
        "Photos",
        "Photo timeline",
        "Ordinary photos with a RAW retry pending",
        MarketingCapturePurpose.StateCoverage,
        "mobile",
        "phone-portrait",
        pullRequest = 249,
        issue = 248,
        width = 1_080,
        height = 1_800,
        density = 2.625f,
    ),
    PhotoFolderBrowserMobile(
        "photo-folder-browser-mobile",
        "photo-folder-browser-mobile.png",
        NextcloudPresentation.Adaptive,
        "Photos",
        "Folder browser",
        "Trip folder in grid view",
        MarketingCapturePurpose.Showcase,
        "mobile",
        "phone-portrait",
        pullRequest = 245,
        issue = 243,
        width = 1_080,
        height = 1_800,
        density = 2.625f,
    ),
    PhotoFolderBrowserDesktop(
        "photo-folder-browser-desktop",
        "photo-folder-browser-desktop.png",
        NextcloudPresentation.Desktop,
        "Photos",
        "Folder browser",
        "Photo library in list view",
        MarketingCapturePurpose.Showcase,
        "desktop",
        "wide",
        pullRequest = 245,
        issue = 243,
        width = 1_440,
        height = 900,
        density = 1f,
    ),
    RawPreviewLoadingMobile(
        "raw-preview-loading-mobile", "raw-preview-loading-mobile.png",
        NextcloudPresentation.Adaptive, "Photos", "RAW preview",
        "Loading embedded preview", MarketingCapturePurpose.StateCoverage,
        "mobile", "phone-compact", pullRequest = 218, issue = 85,
        width = 1_080, height = 1_200, density = 2.625f,
    ),
    RawPreviewErrorMobile(
        "raw-preview-error-mobile", "raw-preview-error-mobile.png",
        NextcloudPresentation.Adaptive, "Photos", "RAW preview",
        "No usable preview", MarketingCapturePurpose.StateCoverage,
        "mobile", "phone-compact", pullRequest = 218, issue = 85,
        width = 1_080, height = 1_200, density = 2.625f,
    ),
    RawPreviewMemoriesReadyMobile(
        "raw-preview-memories-ready-mobile", "raw-preview-memories-ready-mobile.png",
        NextcloudPresentation.Adaptive, "Photos", "RAW preview",
        "Ready from Memories", MarketingCapturePurpose.Showcase,
        "mobile", "phone-portrait", pullRequest = 218, issue = 85,
        width = 1_080, height = 1_600, density = 2.625f,
    ),
    RawPreviewHighDetailDesktop(
        "raw-preview-high-detail-desktop", "raw-preview-high-detail-desktop.png",
        NextcloudPresentation.Desktop, "Photos", "RAW preview",
        "Embedded high-detail preview", MarketingCapturePurpose.Showcase,
        "desktop", "wide", pullRequest = 218, issue = 85,
        width = 1_440, height = 900, density = 1f,
    ),
    LivePhotoMotionFailureMobile(
        "live-photo-motion-failure-mobile",
        "live-photo-motion-failure-mobile.png",
        NextcloudPresentation.Adaptive,
        "Photos",
        "Live Photo viewer",
        "Still visible after motion decoder failure",
        MarketingCapturePurpose.StateCoverage,
        "mobile",
        "phone-compact",
        pullRequest = 249,
        issue = 182,
        width = 1_080,
        height = 1_200,
        density = 2.625f,
    ),
    NativeTiffPreviewMobile(
        "native-tiff-preview-mobile",
        "native-tiff-preview-mobile.png",
        NextcloudPresentation.Adaptive,
        "Photos",
        "TIFF viewer",
        "Native high-detail preview",
        MarketingCapturePurpose.Showcase,
        "mobile",
        "phone-compact",
        pullRequest = 249,
        issue = 84,
        width = 1_080,
        height = 1_200,
        density = 2.625f,
    ),
    FileShareUserMobile(
        "file-share-user-mobile", "file-share-user-mobile.png", NextcloudPresentation.Adaptive,
        "Files", "Share dialog", "User search results", MarketingCapturePurpose.Showcase,
        "mobile", "phone-portrait", pullRequest = 219, issue = 124,
        width = 1_080, height = 1_800, density = 2.625f,
    ),
    FileShareGroupDesktop(
        "file-share-group-desktop", "file-share-group-desktop.png", NextcloudPresentation.Desktop,
        "Files", "Share dialog", "Group search results", MarketingCapturePurpose.Showcase,
        "desktop", "wide", pullRequest = 219, issue = 124,
        width = 1_440, height = 900, density = 1f,
    ),
    FileShareLoadingMobile(
        "file-share-loading-mobile", "file-share-loading-mobile.png", NextcloudPresentation.Adaptive,
        "Files", "Share dialog", "Loading recipients", MarketingCapturePurpose.StateCoverage,
        "mobile", "phone-portrait", pullRequest = 219, issue = 124,
        width = 1_080, height = 1_800, density = 2.625f,
    ),
    FileShareErrorMobile(
        "file-share-error-mobile", "file-share-error-mobile.png", NextcloudPresentation.Adaptive,
        "Files", "Share dialog", "Recipient search error", MarketingCapturePurpose.StateCoverage,
        "mobile", "phone-portrait", pullRequest = 219, issue = 124,
        width = 1_080, height = 1_800, density = 2.625f,
    ),
    TransferMobilePending(
        "transfer-mobile-pending", "transfer-mobile-pending.png", NextcloudPresentation.Adaptive,
        "Files", "Transfer center", "Pending uploads", MarketingCapturePurpose.Showcase,
        "mobile", "phone-portrait", pullRequest = 220, issue = 168,
        width = 1_080, height = 1_800, density = 2.625f,
    ),
    TransferMobileFailed(
        "transfer-mobile-failed-cached", "transfer-mobile-failed-cached.png",
        NextcloudPresentation.Adaptive, "Files", "Transfer center",
        "Failed upload with cached source", MarketingCapturePurpose.StateCoverage,
        "mobile", "phone-portrait", pullRequest = 220, issue = 168,
        width = 1_080, height = 1_800, density = 2.625f,
    ),
    TransferDesktopActive(
        "transfer-desktop-active", "transfer-desktop-active.png", NextcloudPresentation.Desktop,
        "Files", "Transfer center", "Active transfer", MarketingCapturePurpose.Showcase,
        "desktop", "wide", pullRequest = 220, issue = 168,
        width = 1_280, height = 800, density = 1f,
    ),
    TransferDesktopCompleted(
        "transfer-desktop-completed-page", "transfer-desktop-completed-page.png",
        NextcloudPresentation.Desktop, "Files", "Transfer center",
        "Completed transfer history", MarketingCapturePurpose.Showcase,
        "desktop", "wide", pullRequest = 220, issue = 168,
        width = 1_280, height = 800, density = 1f,
    ),
    DeckBoardDesktop(
        "deck-board-desktop", "deck-board-desktop.png", NextcloudPresentation.Desktop,
        "Deck", "Kanban board", "Ready", MarketingCapturePurpose.Showcase,
        "desktop", "wide", pullRequest = 221, issue = 52,
        width = 1_440, height = 900, density = 1f,
    ),
    DeckBoardMobile(
        "deck-board-mobile", "deck-board-mobile.png", NextcloudPresentation.Adaptive,
        "Deck", "Kanban board", "Ready", MarketingCapturePurpose.Showcase,
        "mobile", "phone-portrait", pullRequest = 221, issue = 52,
        width = 1_080, height = 1_800, density = 2.625f,
    ),
    MusicLibraryAlbumTracksMobile(
        "music-library-album-tracks-mobile", "music-library-album-tracks-mobile.png",
        NextcloudPresentation.Adaptive, "Music", "Album track collection",
        "Ready", MarketingCapturePurpose.Showcase,
        "mobile", "phone-portrait", issue = 56,
        width = 1_080, height = 1_800, density = 2.625f,
    ),
    MusicLibraryPlaybackErrorDesktop(
        "music-library-playback-error-desktop", "music-library-playback-error-desktop.png",
        NextcloudPresentation.Desktop, "Music", "Playback queue",
        "Playback error with queue retained", MarketingCapturePurpose.StateCoverage,
        "desktop", "wide", issue = 56,
        width = 1_440, height = 900, density = 1f,
    ),
    GuideAndroidGettingStartedHome("guide-android-getting-started-home", "guide-android-getting-started-home.png", NextcloudPresentation.Adaptive, "Guides", "Android setup", "Connected mobile Home", MarketingCapturePurpose.Showcase, "android", "phone-portrait", width = 1_080, height = 2_400, density = 2.625f),
    GuideAndroidGettingStartedFiles("guide-android-getting-started-files", "guide-android-getting-started-files.png", NextcloudPresentation.Adaptive, "Guides", "Android setup", "Files and offline state", MarketingCapturePurpose.Showcase, "android", "phone-portrait", width = 1_080, height = 2_400, density = 2.625f),
    GuideAndroidGettingStartedCalendar("guide-android-getting-started-calendar", "guide-android-getting-started-calendar.png", NextcloudPresentation.Adaptive, "Guides", "Android setup", "Native Calendar workspace", MarketingCapturePurpose.Showcase, "android", "phone-portrait", width = 1_080, height = 1_800, density = 2.625f),
    GuideDesktopGettingStartedHome("guide-desktop-getting-started-home", "guide-desktop-getting-started-home.png", NextcloudPresentation.Desktop, "Guides", "Desktop setup", "Connected desktop Home", MarketingCapturePurpose.Showcase, "desktop", "wide", width = 1_440, height = 900, density = 1f),
    GuideDesktopGettingStartedApps("guide-desktop-getting-started-apps", "guide-desktop-getting-started-apps.png", NextcloudPresentation.Desktop, "Guides", "Desktop setup", "Installed app catalog", MarketingCapturePurpose.Showcase, "desktop", "wide", width = 1_440, height = 900, density = 1f),
    GuideDesktopGettingStartedSettings("guide-desktop-getting-started-settings", "guide-desktop-getting-started-settings.png", NextcloudPresentation.Desktop, "Guides", "Desktop setup", "Sync and storage settings", MarketingCapturePurpose.Showcase, "desktop", "wide", width = 1_440, height = 900, density = 1f),
    GuideAndroidOfflineFilesBrowse("guide-android-offline-files-browse", "guide-android-offline-files-browse.png", NextcloudPresentation.Adaptive, "Guides", "Android offline files", "Files and availability", MarketingCapturePurpose.Showcase, "android", "phone-portrait", width = 1_080, height = 2_400, density = 2.625f),
    GuideAndroidOfflineFilesStorage("guide-android-offline-files-storage", "guide-android-offline-files-storage.png", NextcloudPresentation.Adaptive, "Guides", "Android offline files", "System Files storage", MarketingCapturePurpose.Showcase, "android", "phone-portrait", width = 1_080, height = 2_200, density = 2.625f),
    GuideAndroidOfflineFilesTransfers("guide-android-offline-files-transfers", "guide-android-offline-files-transfers.png", NextcloudPresentation.Adaptive, "Guides", "Android offline files", "Pending and failed work", MarketingCapturePurpose.Showcase, "android", "phone-portrait", width = 1_080, height = 1_800, density = 2.625f),
    GuideAndroidFolderSyncLocations("guide-android-folder-sync-locations", "guide-android-folder-sync-locations.png", NextcloudPresentation.Adaptive, "Guides", "Android folder sync", "Device and Nextcloud mapping", MarketingCapturePurpose.Showcase, "android", "phone-compact", width = 1_080, height = 1_000, density = 2.625f),
    GuideAndroidFolderSyncRules("guide-android-folder-sync-rules", "guide-android-folder-sync-rules.png", NextcloudPresentation.Adaptive, "Guides", "Android folder sync", "Direction and safety rules", MarketingCapturePurpose.Showcase, "android", "phone-portrait", width = 1_080, height = 2_200, density = 2.625f),
    GuideAndroidFolderSyncStatus("guide-android-folder-sync-status", "guide-android-folder-sync-status.png", NextcloudPresentation.Adaptive, "Guides", "Android folder sync", "Schedule, conflict, and health", MarketingCapturePurpose.Showcase, "android", "phone-portrait", width = 1_080, height = 2_200, density = 2.625f),
    GuideLinuxFolderSyncWorkspace("guide-linux-folder-sync-workspace", "guide-linux-folder-sync-workspace.png", NextcloudPresentation.Desktop, "Guides", "Linux folder sync", "Active pair workspace", MarketingCapturePurpose.Showcase, "linux", "wide", width = 1_721, height = 914, density = 1f),
    GuideLinuxFolderSyncLocations("guide-linux-folder-sync-locations", "guide-linux-folder-sync-locations.png", NextcloudPresentation.Desktop, "Guides", "Linux folder sync", "Local and Nextcloud mapping", MarketingCapturePurpose.Showcase, "linux", "wide", width = 1_440, height = 900, density = 1f),
    GuideLinuxFolderSyncRules("guide-linux-folder-sync-rules", "guide-linux-folder-sync-rules.png", NextcloudPresentation.Desktop, "Guides", "Linux folder sync", "Scope and conflict rules", MarketingCapturePurpose.Showcase, "linux", "wide", width = 1_440, height = 900, density = 1f),
    GuideWindowsCloudFilesSettings("guide-windows-cloud-files-settings", "guide-windows-cloud-files-settings.png", NextcloudPresentation.Desktop, "Guides", "Windows Cloud Files", "Sync and storage settings", MarketingCapturePurpose.Showcase, "windows", "wide", width = 1_440, height = 900, density = 1f),
    GuideWindowsCloudFilesStorage("guide-windows-cloud-files-storage", "guide-windows-cloud-files-storage.png", NextcloudPresentation.Desktop, "Guides", "Windows Cloud Files", "File Explorer placeholders", MarketingCapturePurpose.Showcase, "windows", "wide", width = 1_440, height = 900, density = 1f),
    GuideWindowsCloudFilesRecovery("guide-windows-cloud-files-recovery", "guide-windows-cloud-files-recovery.png", NextcloudPresentation.Desktop, "Guides", "Windows Cloud Files", "Guarded writeback recovery", MarketingCapturePurpose.Showcase, "windows", "wide", width = 1_440, height = 900, density = 1f),
    GuideAndroidPhotoBackupFolders("guide-android-photo-backup-folders", "guide-android-photo-backup-folders.png", NextcloudPresentation.Adaptive, "Guides", "Android photo backup", "Media folder discovery", MarketingCapturePurpose.Showcase, "android", "phone-portrait", width = 1_080, height = 2_200, density = 2.625f),
    GuideAndroidPhotoBackupQueue("guide-android-photo-backup-queue", "guide-android-photo-backup-queue.png", NextcloudPresentation.Adaptive, "Guides", "Android photo backup", "Durable transfer queue", MarketingCapturePurpose.Showcase, "android", "phone-portrait", width = 1_080, height = 1_800, density = 2.625f),
    GuideAndroidPhotoBackupLibrary("guide-android-photo-backup-library", "guide-android-photo-backup-library.png", NextcloudPresentation.Adaptive, "Guides", "Android photo backup", "Remote photo library", MarketingCapturePurpose.Showcase, "android", "phone-portrait", width = 1_080, height = 1_800, density = 2.625f),
    GuideAndroidCalendarMonth("guide-android-calendar-month", "guide-android-calendar-month.png", NextcloudPresentation.Adaptive, "Guides", "Android Calendar", "Compact month", MarketingCapturePurpose.Showcase, "android", "phone-portrait", width = 1_080, height = 1_800, density = 2.625f),
    GuideAndroidCalendarAgenda("guide-android-calendar-agenda", "guide-android-calendar-agenda.png", NextcloudPresentation.Adaptive, "Guides", "Android Calendar", "Touch agenda", MarketingCapturePurpose.Showcase, "android", "phone-portrait", width = 1_080, height = 1_800, density = 2.625f),
    GuideAndroidCalendarEdit("guide-android-calendar-edit", "guide-android-calendar-edit.png", NextcloudPresentation.Adaptive, "Guides", "Android Calendar", "Writable event context", MarketingCapturePurpose.Showcase, "android", "phone-portrait", width = 1_080, height = 1_800, density = 2.625f),
    GuideDesktopCalendarMonth("guide-desktop-calendar-month", "guide-desktop-calendar-month.png", NextcloudPresentation.Desktop, "Guides", "Desktop Calendar", "Month and inspector", MarketingCapturePurpose.Showcase, "desktop", "wide", width = 1_440, height = 900, density = 1f),
    GuideDesktopCalendarSources("guide-desktop-calendar-sources", "guide-desktop-calendar-sources.png", NextcloudPresentation.Desktop, "Guides", "Desktop Calendar", "Sources and authority", MarketingCapturePurpose.Showcase, "desktop", "wide", width = 1_440, height = 900, density = 1f),
    GuideDesktopCalendarEdit("guide-desktop-calendar-edit", "guide-desktop-calendar-edit.png", NextcloudPresentation.Desktop, "Guides", "Desktop Calendar", "Writable event context", MarketingCapturePurpose.Showcase, "desktop", "wide", width = 1_440, height = 900, density = 1f),
    GuideDesktopSwitchAppsCatalog("guide-desktop-switch-apps-catalog", "guide-desktop-switch-apps-catalog.png", NextcloudPresentation.Desktop, "Guides", "Desktop app switching", "Installed app catalog", MarketingCapturePurpose.Showcase, "desktop", "wide", width = 1_440, height = 900, density = 1f),
    GuideDesktopSwitchAppsSidebar("guide-desktop-switch-apps-sidebar", "guide-desktop-switch-apps-sidebar.png", NextcloudPresentation.Desktop, "Guides", "Desktop app switching", "Persistent shortcuts", MarketingCapturePurpose.Showcase, "desktop", "wide", width = 1_440, height = 900, density = 1f),
    GuideDesktopSwitchAppsNested("guide-desktop-switch-apps-nested", "guide-desktop-switch-apps-nested.png", NextcloudPresentation.Desktop, "Guides", "Desktop app switching", "Verified nested workspace", MarketingCapturePurpose.Showcase, "desktop", "wide", width = 1_440, height = 900, density = 1f),
}

internal fun MarketingCaptureScenario.guideCaptureSourceScenarioOrNull(): MarketingCaptureScenario? =
    when (this) {
        MarketingCaptureScenario.GuideAndroidGettingStartedHome -> MarketingCaptureScenario.HomepageOverviewMobileDark
        MarketingCaptureScenario.GuideAndroidGettingStartedFiles -> MarketingCaptureScenario.HomepageFilesMobileDark
        MarketingCaptureScenario.GuideAndroidGettingStartedCalendar -> MarketingCaptureScenario.CalendarWorkspaceMobileDark
        MarketingCaptureScenario.GuideDesktopGettingStartedHome -> MarketingCaptureScenario.HomepageOverviewDesktopDark
        MarketingCaptureScenario.GuideDesktopGettingStartedApps -> MarketingCaptureScenario.AppsWorkspaceDesktopDark
        MarketingCaptureScenario.GuideDesktopGettingStartedSettings -> MarketingCaptureScenario.DesktopStartupSettings
        MarketingCaptureScenario.GuideAndroidOfflineFilesBrowse -> MarketingCaptureScenario.HomepageFilesMobileDark
        MarketingCaptureScenario.GuideAndroidOfflineFilesStorage -> MarketingCaptureScenario.VirtualFileStorageMobile
        MarketingCaptureScenario.GuideAndroidOfflineFilesTransfers -> MarketingCaptureScenario.TransferMobileFailed
        MarketingCaptureScenario.GuideAndroidFolderSyncLocations -> MarketingCaptureScenario.ObsidianSync
        MarketingCaptureScenario.GuideAndroidFolderSyncRules -> MarketingCaptureScenario.FileSyncRulesMobile
        MarketingCaptureScenario.GuideAndroidFolderSyncStatus -> MarketingCaptureScenario.FileSyncStatusMobile
        MarketingCaptureScenario.GuideLinuxFolderSyncWorkspace -> MarketingCaptureScenario.FileSyncStatusDesktop
        MarketingCaptureScenario.GuideLinuxFolderSyncLocations -> null
        MarketingCaptureScenario.GuideLinuxFolderSyncRules -> MarketingCaptureScenario.FileSyncSetupDesktop
        MarketingCaptureScenario.GuideWindowsCloudFilesSettings -> MarketingCaptureScenario.DesktopStartupSettings
        MarketingCaptureScenario.GuideWindowsCloudFilesStorage -> MarketingCaptureScenario.WindowsCloudFilesStorageDesktop
        MarketingCaptureScenario.GuideWindowsCloudFilesRecovery -> MarketingCaptureScenario.WindowsCloudFilesRecoveryDesktop
        MarketingCaptureScenario.GuideAndroidPhotoBackupFolders -> MarketingCaptureScenario.MediaBackup
        MarketingCaptureScenario.GuideAndroidPhotoBackupQueue -> MarketingCaptureScenario.TransferMobilePending
        MarketingCaptureScenario.GuideAndroidPhotoBackupLibrary -> MarketingCaptureScenario.PhotoFolderBrowserMobile
        MarketingCaptureScenario.GuideAndroidCalendarMonth -> MarketingCaptureScenario.CalendarMonthMobile
        MarketingCaptureScenario.GuideAndroidCalendarAgenda -> MarketingCaptureScenario.CalendarWorkspaceMobileDark
        MarketingCaptureScenario.GuideAndroidCalendarEdit -> MarketingCaptureScenario.CalendarEventEditorMobile
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

data class MarketingCaptureAssets(
    val avatar: ImageBitmap,
    val mediaPreview: ImageBitmap,
    val services: NextcloudPlatformServices,
)

@Composable
internal fun MarketingFileShareScenario(
    scenario: MarketingCaptureScenario,
    fixture: MarketingFileShareFixture = nextcloudNativeMarketingFileShareFixture,
) {
    val capture = marketingFileShareCaptureState(scenario, fixture)
    val desktop = scenario.presentation == NextcloudPresentation.Desktop
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(if (desktop) NextcloudSpacing.XLarge else NextcloudSpacing.Medium),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = if (desktop) 760.dp else 560.dp)
                .fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(NextcloudSpacing.XLarge),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
            ) {
                Text(
                    text = "Share ${capture.dialog.file.name}",
                    style = MaterialTheme.typography.headlineSmall,
                )
                FileShareDialogContent(
                    state = capture.dialog,
                    onTargetChanged = { _ -> },
                    onAllowEditingChanged = { _ -> },
                    onDetailsChanged = { _ -> },
                    recipientPicker = { target ->
                        FileShareRecipientPickerContent(
                            target = target,
                            state = capture.recipientPicker,
                            enabled = !capture.dialog.running,
                            onQueryChanged = { _ -> },
                            onSelected = { _ -> },
                        )
                    },
                    existingShare = { share ->
                        ExistingFileShareSummary(
                            share = share,
                            running = false,
                            canCopy = false,
                            showManagementActions = true,
                            onCopy = {},
                            onPermissions = {},
                            onRevoke = {},
                        )
                    },
                    maximumHeight = when {
                        desktop -> 620.dp
                        scenario == MarketingCaptureScenario.FileShareLoadingMobile -> 420.dp
                        scenario == MarketingCaptureScenario.FileShareErrorMobile -> 470.dp
                        else -> 480.dp
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        NextcloudSpacing.Small,
                        Alignment.End,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FileShareDialogDismissAction(
                        state = capture.dialog,
                        onDismiss = {},
                    )
                    FileShareDialogConfirmAction(
                        state = capture.dialog,
                        onCreate = { _ -> },
                    )
                }
            }
        }
    }
}

internal data class MarketingFileShareCaptureState(
    val dialog: FileShareDialogUiState,
    val recipientPicker: FileShareRecipientPickerUiState,
)

internal fun marketingFileShareCaptureState(
    scenario: MarketingCaptureScenario,
    fixture: MarketingFileShareFixture = nextcloudNativeMarketingFileShareFixture,
): MarketingFileShareCaptureState {
    val target: FileShareTarget
    val existingShares: List<NextcloudFileShare>
    val picker: FileShareRecipientPickerUiState
    val capabilities: NextcloudFileSharingCapabilities
    when (scenario) {
        MarketingCaptureScenario.FileShareUserMobile -> {
            target = FileShareTarget.User
            existingShares = listOf(fixture.existingGroupShare)
            capabilities = fixture.capabilities
            picker = FileShareRecipientPickerUiState(
                query = "de",
                results = fixture.userResults,
            )
        }
        MarketingCaptureScenario.FileShareGroupDesktop -> {
            target = FileShareTarget.Group
            existingShares = listOf(fixture.existingUserShare)
            capabilities = fixture.capabilities
            picker = FileShareRecipientPickerUiState(
                query = "de",
                results = fixture.groupResults,
            )
        }
        MarketingCaptureScenario.FileShareLoadingMobile -> {
            target = FileShareTarget.User
            existingShares = listOf(fixture.existingGroupShare)
            capabilities = fixture.capabilities.copy(userExpirationSupported = false)
            picker = FileShareRecipientPickerUiState(
                query = "de",
                loading = true,
            )
        }
        MarketingCaptureScenario.FileShareErrorMobile -> {
            target = FileShareTarget.User
            existingShares = listOf(fixture.existingGroupShare)
            capabilities = fixture.capabilities.copy(userExpirationSupported = false)
            picker = FileShareRecipientPickerUiState(
                query = "de",
                error = "Could not search recipients. Check your connection and try again.",
            )
        }
        else -> error("${scenario.id} is not a file-share capture.")
    }
    return MarketingFileShareCaptureState(
        dialog = FileShareDialogUiState(
            file = fixture.file,
            capabilities = capabilities,
            existingShares = existingShares,
            target = target,
        ),
        recipientPicker = picker,
    )
}

@Composable
internal fun MarketingObsidianSyncScenario() {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Obsidian Vault",
            subtitle = "Two-way folder sync",
            onBack = {},
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
        ) {
            FolderSyncSection(
                snapshot = FileSyncCenterSnapshot(
                    support = FileSyncCenterSupport.Available,
                    pairs = listOf(
                        marketingSyncPair(
                            id = "fixture-obsidian",
                            name = "Obsidian Vault",
                            remote = "Notes/Obsidian",
                            direction = FileSyncDirection.Bidirectional,
                            pending = 1,
                            completed = 42,
                            schedule = "Background sync · Wi-Fi or mobile data",
                        ),
                    ),
                ),
                loading = false,
                mediaDiscovery = null,
                mediaDiscoveryLoading = false,
                busyPairId = null,
                onAdd = {},
                onOpenMediaSuggestion = {},
                onRequestMediaPermission = {},
                onRun = {},
                onRemove = {},
                onResolve = { _, _, _ -> },
            )
        }
    }
}

@Composable
internal fun MarketingMediaBackupScenario() {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Photo backup",
            subtitle = "Camera and media folders",
            onBack = {},
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(NextcloudSpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
        ) {
            item {
                FolderSyncSection(
                    snapshot = FileSyncCenterSnapshot(
                        support = FileSyncCenterSupport.Available,
                        pairs = listOf(
                            marketingSyncPair(
                                id = "fixture-camera",
                                name = "Camera",
                                remote = "Photos/Phone/Camera",
                                direction = FileSyncDirection.UploadOnly,
                                pending = 3,
                                completed = 128,
                                schedule = "Wi-Fi · battery not low",
                            ),
                        ),
                    ),
                    loading = false,
                    mediaDiscovery = MediaSyncFolderDiscovery(
                        support = MediaSyncFolderDiscoverySupport.Available,
                        suggestions = listOf(
                            MediaSyncFolderSuggestion(
                                localRootHint = "fixture-media-camera",
                                displayName = "Camera",
                                relativePath = "DCIM/Camera",
                                kind = MediaSyncFolderKind.Camera,
                                imageCount = 128,
                                videoCount = 14,
                                suggestedRemoteRootPath = "Photos/Phone/Camera",
                                totalBytes = 3_487_000_000L,
                            ),
                            MediaSyncFolderSuggestion(
                                localRootHint = "fixture-media-screenshots",
                                displayName = "Screenshots",
                                relativePath = "Pictures/Screenshots",
                                kind = MediaSyncFolderKind.Screenshots,
                                imageCount = 36,
                                videoCount = 2,
                                suggestedRemoteRootPath = "Photos/Phone/Screenshots",
                                totalBytes = 412_000_000L,
                            ),
                        ),
                    ),
                    mediaDiscoveryLoading = false,
                    busyPairId = null,
                    onAdd = {},
                    onOpenMediaSuggestion = {},
                    onRequestMediaPermission = {},
                    onRun = {},
                    onRemove = {},
                    onResolve = { _, _, _ -> },
                )
            }
        }
    }
}

@Composable
internal fun MarketingFileSyncRulesScenario() {
    var configuration by remember {
        mutableStateOf(
            FileSyncConfiguration(
                direction = FileSyncDirection.Bidirectional,
                conflictPolicy = FileSyncConflictPolicy.Ask,
                deletionPolicy = FileSyncDeletionPolicy.Ask,
                deviceLabel = "Alex's phone",
                networkPolicy = FileSyncNetworkPolicy.Unmetered,
                powerPolicy = FileSyncPowerPolicy.BatteryNotLow,
                ignoredPatterns = listOf("*.part", "**/.thumbnails/**", "**/Cache/**"),
                priorityRules = listOf(
                    FileSyncPriorityRule("**/*.raf"),
                    FileSyncPriorityRule("**/*.jpg"),
                    FileSyncPriorityRule("**/*.jpeg"),
                ),
            ),
        )
    }
    FileSyncSetupSurface(
        localRoot = FileSyncLocalRoot("fixture-studio-local", "Pictures/Studio"),
        mediaSuggestion = null,
        remotePath = "Photos/Studio",
        configuration = configuration,
        mediaPreview = null,
        mediaPreviewLoading = false,
        mediaPreviewError = null,
        busy = false,
        onDismiss = {},
        onChooseDestination = {},
        onConfigurationChanged = { configuration = it },
        onAdd = {},
        modifier = Modifier.fillMaxSize(),
        initialStep = FileSyncSetupStep.Rules,
        syntheticScopeSummary = "18,742 files - 123.4 GB - 2,511 RAW",
    )
}

@Composable
internal fun MarketingFileSyncStatusDesktopScenario() {
    NextcloudDesktopShell(
        selected = NextcloudDestination.FolderSync,
        onSelected = {},
        identity = marketingDesktopIdentity(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            FileOfflineWorkspaceTabs(
                selected = FileOfflineWorkspaceSection.FolderSync,
                onSelected = {},
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            FileSyncWorkspace(
                snapshot = FileSyncCenterSnapshot(
                    support = FileSyncCenterSupport.Available,
                    limitation = "Background checks run every two minutes while the desktop app is active.",
                    pairs = listOf(
                        FileSyncPairSummary(
                            id = "fixture-studio",
                            localDisplayName = "Studio archive",
                            localRootPath = "~/Pictures/Studio",
                            remoteRootPath = "Photos/Studio",
                            configuration = FileSyncConfiguration(
                                direction = FileSyncDirection.Bidirectional,
                                deviceLabel = "Field workstation",
                                selectedPaths = listOf("Shoots/2026", "Exports/Portfolio"),
                                ignoredPatterns = listOf("*.part", "**/.thumbnails/**"),
                                priorityRules = listOf(
                                    FileSyncPriorityRule("**/*.raf"),
                                    FileSyncPriorityRule("**/*.jpg"),
                                ),
                            ),
                            readyCount = 5,
                            runningCount = 1,
                            conflicts = emptyList(),
                            failedCount = 0,
                            skippedCount = 0,
                            completedCount = 341,
                            lastScanEpochMillis = 1,
                            scheduleDescription = "Background checks every two minutes",
                            networkState = FileSyncNetworkState.Available,
                        ),
                        FileSyncPairSummary(
                            id = "fixture-client",
                            localDisplayName = "Client selects",
                            localRootPath = "~/Pictures/Clients/Selects",
                            remoteRootPath = "Photos/Clients/Selects",
                            configuration = FileSyncConfiguration(
                                direction = FileSyncDirection.Bidirectional,
                                deviceLabel = "Field workstation",
                                ignoredPatterns = listOf("*.part"),
                            ),
                            readyCount = 5,
                            runningCount = 0,
                            conflicts = listOf(
                                FileSyncConflictSummary(
                                    workId = 42,
                                    relativePath = "cover.jpg",
                                    reason = FileSyncDecisionReason.SimultaneousEdit,
                                    choices = setOf(
                                        FileSyncDecisionChoice.UseLocal,
                                        FileSyncDecisionChoice.UseRemote,
                                        FileSyncDecisionChoice.KeepBoth,
                                        FileSyncDecisionChoice.Skip,
                                    ),
                                ),
                            ),
                            failedCount = 0,
                            skippedCount = 0,
                            completedCount = 86,
                            lastScanEpochMillis = 1,
                            scheduleDescription = "Background checks every two minutes",
                            networkState = FileSyncNetworkState.Available,
                        ),
                        FileSyncPairSummary(
                            id = "fixture-documents",
                            localDisplayName = "Project documents",
                            localRootPath = "~/Nextcloud/Projects",
                            remoteRootPath = "Work/Projects",
                            configuration = FileSyncConfiguration(
                                direction = FileSyncDirection.UploadOnly,
                                deviceLabel = "Field workstation",
                                ignoredPatterns = listOf("*.tmp"),
                            ),
                            readyCount = 0,
                            runningCount = 1,
                            conflicts = emptyList(),
                            failedCount = 0,
                            skippedCount = 0,
                            completedCount = 219,
                            lastScanEpochMillis = 1,
                            scheduleDescription = "Background checks every two minutes",
                            networkState = FileSyncNetworkState.Available,
                        ),
                        FileSyncPairSummary(
                            id = "fixture-archive",
                            localDisplayName = "Archive 2024",
                            localRootPath = "~/Pictures/Archive/2024",
                            remoteRootPath = "Photos/Archive/2024",
                            configuration = FileSyncConfiguration(
                                direction = FileSyncDirection.DownloadOnly,
                                deviceLabel = "Field workstation",
                            ),
                            readyCount = 12,
                            runningCount = 0,
                            conflicts = emptyList(),
                            failedCount = 0,
                            skippedCount = 0,
                            completedCount = 802,
                            lastScanEpochMillis = 1,
                            scheduleDescription = "Will resume when Nextcloud is reachable",
                            networkState = FileSyncNetworkState.WaitingForNetwork,
                        ),
                    ),
                ),
                loading = false,
                busyPairId = null,
                onAdd = {},
                onRun = {},
                onRemove = {},
                onResolve = { _, _, _ -> },
                initialSelectedPairId = "fixture-studio",
                modifier = Modifier.weight(1f).fillMaxWidth().padding(
                    start = NextcloudSpacing.Large,
                    end = NextcloudSpacing.Large,
                    bottom = NextcloudSpacing.Large,
                ),
                fillAvailableHeight = true,
            )
        }
    }
}

@Composable
internal fun MarketingActivityWorkspaceDesktopScenario() {
    val activities = remember { marketingActivityFixture() }
    val timeline = remember(activities) {
        ActivityTimelineState(
            activities = activities,
            initialized = true,
            nextSince = 120,
            hasMore = true,
        )
    }
    val feed = remember(activities) { buildActivityFeedPresentation(activities) }
    NextcloudDesktopShell(
        selected = NextcloudDestination.Activity,
        onSelected = {},
        identity = marketingDesktopIdentity(),
    ) {
        ActivityDesktopWorkspace(
            timeline = timeline,
            feed = feed,
            query = "",
            selectedSemantic = null,
            selectedApp = null,
            selectedType = null,
            onQueryChanged = {},
            onSemanticSelected = {},
            onAppSelected = {},
            onTypeSelected = {},
            onClearFilters = {},
            onRefresh = {},
            onLoadMore = {},
            actionFor = { activity ->
                when {
                    activity.subject.contains("conflict", ignoreCase = true) ->
                        ActivityOpenAction("Review conflict", appId = "files")
                    activity.subject.contains("expir", ignoreCase = true) ->
                        ActivityOpenAction("Extend link", appId = "files")
                    activity.subject.contains("failed", ignoreCase = true) ->
                        ActivityOpenAction("Retry upload", appId = "files")
                    else -> null
                }
            },
            onOpenAction = {},
        )
    }
}

private fun marketingActivityFixture(): List<NextcloudActivity> = listOf(
    marketingActivity(150, "files", "sync_conflict", "Sync conflict in Project plan 2026.docx", "Both copies changed", "2026-08-02T09:46:00Z"),
    marketingActivity(149, "files_sharing", "share_expiring", "Public share for Budget Q3.xlsx expires soon", "Shared link expires in 2 days", "2026-08-02T09:31:00Z"),
    marketingActivity(148, "files", "upload_failed", "Background upload failed for IMG_211830.jpg", "The connection was interrupted", "2026-08-02T09:18:00Z"),
    marketingActivity(147, "files_sharing", "shared", "Elena Schneider shared Project Phoenix", "Shared with 6 people via link", "2026-08-02T08:58:00Z"),
    marketingActivity(146, "comments", "comment", "Kai Lind commented on Budget Q3.xlsx", "Please review the updated numbers.", "2026-08-02T08:42:00Z"),
    marketingActivity(145, "spreed", "mention", "You were mentioned in Campaign Assets", "Can you confirm the final version?", "2026-08-02T08:21:00Z"),
    marketingActivity(144, "files", "file_changed", "Jonas Lund changed 3 files in Brand Kit", "logo.svg, colors.css, type-scale.md", "2026-08-02T07:48:00Z"),
    marketingActivity(143, "recognize", "system_tag", "System tag added to Photos/Camera/IMG_201.jpg", null, "2026-08-02T06:15:00Z"),
    marketingActivity(142, "recognize", "system_tag", "System tag added to Photos/Camera/IMG_202.jpg", null, "2026-08-02T06:14:00Z"),
    marketingActivity(141, "recognize", "system_tag", "System tag added to Photos/Camera/IMG_203.jpg", null, "2026-08-02T06:14:00Z"),
    marketingActivity(140, "recognize", "system_tag", "System tag added to Photos/Camera/IMG_204.jpg", null, "2026-08-02T06:13:00Z"),
    marketingActivity(139, "files", "file_created", "Mara created Field notes.md", "Projects/Research", "2026-08-01T18:24:00Z"),
)

private fun marketingActivity(
    id: Long,
    app: String,
    type: String,
    subject: String,
    message: String?,
    dateTime: String,
) = NextcloudActivity(
    id = id,
    app = app,
    type = type,
    subject = subject,
    message = message,
    objectType = null,
    objectId = null,
    objectName = null,
    link = null,
    icon = null,
    dateTime = dateTime,
)

@Composable
internal fun MarketingFileSyncSetupDesktopScenario(
    initialStep: FileSyncSetupStep = FileSyncSetupStep.Rules,
) {
    var configuration by remember {
        mutableStateOf(
            FileSyncConfiguration(
                direction = FileSyncDirection.Bidirectional,
                conflictPolicy = FileSyncConflictPolicy.Ask,
                deletionPolicy = FileSyncDeletionPolicy.Ask,
                deviceLabel = "Field workstation",
                networkPolicy = FileSyncNetworkPolicy.AnyConnection,
                powerPolicy = FileSyncPowerPolicy.BatteryNotLow,
                ignoredPatterns = listOf("*.part", "**/.thumbnails/**", "**/Cache/**"),
                priorityRules = listOf(
                    FileSyncPriorityRule("**/*.raf"),
                    FileSyncPriorityRule("**/*.jpg"),
                    FileSyncPriorityRule("**/*.jpeg"),
                ),
            ),
        )
    }
    Box(modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.XLarge), contentAlignment = Alignment.Center) {
        FileSyncSetupSurface(
            localRoot = FileSyncLocalRoot("fixture-desktop-studio", "~/Pictures/Studio"),
            mediaSuggestion = null,
            remotePath = "Photos/Studio",
            configuration = configuration,
            mediaPreview = null,
            mediaPreviewLoading = false,
            mediaPreviewError = null,
            busy = false,
            onDismiss = {},
            onChooseDestination = {},
            onConfigurationChanged = { configuration = it },
            onAdd = {},
            modifier = Modifier.fillMaxWidth().widthIn(max = 920.dp).heightIn(max = 760.dp),
            initialStep = initialStep,
            syntheticScopeSummary = "18,742 files - 123.4 GB - 2,511 RAW",
        )
    }
}

@Composable
internal fun MarketingFileSyncSelectionScenario(services: NextcloudPlatformServices) {
    Box(
        modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.XLarge),
        contentAlignment = Alignment.Center,
    ) {
        RemoteFileSyncSelectionDialog(
            services = services,
            session = NextcloudSession("https://cloud.invalid", "alex@example.invalid", "fixture"),
            userId = "alex",
            remoteRootPath = "Photos/Studio",
            initialSelection = listOf("RAW/Day 1"),
            onDismiss = {},
            onSelected = {},
            embedded = true,
        )
    }
}

@Composable
internal fun MarketingFileSyncStatusMobileScenario() {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Folder sync",
            subtitle = "Alex's phone",
            onBack = {},
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            FileSyncWorkspace(
                snapshot = FileSyncCenterSnapshot(
                    support = FileSyncCenterSupport.Available,
                    limitation = "Background sync resumes automatically when network and power rules allow it.",
                    pairs = listOf(
                        FileSyncPairSummary(
                            id = "fixture-mobile-studio",
                            localDisplayName = "Studio archive",
                            localRootPath = "Pictures/Studio",
                            remoteRootPath = "Photos/Studio",
                            configuration = FileSyncConfiguration(
                                direction = FileSyncDirection.Bidirectional,
                                deviceLabel = "Alex's phone",
                                ignoredPatterns = listOf("*.part", "**/.thumbnails/**", "**/Cache/**"),
                                priorityRules = listOf(
                                    FileSyncPriorityRule("**/*.raf"),
                                    FileSyncPriorityRule("**/*.jpg"),
                                ),
                            ),
                            readyCount = 17,
                            runningCount = 1,
                            conflicts = emptyList(),
                            failedCount = 0,
                            skippedCount = 0,
                            completedCount = 341,
                            lastScanEpochMillis = 1,
                            scheduleDescription = "Background sync enabled",
                        ),
                        FileSyncPairSummary(
                            id = "fixture-mobile-client",
                            localDisplayName = "Client selects",
                            localRootPath = "Pictures/Clients/Selects",
                            remoteRootPath = "Photos/Clients/Selects",
                            configuration = FileSyncConfiguration(
                                direction = FileSyncDirection.Bidirectional,
                                deviceLabel = "Alex's phone",
                                ignoredPatterns = listOf("*.part"),
                            ),
                            readyCount = 5,
                            runningCount = 0,
                            conflicts = listOf(
                                FileSyncConflictSummary(
                                    workId = 52,
                                    relativePath = "cover.jpg",
                                    reason = FileSyncDecisionReason.SimultaneousEdit,
                                    choices = setOf(
                                        FileSyncDecisionChoice.UseLocal,
                                        FileSyncDecisionChoice.UseRemote,
                                        FileSyncDecisionChoice.KeepBoth,
                                        FileSyncDecisionChoice.Skip,
                                    ),
                                ),
                            ),
                            failedCount = 0,
                            skippedCount = 0,
                            completedCount = 86,
                            lastScanEpochMillis = 1,
                            scheduleDescription = "Waiting for your decision",
                        ),
                        FileSyncPairSummary(
                            id = "fixture-mobile-camera",
                            localDisplayName = "Camera backup",
                            localRootPath = "DCIM/Camera",
                            remoteRootPath = "Photos/Phone camera",
                            configuration = FileSyncConfiguration(
                                direction = FileSyncDirection.UploadOnly,
                                deviceLabel = "Alex's phone",
                                networkPolicy = FileSyncNetworkPolicy.Unmetered,
                            ),
                            readyCount = 0,
                            runningCount = 0,
                            conflicts = emptyList(),
                            failedCount = 0,
                            skippedCount = 0,
                            completedCount = 1_842,
                            lastScanEpochMillis = 1,
                            scheduleDescription = "Wi-Fi only",
                        ),
                    ),
                ),
                loading = false,
                busyPairId = null,
                onAdd = {},
                onRun = {},
                onRemove = {},
                onResolve = { _, _, _ -> },
                initialSelectedPairId = "fixture-mobile-client",
            )
        }
    }
}

@Composable
internal fun MarketingVirtualFileStorageMobileScenario() {
    val snapshot = remember {
        marketingVirtualFileStorageSnapshot(
            support = VirtualFileStorageSupport.Available,
            integration = VirtualFilePlatformIntegration.AndroidDocumentsProvider,
        )
    }
    var policy by remember { mutableStateOf(snapshot.policy) }
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Virtual file storage",
            subtitle = "Cache and automatic cleanup",
            onBack = {},
        )
        VirtualFileStoragePolicyEditor(
            snapshot = snapshot,
            busy = false,
            policy = policy,
            onPolicyChanged = { policy = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(NextcloudSpacing.XLarge),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.TextButton(onClick = {}) { Text("Cancel") }
                androidx.compose.material3.Button(onClick = {}) { Text("Save rules") }
            }
        }
    }
}

@Composable
internal fun MarketingVirtualFileStorageDesktopScenario(scenario: MarketingCaptureScenario) {
    val windows = scenario == MarketingCaptureScenario.WindowsCloudFilesStorageDesktop ||
        scenario == MarketingCaptureScenario.WindowsCloudFilesRecoveryDesktop
    val recovery = scenario == MarketingCaptureScenario.WindowsCloudFilesRecoveryDesktop
    NextcloudDesktopShell(
        selected = NextcloudDestination.FolderSync,
        onSelected = {},
        identity = marketingDesktopIdentity(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            FileOfflineWorkspaceTabs(
                selected = FileOfflineWorkspaceSection.VirtualFiles,
                onSelected = {},
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
                        Text("Virtual files", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Keep your cloud visible locally and choose what must stay available offline",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text(
                            "Healthy",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                VirtualFileStorageCard(
                    snapshot = marketingVirtualFileStorageSnapshot(
                        support = VirtualFileStorageSupport.Available,
                        integration = if (windows) {
                            VirtualFilePlatformIntegration.WindowsCloudFiles
                        } else {
                            VirtualFilePlatformIntegration.LinuxFilesystemMount
                        },
                        limitations = if (recovery) {
                            listOf(
                                "A local edit conflicts with a newer remote generation and is retained for recovery.",
                                "Review the pending writeback before freeing space or removing this account.",
                            )
                        } else {
                            emptyList()
                        },
                        pendingWritebackCount = if (recovery) 1 else 0,
                    ),
                    loading = false,
                    busy = false,
                    onManage = {},
                    onFreeUp = {},
                    onActivateProvider = {},
                    onDeactivateProvider = {},
                    onChangeLocation = {},
                    onChoosePinnedFolder = {},
                    onReleaseFolder = {},
                    onRetryFolder = {},
                )
            }
        }
    }
}

private fun marketingVirtualFileStorageSnapshot(
    support: VirtualFileStorageSupport,
    integration: VirtualFilePlatformIntegration,
    limitations: List<String> = emptyList(),
    pendingWritebackCount: Int? = null,
): VirtualFileStorageSnapshot = VirtualFileStorageSnapshot(
    support = support,
    integration = integration,
    policy = VirtualFileCachePolicy(
        automaticCleanup = true,
        maximumCacheBytes = 20L * 1024L * 1024L * 1024L,
        minimumFreeSpaceBytes = 10L * 1024L * 1024L * 1024L,
        unusedFileAgeMillis = 30L * 24L * 60L * 60L * 1_000L,
    ),
    cachedBytes = 12_884_901_888L,
    reclaimableBytes = 7_193_722_880L,
    pinnedBytes = 4_482_344_960L,
    hydratedFileCount = 1_842,
    pinnedFileCount = 318,
    availableFreeBytes = 68_719_476_736L,
    storageCapacityBytes = 512L * 1024L * 1024L * 1024L,
    limitations = limitations,
    providerState = VirtualFileProviderState.Active,
    providerLocation = when (integration) {
        VirtualFilePlatformIntegration.AndroidDocumentsProvider -> "System Files / Nextcloud Native"
        VirtualFilePlatformIntegration.WindowsCloudFiles -> "Nextcloud Native in File Explorer"
        VirtualFilePlatformIntegration.LinuxFilesystemMount -> "~/Nextcloud Native"
        VirtualFilePlatformIntegration.AppleFileProvider -> "Files / Nextcloud Native"
        VirtualFilePlatformIntegration.InAppOnDemandCache -> null
    },
    providerLocationConfiguration = if (integration == VirtualFilePlatformIntegration.LinuxFilesystemMount) {
        VirtualFileProviderLocation("Home folder", "Nextcloud Native")
    } else {
        null
    },
    providerLocationCanChange = integration == VirtualFilePlatformIntegration.LinuxFilesystemMount,
    folderRetentionRules = if (integration == VirtualFilePlatformIntegration.LinuxFilesystemMount) {
        listOf(
            VirtualFolderRetentionRule("Projects/Phoenix", VirtualFolderRetention.KeepOnDevice),
            VirtualFolderRetentionRule("Photos/Portfolio", VirtualFolderRetention.KeepOnDevice),
            VirtualFolderRetentionRule("Shared/Field research", VirtualFolderRetention.KeepOnDevice),
        )
    } else {
        emptyList()
    },
    folderHydrationStatuses = if (integration == VirtualFilePlatformIntegration.LinuxFilesystemMount) {
        listOf(
            VirtualFolderHydrationStatus(
                relativePath = "Projects/Phoenix",
                phase = VirtualFolderHydrationPhase.AvailableOffline,
                verifiedAtEpochMillis = 1,
            ),
            VirtualFolderHydrationStatus(
                relativePath = "Photos/Portfolio",
                phase = VirtualFolderHydrationPhase.Downloading,
            ),
            VirtualFolderHydrationStatus(
                relativePath = "Shared/Field research",
                phase = VirtualFolderHydrationPhase.Failed,
                detail = "Connection interrupted. Existing offline files remain available.",
            ),
        )
    } else {
        emptyList()
    },
    pendingWritebackCount = pendingWritebackCount
        ?: if (integration == VirtualFilePlatformIntegration.LinuxFilesystemMount) 1 else 0,
)

@Composable
internal fun MarketingAdaptiveAppScenario(scenario: MarketingCaptureScenario) {
    require(
        scenario == MarketingCaptureScenario.HomepageAppsDesktopDark ||
            scenario == MarketingCaptureScenario.HomepageAppsDesktopLight ||
            scenario == MarketingCaptureScenario.AdaptiveApp ||
            scenario == MarketingCaptureScenario.AdaptiveAppMobile ||
            scenario == MarketingCaptureScenario.AdaptiveAppCollectionMobile ||
            scenario == MarketingCaptureScenario.AdaptiveAppContextMenuMobile,
    ) {
        "${scenario.id} is not an adaptive data capture."
    }
    MarketingDynamicUiScenario(scenario)
}

@Composable
internal fun MarketingMailWorkspaceScenario(scenario: MarketingCaptureScenario) {
    require(
        scenario in setOf(
            MarketingCaptureScenario.MailWorkspaceDesktop,
            MarketingCaptureScenario.MailWorkspaceMobile,
            MarketingCaptureScenario.MailWorkspaceLoadingMobile,
            MarketingCaptureScenario.MailWorkspaceEmptyMobile,
            MarketingCaptureScenario.MailWorkspaceErrorDesktop,
        ),
    ) {
        "${scenario.id} is not a Mail workspace capture."
    }
    val desktop = scenario.presentation == NextcloudPresentation.Desktop
    val composeAction = marketingMailDescriptor.preferredNativeMailComposeAction(marketingMailSchema)
    val currentView = if (desktop) marketingMailBodyView else marketingMailMessageView
    val currentState = when (scenario) {
        MarketingCaptureScenario.MailWorkspaceLoadingMobile -> NativeScreenState.Loading
        MarketingCaptureScenario.MailWorkspaceEmptyMobile -> NativeScreenState.Ready(emptyList())
        MarketingCaptureScenario.MailWorkspaceErrorDesktop -> NativeScreenState.Error(
            message = "The server did not return the selected message body.",
            retry = {},
            retryLabel = "Try again",
        )
        else -> NativeScreenState.Ready(
            if (desktop) listOf(marketingMailBodyRecord) else marketingMailMessages,
        )
    }
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Mail",
            subtitle = "Inbox",
            onBack = {},
            trailingContent = {
                val compose = composeAction
                if (compose != null) {
                    androidx.compose.material3.Button(onClick = {}) {
                        Text(compose.label)
                    }
                }
            },
        )
        GenericNativeAppScreen(
            schema = marketingMailSchema,
            view = currentView,
            state = currentState,
            actionExecutor = NativeActionExecutor {
                NativeActionExecutionResult.Failure("This synthetic fixture is read-only.")
            },
            selectedRecordId = if (desktop) marketingMailSelectedMessage.id else null,
            selectedRecordResourceId = if (desktop) "messages" else null,
            showSelectedRecordDetail = desktop,
            onSelectRecord = {},
            datasetContext = NativeDatasetContext(
                parentResourceId = if (desktop) "messages" else "mailboxes",
                parentRecord = if (desktop) marketingMailSelectedMessage else marketingMailInbox,
                relatedRecords = mapOf(
                    "accounts" to listOf(marketingMailAccount),
                    "mailboxes" to marketingMailboxes,
                    "messages" to marketingMailMessages,
                ),
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun MarketingHomeDashboardScenario(
    scenario: MarketingCaptureScenario,
    fixture: MarketingDemoFixture,
) {
    val formFactor = when (scenario.presentation) {
        NextcloudPresentation.Desktop -> HomeFormFactor.Desktop
        NextcloudPresentation.Adaptive -> HomeFormFactor.Phone
    }
    val workspaceScope = remember(formFactor) {
        HomeWorkspaceScope(
            accountScopeDigest = "8a2df7f31f8de281a514cfe02d04ba13dc793be7b88b890b6c415f7e3290bd85",
            formFactor = formFactor,
        )
    }
    NativeDashboardPresentation(
        state = DashboardSurfaceState.Available(
            snapshot = marketingDashboardSnapshot,
            status = if (scenario.feature == "Homepage") {
                marketingHomepageUserStatus
            } else {
                marketingUserStatus
            },
        ),
        installedApps = fixture.apps,
        workspaceLayout = defaultHomeWorkspaceLayout(workspaceScope),
        onWorkspaceLayoutChanged = { true },
        onOpenApp = {},
        onOpenStatus = {},
        onOpenLink = {},
        onBack = null,
        onRefresh = {},
        onSearch = {},
        onSettings = {},
    )
}

@Composable
internal fun MarketingDeckBoardScenario() {
    NativeDeckBoardSurface(
        state = DeckWorkspaceState.Board(
            board = marketingDeckBoard,
            stacks = marketingDeckStacks,
        ),
        onExit = {},
        onSelectBoard = {},
        onBackToBoards = {},
        onOpenCard = {},
        onSelectCard = {},
        onDismissCard = {},
        onRetry = {},
        onCreateStack = {},
        onCreateCard = {},
        onMoveCard = { _, _, _ -> },
        modifier = Modifier.fillMaxSize(),
    )
}

private fun marketingSyncPair(
    id: String,
    name: String,
    remote: String,
    direction: FileSyncDirection,
    pending: Int,
    completed: Int,
    schedule: String,
) = FileSyncPairSummary(
    id = id,
    localDisplayName = name,
    remoteRootPath = remote,
    configuration = FileSyncConfiguration(
        direction = direction,
        deviceLabel = "fixture-mobile",
    ),
    readyCount = pending,
    runningCount = 0,
    conflicts = emptyList(),
    failedCount = 0,
    skippedCount = 0,
    completedCount = completed,
    lastScanEpochMillis = 1,
    scheduleDescription = schedule,
)

internal val marketingAdaptiveSchema = NativeAppSchema(
    schemaVersion = "0.1",
    app = AppIdentity("fixture-inventory", "Community inventory", "fixture"),
    confidence = Confidence.verified,
    resources = listOf(
        ResourceSpec(
            id = "items",
            name = "Inventory items",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("name", "Item", FieldKind.string, required = true, readOnly = true),
                FieldSpec("category", "Category", FieldKind.string, required = false, readOnly = true),
                FieldSpec(
                    "amount",
                    "Value",
                    FieldKind.currency,
                    required = false,
                    readOnly = true,
                    format = "EUR",
                ),
                FieldSpec("status", "Status", FieldKind.enumeration, required = false, readOnly = true),
                FieldSpec("updated", "Updated", FieldKind.date, required = false, readOnly = true),
            ),
        ),
    ),
    views = listOf(
        ViewSpec(
            id = "items.table",
            title = "Inventory",
            resourceId = "items",
            component = NativeComponent.dataTable,
            sourceActionId = "fixture.items.list",
            confidence = Confidence.verified,
        ),
    ),
)

internal val marketingAdaptiveRecords = listOf(
    NativeRecord(
        id = "item-1",
        values = mapOf(
            "name" to "Field recorder",
            "category" to "Audio",
            "amount" to "219.00",
            "status" to "Available",
            "updated" to "2026-07-24",
        ),
    ),
    NativeRecord(
        id = "item-2",
        values = mapOf(
            "name" to "Tripod",
            "category" to "Camera",
            "amount" to "84.50",
            "status" to "On loan",
            "updated" to "2026-07-23",
        ),
    ),
    NativeRecord(
        id = "item-3",
        values = mapOf(
            "name" to "USB-C hub",
            "category" to "Computer",
            "amount" to "49.95",
            "status" to "Available",
            "updated" to "2026-07-22",
        ),
    ),
    NativeRecord(
        id = "item-4",
        values = mapOf(
            "name" to "Lighting kit",
            "category" to "Camera",
            "amount" to "135.00",
            "status" to "Reserved",
            "updated" to "2026-07-21",
        ),
    ),
    NativeRecord(
        id = "item-5",
        values = mapOf(
            "name" to "Studio monitor",
            "category" to "Audio",
            "amount" to "175.00",
            "status" to "Available",
            "updated" to "2026-07-19",
        ),
    ),
)

private val marketingMailComposeAction = DynamicAction(
    id = "compose-message",
    label = "Compose",
    resourceId = "messages",
    intent = ActionIntent.create,
    risk = ActionRisk.mutating,
    requiresConfirmation = false,
    binding = DynamicHttpBinding(method = HttpMethod.POST, path = "/fixture/messages"),
    confidence = Confidence.verified,
)

private val marketingMailSchema = NativeAppSchema(
    schemaVersion = "0.1",
    app = AppIdentity("fixture-mail", "Mail", "fixture"),
    confidence = Confidence.verified,
    resources = listOf(
        ResourceSpec(
            id = "accounts",
            name = "Accounts",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("accountName", "Account", FieldKind.string, required = true, readOnly = true),
                FieldSpec("emailAddress", "Email", FieldKind.string, required = true, readOnly = true),
            ),
        ),
        ResourceSpec(
            id = "mailboxes",
            name = "Mailboxes",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("name", "Mailbox", FieldKind.string, required = true, readOnly = true),
                FieldSpec("specialUse", "Role", FieldKind.string, required = false, readOnly = true),
                FieldSpec("unreadCount", "Unread", FieldKind.integer, required = false, readOnly = true),
                FieldSpec("path", "Path", FieldKind.string, required = false, readOnly = true),
            ),
        ),
        ResourceSpec(
            id = "messages",
            name = "Messages",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("subject", "Subject", FieldKind.string, required = false, readOnly = true),
                FieldSpec("from", "From", FieldKind.string, required = false, readOnly = true),
                FieldSpec("preview", "Preview", FieldKind.string, required = false, readOnly = true),
                FieldSpec("date", "Date", FieldKind.dateTime, required = false, readOnly = true),
                FieldSpec("seen", "Seen", FieldKind.boolean, required = false, readOnly = true),
                FieldSpec("flagged", "Flagged", FieldKind.boolean, required = false, readOnly = true),
            ),
        ),
        ResourceSpec(
            id = "messageBody",
            name = "Message body",
            confidence = Confidence.verified,
            fields = listOf(
                FieldSpec("body", "Body", FieldKind.longText, required = true, readOnly = true),
                FieldSpec("hasHtmlBody", "HTML", FieldKind.boolean, required = false, readOnly = true),
            ),
        ),
    ),
    views = listOf(
        ViewSpec(
            id = "messages.mailbox",
            title = "Inbox",
            resourceId = "messages",
            component = NativeComponent.mailbox,
            sourceActionId = "fixture.messages.list",
            confidence = Confidence.verified,
        ),
        ViewSpec(
            id = "message.body",
            title = "Message",
            resourceId = "messageBody",
            component = NativeComponent.detail,
            sourceActionId = "fixture.message.body",
            confidence = Confidence.verified,
        ),
    ),
    actions = listOf(
        ActionSpec(
            id = marketingMailComposeAction.id,
            label = marketingMailComposeAction.label,
            resourceId = marketingMailComposeAction.resourceId,
            binding = ApiBinding(
                method = marketingMailComposeAction.binding.method,
                path = marketingMailComposeAction.binding.path,
                operationId = marketingMailComposeAction.id,
            ),
            intent = marketingMailComposeAction.intent,
            risk = marketingMailComposeAction.risk,
            requiresConfirmation = marketingMailComposeAction.requiresConfirmation,
            confidence = marketingMailComposeAction.confidence,
        ),
    ),
)

private val marketingMailDescriptor = DynamicAppDescriptor(
    descriptorVersion = "0.1",
    app = AppIdentity("fixture-mail", "Mail", "fixture"),
    endpointPolicy = EndpointPolicy(serverOrigin = "https://fixture.invalid"),
    resources = emptyList(),
    actions = listOf(marketingMailComposeAction),
    forms = listOf(
        DynamicForm(
            id = "compose-message-form",
            title = "Compose",
            resourceId = "messages",
            actionId = marketingMailComposeAction.id,
            confidence = Confidence.verified,
        ),
    ),
)

private val marketingMailMessageView = requireNotNull(
    marketingMailSchema.views.firstOrNull { view -> view.id == "messages.mailbox" },
)
private val marketingMailBodyView = requireNotNull(
    marketingMailSchema.views.firstOrNull { view -> view.id == "message.body" },
)
private val marketingMailAccount = NativeRecord(
    id = "personal",
    values = mapOf(
        "accountName" to "Obiente",
        "emailAddress" to "obiente@example.test",
    ),
)
private val marketingMailInbox = NativeRecord(
    id = "inbox",
    values = mapOf(
        "name" to "Inbox",
        "specialUse" to "inbox",
        "unreadCount" to "2",
        "path" to "Personal/Inbox",
        "accountId" to marketingMailAccount.id,
    ),
)
private val marketingMailboxes = listOf(
    marketingMailInbox,
    NativeRecord(
        id = "drafts",
        values = mapOf(
            "name" to "Drafts", "specialUse" to "drafts", "path" to "Personal/Drafts",
            "accountId" to marketingMailAccount.id,
        ),
    ),
    NativeRecord(
        id = "sent",
        values = mapOf(
            "name" to "Sent", "specialUse" to "sent", "path" to "Personal/Sent",
            "accountId" to marketingMailAccount.id,
        ),
    ),
    NativeRecord(
        id = "archive",
        values = mapOf(
            "name" to "Archive", "specialUse" to "archive", "path" to "Personal/Archive",
            "accountId" to marketingMailAccount.id,
        ),
    ),
)
private val marketingMailMessages = listOf(
    NativeRecord(
        id = "mail-1",
        values = mapOf(
            "subject" to "Release candidate is ready",
            "from" to "Ada <ada@example.test>",
            "preview" to "The Android and desktop artifacts passed the final checks.",
            "date" to "2026-07-29T08:42:00Z",
            "seen" to "false",
            "flagged" to "true",
            "accountId" to marketingMailAccount.id,
            "mailboxId" to marketingMailInbox.id,
        ),
    ),
    NativeRecord(
        id = "mail-2",
        values = mapOf(
            "subject" to "Design review notes",
            "from" to "Mira <mira@example.test>",
            "preview" to "I added the adaptive navigation feedback to the shared notes.",
            "date" to "2026-07-28T17:30:00Z",
            "seen" to "false",
            "accountId" to marketingMailAccount.id,
            "mailboxId" to marketingMailInbox.id,
        ),
    ),
    NativeRecord(
        id = "mail-3",
        values = mapOf(
            "subject" to "Community call",
            "from" to "Nextcloud community <community@example.test>",
            "preview" to "Here is the agenda for Thursday's community call.",
            "date" to "2026-07-27T11:05:00Z",
            "seen" to "true",
            "accountId" to marketingMailAccount.id,
            "mailboxId" to marketingMailInbox.id,
        ),
    ),
)
private val marketingMailSelectedMessage = marketingMailMessages.first()
private val marketingMailBodyRecord = NativeRecord(
    id = marketingMailSelectedMessage.id,
    values = mapOf(
        "body" to """
            <p>Hello Obiente,</p>
            <p>The <strong>release candidate</strong> is ready for review.</p>
            <p>Android and desktop artifacts passed the final checks. The visual audit is attached to the build.</p>
            <p>Thanks,<br>Ada</p>
        """.trimIndent(),
        "hasHtmlBody" to "true",
    ),
)

internal val marketingDashboardSnapshot = NativeDashboardSnapshot(
    widgets = listOf(
        marketingDashboardWidget("activity", "Recent activity", 10),
        marketingDashboardWidget("calendar", "Upcoming events", 20),
        marketingDashboardWidget("recommendations", "Recent files", 30),
        marketingDashboardWidget("photos", "Photo backup", 40),
        marketingDashboardWidget("favorites", "Favorite files", 50),
        marketingDashboardWidget("storage", "Storage", 60),
        marketingDashboardWidget("talk", "Unread conversations", 70),
        marketingDashboardWidget("mail", "Important mail", 80),
    ),
    itemsByWidget = mapOf(
        "activity" to listOf(
            marketingDashboardItem(
                widgetId = "activity",
                title = "Project brief was updated",
                subtitle = "A few minutes ago",
                sinceId = "activity-2",
            ),
            marketingDashboardItem(
                widgetId = "activity",
                title = "A design file was shared",
                subtitle = "Today",
                sinceId = "activity-1",
            ),
            marketingDashboardItem("activity", "Kai commented on Q3 roadmap.xlsx", "18 minutes ago", "activity-0b"),
            marketingDashboardItem("activity", "Camera backup uploaded 27 new photos", "42 minutes ago", "activity-0a"),
        ),
        "calendar" to listOf(
            marketingDashboardItem(
                widgetId = "calendar",
                title = "Product planning",
                subtitle = "Today at 14:00",
                sinceId = "calendar-2",
            ),
            marketingDashboardItem(
                widgetId = "calendar",
                title = "Community call",
                subtitle = "Tomorrow at 10:30",
                sinceId = "calendar-1",
            ),
            marketingDashboardItem("calendar", "Design review", "Tomorrow at 14:30 · Product room", "calendar-0b"),
            marketingDashboardItem("calendar", "Release retrospective", "Friday at 09:30", "calendar-0a"),
        ),
        "recommendations" to listOf(
            marketingDashboardItem(
                widgetId = "recommendations",
                title = "Product brief.pdf",
                subtitle = "Projects",
                sinceId = "files-2",
            ),
            marketingDashboardItem(
                widgetId = "recommendations",
                title = "Release notes.md",
                subtitle = "Notes",
                sinceId = "files-1",
            ),
            marketingDashboardItem("recommendations", "Q3 roadmap.xlsx", "Projects · edited 18 min ago", "files-0b"),
            marketingDashboardItem("recommendations", "Brand presentation.pptx", "Design system", "files-0a"),
        ),
        "photos" to listOf(
            marketingDashboardItem(
                widgetId = "photos",
                title = "Camera backup is up to date",
                subtitle = "128 photos and 14 videos",
                sinceId = "photos-1",
            ),
            marketingDashboardItem("photos", "Weekend in Texel", "38 new photos · Yesterday", "photos-0b"),
            marketingDashboardItem("photos", "7 people recognized", "Review suggested matches", "photos-0a"),
        ),
        "favorites" to listOf(
            marketingDashboardItem("favorites", "Q3 roadmap.xlsx", "Projects/Planning", "favorites-3"),
            marketingDashboardItem("favorites", "Product direction.md", "Projects/Native", "favorites-2"),
            marketingDashboardItem("favorites", "Brand presentation.pptx", "Design system", "favorites-1"),
        ),
        "storage" to listOf(
            marketingDashboardItem("storage", "34.2 GB of 100 GB used", "65.8 GB available", "storage-2"),
            marketingDashboardItem("storage", "8.6 GB available offline", "4 folder sync pairs", "storage-1"),
        ),
        "talk" to listOf(
            marketingDashboardItem("talk", "Nextcloud Native", "Mara: The updated brief is ready · 3 unread", "talk-4"),
            marketingDashboardItem("talk", "Design system", "Kai: I reviewed the new tokens · 1 unread", "talk-3"),
            marketingDashboardItem("talk", "Community", "Elena: See you at the call · 1 unread", "talk-2"),
            marketingDashboardItem("talk", "Release crew", "You: Desktop artifacts are ready", "talk-1"),
        ),
        "mail" to listOf(
            marketingDashboardItem("mail", "Release candidate is ready", "Ada Lovelace · 8 minutes ago", "mail-4"),
            marketingDashboardItem("mail", "Design review notes", "Kai Lind · 31 minutes ago", "mail-3"),
            marketingDashboardItem("mail", "Your weekly cloud summary", "Nextcloud · Today", "mail-2"),
            marketingDashboardItem("mail", "Community call agenda", "Elena Schneider · Yesterday", "mail-1"),
        ),
    ),
)

internal val marketingUserStatus = NativeUserStatus(
    userId = "fixture-user",
    presence = NativeUserPresence.Online,
    message = "Building the next native experience",
    icon = null,
    messageId = null,
    clearAtEpochSeconds = null,
    messageIsPredefined = false,
    statusIsUserDefined = true,
)

internal val marketingHomepageUserStatus = marketingUserStatus.copy(
    message = "Your cloud is ready",
)

private fun marketingDashboardWidget(
    id: String,
    title: String,
    order: Int,
) = NativeDashboardWidget(
    id = id,
    title = title,
    order = order,
    iconUrl = null,
    iconClass = null,
    widgetUrl = null,
    itemApiVersions = setOf(2),
    itemIconsRound = false,
    reloadIntervalSeconds = null,
    actions = emptyList(),
)

private fun marketingDashboardItem(
    widgetId: String,
    title: String,
    subtitle: String,
    sinceId: String,
) = NativeDashboardItem(
    widgetId = widgetId,
    title = title,
    subtitle = subtitle,
    link = null,
    iconUrl = null,
    overlayIconUrl = null,
    sinceId = sinceId,
)

private val marketingDeckBoard = DeckBoard(
    id = 1,
    title = "Home renovation",
    color = "8b5cf6",
    archived = false,
    owner = DeckUser("fixture-owner", "Demo owner"),
    labels = emptyList(),
    permissions = DeckPermissions(
        canRead = true,
        canEdit = true,
        canManage = true,
        canShare = false,
    ),
    shared = false,
    lastModified = null,
    etag = null,
)

private val marketingDeckStacks = listOf(
    marketingDeckStack(
        id = 10,
        title = "Planned",
        cards = listOf(
            marketingDeckCard(100, 10, "Measure kitchen cabinets", 100),
            marketingDeckCard(101, 10, "Compare paint samples", 200),
            marketingDeckCard(102, 10, "Request tile samples", 300),
        ),
    ),
    marketingDeckStack(
        id = 20,
        title = "In progress",
        cards = listOf(
            marketingDeckCard(200, 20, "Book the electrician", 100),
            marketingDeckCard(201, 20, "Choose hallway lighting", 200),
            marketingDeckCard(202, 20, "Patch the living room wall", 300),
        ),
    ),
    marketingDeckStack(
        id = 30,
        title = "Done",
        cards = listOf(
            marketingDeckCard(300, 30, "Order shelf brackets", 100),
            marketingDeckCard(301, 30, "Set the renovation budget", 200),
            marketingDeckCard(302, 30, "Photograph existing wiring", 300),
        ),
    ),
)

private fun marketingDeckStack(
    id: Long,
    title: String,
    cards: List<DeckCard>,
) = DeckStack(
    id = id,
    boardId = marketingDeckBoard.id,
    title = title,
    order = id,
    doneColumn = title == "Done",
    cards = cards,
    lastModified = null,
    etag = null,
)

private fun marketingDeckCard(
    id: Long,
    stackId: Long,
    title: String,
    order: Long,
) = DeckCard(
    id = id,
    boardId = marketingDeckBoard.id,
    stackId = stackId,
    title = title,
    descriptionMarkdown = null,
    ownerId = "fixture-owner",
    color = null,
    order = order,
    dueAt = null,
    startAt = null,
    completedAt = null,
    archived = false,
    overdue = false,
    labels = emptyList(),
    assignees = emptyList(),
    attachmentCount = 0,
    unreadCommentCount = 0,
    etag = null,
)
