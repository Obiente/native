package dev.obiente.nextcloudnative.app

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.obiente.nextcloudnative.app.design.NextcloudAppBackground
import dev.obiente.nextcloudnative.app.design.NextcloudDestination
import dev.obiente.nextcloudnative.app.design.NextcloudNativeTheme
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopMasterDetail
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopWorkspaceKind
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudPresentation
import dev.obiente.nextcloudnative.app.design.NextcloudTypography

/**
 * Renders the real root shell and home components against the compile-time synthetic fixture.
 *
 * Capture builds call this directly, so they cannot load sessions, caches, endpoints, or media.
 */
@Composable
fun NextcloudNativeMarketingCapture(
    scenario: MarketingCaptureScenario,
    assets: MarketingCaptureAssets,
    fixture: MarketingDemoFixture = nextcloudNativeMarketingFixture,
    darkTheme: Boolean = scenario.darkTheme,
    typography: Typography = NextcloudTypography,
) {
    scenario.guideCaptureSourceScenarioOrNull()?.let { sourceScenario ->
        NextcloudNativeMarketingCapture(
            scenario = sourceScenario,
            assets = assets,
            fixture = fixture,
            darkTheme = darkTheme,
            typography = typography,
        )
        return
    }
    NextcloudNativeTheme(darkTheme = darkTheme, typography = typography) {
        NextcloudAppBackground {
            val desktop = scenario.presentation == NextcloudPresentation.Desktop
            CompositionLocalProvider(
                LocalNextcloudWorkspaceCapabilities provides NextcloudWorkspaceCapabilities(
                    isDesktop = desktop,
                    usesDenseControls = desktop,
                    supportsAuxiliaryPane = desktop,
                ),
            ) {
                when (scenario) {
                    MarketingCaptureScenario.SharedControlsDesktop,
                    MarketingCaptureScenario.SharedControlsMobile,
                    -> MarketingSharedControlsScenario(scenario)
                    MarketingCaptureScenario.ShellCompactDesktop,
                    MarketingCaptureScenario.ShellAppSwitcherMobile,
                    MarketingCaptureScenario.ShellTablet,
                    -> MarketingShellCaptureScenario(scenario, assets)
                    MarketingCaptureScenario.HomepageOverviewDesktopDark,
                    MarketingCaptureScenario.HomepageOverviewDesktopLight,
                    MarketingCaptureScenario.HomepageOverviewMobileDark,
                    MarketingCaptureScenario.HomepageOverviewMobileLight,
                    MarketingCaptureScenario.DesktopHome,
                    MarketingCaptureScenario.MobileHome,
                    -> {
                        RootShell(
                            presentation = scenario.presentation,
                            selected = NextcloudDestination.Home,
                            desktopWorkspaceKind = NextcloudDesktopWorkspaceKind.Root,
                            onSelected = {},
                            identity = marketingDesktopIdentity(fixture, assets.avatar),
                        ) {
                            MarketingHomeDashboardScenario(scenario, fixture)
                        }
                    }
                    MarketingCaptureScenario.HomepageFilesDesktopDark,
                    MarketingCaptureScenario.HomepageFilesDesktopLight,
                    MarketingCaptureScenario.HomepageFilesMobileDark,
                    MarketingCaptureScenario.HomepageFilesMobileLight,
                    -> if (desktop) {
                        RootShell(
                            presentation = NextcloudPresentation.Desktop,
                            selected = NextcloudDestination.Apps,
                            desktopWorkspaceKind = NextcloudDesktopWorkspaceKind.AppWorkspace,
                            onSelected = {},
                            identity = marketingDesktopIdentity(fixture, assets.avatar),
                            activeAppId = "files",
                        ) {
                            FilesScreen(
                                services = assets.services,
                                session = marketingHomepageSession,
                                userId = marketingHomepageTalkUserId,
                                fileSharing = nextcloudNativeMarketingFileShareFixture.capabilities,
                                path = "",
                                layout = FileLayout.List,
                                onLayoutChanged = {},
                                onBack = {},
                                onOpenFolder = {},
                                onOpenFile = { _, _ -> },
                                onFileAction = { _, _, _ -> },
                                initialSelectedFilePath = "Product direction.md",
                            )
                        }
                    } else {
                        FilesScreen(
                            services = assets.services,
                            session = marketingHomepageSession,
                            userId = marketingHomepageTalkUserId,
                            fileSharing = nextcloudNativeMarketingFileShareFixture.capabilities,
                            path = "",
                            layout = FileLayout.List,
                            onLayoutChanged = {},
                            onBack = {},
                            onOpenFolder = {},
                            onOpenFile = { _, _ -> },
                            onFileAction = { _, _, _ -> },
                            initialSelectedFilePath = "Product direction.md",
                        )
                    }
                    MarketingCaptureScenario.HomepageConversationsDesktopDark,
                    MarketingCaptureScenario.HomepageConversationsDesktopLight,
                    -> MarketingDesktopConversationsScenario(fixture, assets)
                    MarketingCaptureScenario.HomepageAppsDesktopDark,
                    MarketingCaptureScenario.HomepageAppsDesktopLight,
                    MarketingCaptureScenario.AdaptiveApp,
                    MarketingCaptureScenario.TablesRowsDesktop,
                    MarketingCaptureScenario.TablesRowFormDesktop,
                    MarketingCaptureScenario.TablesColumnsDesktop,
                    MarketingCaptureScenario.TablesViewsDesktop,
                    MarketingCaptureScenario.TablesSharesDesktop,
                    MarketingCaptureScenario.AdaptiveAppMobile,
                    MarketingCaptureScenario.AdaptiveAppCollectionMobile,
                    MarketingCaptureScenario.AdaptiveAppContextMenuMobile,
                    MarketingCaptureScenario.TablesColumnsMobile,
                    MarketingCaptureScenario.TablesViewsMobile,
                    MarketingCaptureScenario.TablesSharesMobile,
                    -> MarketingAdaptiveAppScenario(scenario)
                    MarketingCaptureScenario.InlineRecordEditDesktop,
                    MarketingCaptureScenario.InlineRecordEditMobile,
                    -> MarketingInlineRecordEditShell(scenario, fixture, assets)
                    MarketingCaptureScenario.AppsWorkspaceDesktopDark,
                    MarketingCaptureScenario.AppsWorkspaceDesktopLight,
                    -> MarketingAppsWorkspaceScenario(fixture, assets)
                    MarketingCaptureScenario.CalendarWorkspaceDesktopDark,
                    MarketingCaptureScenario.CalendarWorkspaceDesktopLight,
                    MarketingCaptureScenario.CalendarWorkspaceMobileDark,
                    MarketingCaptureScenario.CalendarWorkspaceMobileLight,
                    MarketingCaptureScenario.CalendarMonthMobile,
                    MarketingCaptureScenario.CalendarWeekMobile,
                    MarketingCaptureScenario.CalendarWeekDesktop,
                    MarketingCaptureScenario.CalendarEventEditorMobile,
                    MarketingCaptureScenario.CalendarEventEditorDesktop,
                    -> MarketingCalendarWorkspaceScenario(scenario, assets)
                    MarketingCaptureScenario.MailWorkspaceDesktop,
                    MarketingCaptureScenario.MailWorkspaceMobile,
                    MarketingCaptureScenario.MailMessageBodyMobile,
                    MarketingCaptureScenario.MailWorkspaceLoadingMobile,
                    MarketingCaptureScenario.MailWorkspaceEmptyMobile,
                    MarketingCaptureScenario.MailWorkspaceErrorDesktop,
                    -> MarketingMailWorkspaceScenario(scenario)
                    MarketingCaptureScenario.PhotoTimelineRevalidationErrorMobile,
                    MarketingCaptureScenario.PhotoTimelineReturnToNewestErrorMobile,
                    MarketingCaptureScenario.PhotoTimelineRawRetryMobile,
                    -> MarketingPhotoTimelineFailureScenario(scenario)
                    MarketingCaptureScenario.PhotoFolderBrowserMobile,
                    MarketingCaptureScenario.PhotoFolderBrowserDesktop,
                    MarketingCaptureScenario.HomepagePhotosDesktopDark,
                    MarketingCaptureScenario.HomepagePhotosDesktopLight,
                    -> MarketingPhotoFolderScenario(scenario, assets)
                    MarketingCaptureScenario.ObsidianSync -> MarketingObsidianSyncScenario()
                    MarketingCaptureScenario.MediaBackup -> MarketingMediaBackupScenario()
                    MarketingCaptureScenario.FileSyncRulesMobile -> MarketingFileSyncRulesScenario()
                    MarketingCaptureScenario.FileSyncStatusMobile -> MarketingFileSyncStatusMobileScenario()
                    MarketingCaptureScenario.FileSyncStatusDesktop -> MarketingFileSyncStatusDesktopScenario()
                    MarketingCaptureScenario.ActivityWorkspaceDesktop -> MarketingActivityWorkspaceDesktopScenario()
                    MarketingCaptureScenario.ActivityWorkspaceMobileDark,
                    MarketingCaptureScenario.ActivityWorkspaceMobileLight,
                    -> MarketingActivityWorkspaceMobileScenario()
                    MarketingCaptureScenario.BudgetDashboardDesktopDark,
                    MarketingCaptureScenario.BudgetDashboardDesktopLight,
                    MarketingCaptureScenario.BudgetDashboardMobileDark,
                    MarketingCaptureScenario.BudgetDashboardMobileLight,
                    -> MarketingBudgetDashboardScenario(scenario)
                    MarketingCaptureScenario.BudgetTransactionsDesktop,
                    MarketingCaptureScenario.BudgetTransactionsMobile,
                    MarketingCaptureScenario.BudgetAccountsDesktop,
                    MarketingCaptureScenario.BudgetAccountsMobile,
                    MarketingCaptureScenario.BudgetCategoriesDesktop,
                    MarketingCaptureScenario.BudgetCategoriesMobile,
                    MarketingCaptureScenario.BudgetPlanDesktop,
                    MarketingCaptureScenario.BudgetPlanMobile,
                    -> MarketingBudgetDynamicWorkspaceScenario(scenario)
                    MarketingCaptureScenario.FileSyncSetupDesktop -> MarketingFileSyncSetupDesktopScenario()
                    MarketingCaptureScenario.GuideLinuxFolderSyncLocations ->
                        MarketingFileSyncSetupDesktopScenario(initialStep = FileSyncSetupStep.Locations)
                    MarketingCaptureScenario.GuideLinuxFolderSyncRules ->
                        MarketingFileSyncSetupDesktopScenario(
                            initialStep = FileSyncSetupStep.Review,
                            initialAdvancedSettingsVisible = true,
                        )
                    MarketingCaptureScenario.GuideAndroidFolderSyncLocations ->
                        MarketingFileSyncRulesScenario(initialStep = FileSyncSetupStep.Locations)
                    MarketingCaptureScenario.GuideAndroidFolderSyncRules ->
                        MarketingFileSyncRulesScenario(
                            initialStep = FileSyncSetupStep.Review,
                            initialAdvancedSettingsVisible = true,
                        )
                    MarketingCaptureScenario.GuideAndroidCalendarEdit ->
                        MarketingCalendarRecurringEventDetailCapture()
                    MarketingCaptureScenario.GuideAndroidOfflineFilesTransfers ->
                        MarketingOfflineFileTransferScenario()
                    MarketingCaptureScenario.GuideAndroidOfflineFilesStorage ->
                        MarketingVirtualFileStorageOverviewMobileScenario()
                    MarketingCaptureScenario.GuideWindowsCloudFilesSettings ->
                        MarketingVirtualFileStorageDesktopScenario(scenario)
                    MarketingCaptureScenario.GuideAndroidPhotoBackupLibrary ->
                        MarketingMediaTransferScenario(scenario)
                    MarketingCaptureScenario.FileSyncSelectionDesktop,
                    MarketingCaptureScenario.FileSyncSelectionMobile,
                    ->
                        MarketingFileSyncSelectionScenario(assets.services)
                    MarketingCaptureScenario.VirtualFileStorageMobile -> MarketingVirtualFileStorageMobileScenario()
                    MarketingCaptureScenario.VirtualFileStorageDesktop,
                    MarketingCaptureScenario.WindowsCloudFilesStorageDesktop,
                    MarketingCaptureScenario.WindowsCloudFilesRecoveryDesktop,
                    -> MarketingVirtualFileStorageDesktopScenario(scenario)
                    MarketingCaptureScenario.DesktopStartupSettings ->
                        MarketingDesktopStartupSettingsScenario(fixture, assets)
                    MarketingCaptureScenario.RawPreviewLoadingMobile,
                    MarketingCaptureScenario.RawPreviewErrorMobile,
                    MarketingCaptureScenario.RawPreviewMemoriesReadyMobile,
                    MarketingCaptureScenario.RawPreviewHighDetailDesktop,
                    MarketingCaptureScenario.NativeTiffPreviewMobile,
                    -> error("Native media captures require the isolated desktop fixture renderer.")
                    MarketingCaptureScenario.LivePhotoMotionFailureMobile ->
                        MarketingLivePhotoMotionFailureScenario(assets.mediaPreview)
                    MarketingCaptureScenario.FileShareUserMobile,
                    MarketingCaptureScenario.FileShareGroupDesktop,
                    MarketingCaptureScenario.FileShareLoadingMobile,
                    MarketingCaptureScenario.FileShareErrorMobile,
                    -> MarketingFileShareScenario(scenario)
                    MarketingCaptureScenario.TransferMobilePending,
                    MarketingCaptureScenario.TransferMobileFailed,
                    MarketingCaptureScenario.TransferDesktopActive,
                    MarketingCaptureScenario.TransferDesktopCompleted,
                    -> MarketingMediaTransferScenario(scenario)
                    MarketingCaptureScenario.MusicLibraryAlbumTracksMobile,
                    MarketingCaptureScenario.MusicLibraryPlaybackErrorDesktop,
                    -> MarketingMusicWorkspaceScenario(scenario, assets)
                    MarketingCaptureScenario.DeckBoardDesktop,
                    MarketingCaptureScenario.DeckBoardMobile,
                    MarketingCaptureScenario.HomepagePlanningDesktopDark,
                    MarketingCaptureScenario.HomepagePlanningDesktopLight,
                    -> MarketingDeckBoardScenario(scenario)
                    MarketingCaptureScenario.GuideAndroidGettingStartedHome,
                    MarketingCaptureScenario.GuideAndroidGettingStartedFiles,
                    MarketingCaptureScenario.GuideAndroidGettingStartedCalendar,
                    MarketingCaptureScenario.GuideDesktopGettingStartedHome,
                    MarketingCaptureScenario.GuideDesktopGettingStartedApps,
                    MarketingCaptureScenario.GuideDesktopGettingStartedSettings,
                    MarketingCaptureScenario.GuideAndroidOfflineFilesBrowse,
                    MarketingCaptureScenario.GuideAndroidFolderSyncStatus,
                    MarketingCaptureScenario.GuideLinuxFolderSyncWorkspace,
                    MarketingCaptureScenario.GuideWindowsCloudFilesStorage,
                    MarketingCaptureScenario.GuideWindowsCloudFilesRecovery,
                    MarketingCaptureScenario.GuideAndroidPhotoBackupFolders,
                    MarketingCaptureScenario.GuideAndroidPhotoBackupQueue,
                    MarketingCaptureScenario.GuideAndroidCalendarMonth,
                    MarketingCaptureScenario.GuideAndroidCalendarAgenda,
                    MarketingCaptureScenario.GuideDesktopCalendarMonth,
                    MarketingCaptureScenario.GuideDesktopCalendarSources,
                    MarketingCaptureScenario.GuideDesktopCalendarEdit,
                    MarketingCaptureScenario.GuideDesktopSwitchAppsCatalog,
                    MarketingCaptureScenario.GuideDesktopSwitchAppsSidebar,
                    MarketingCaptureScenario.GuideDesktopSwitchAppsNested,
                    -> error("Guide capture aliases must resolve before rendering.")
                }
            }
        }
    }
}

@Composable
private fun MarketingAppsWorkspaceScenario(
    fixture: MarketingDemoFixture,
    assets: MarketingCaptureAssets,
) {
    RootShell(
        presentation = NextcloudPresentation.Desktop,
        selected = NextcloudDestination.Apps,
        onSelected = {},
        identity = marketingDesktopIdentity(fixture, assets.avatar),
        onOpenApp = {},
    ) {
        NativeAppsWorkspace(
            serverInfo = fixture.serverInfo(),
            error = null,
            lastOpenedAppId = "deck",
            onRetry = {},
            onSettings = {},
            onSearch = {},
            onOpenApp = {},
        )
    }
}

@Composable
private fun MarketingDesktopConversationsScenario(
    fixture: MarketingDemoFixture,
    assets: MarketingCaptureAssets,
) {
    RootShell(
        presentation = NextcloudPresentation.Desktop,
        selected = NextcloudDestination.Apps,
        onSelected = {},
        identity = marketingDesktopIdentity(fixture, assets.avatar),
        activeAppId = "spreed",
        desktopWorkspaceKind = NextcloudDesktopWorkspaceKind.AppWorkspace,
    ) {
        NextcloudDesktopMasterDetail(
            masterWidthDp = 340,
            master = {
                TalkScreen(
                    services = assets.services,
                    session = marketingHomepageSession,
                    onBack = {},
                    onOpenRoom = {},
                )
            },
            detail = {
                ChatScreen(
                    services = assets.services,
                    session = marketingHomepageSession,
                    userId = marketingHomepageTalkUserId,
                    room = marketingHomepageTalkRoom,
                    onBack = {},
                    onOpenAttachment = {},
                )
            },
        )
    }
}

@Composable
private fun MarketingDesktopStartupSettingsScenario(
    fixture: MarketingDemoFixture,
    assets: MarketingCaptureAssets,
) {
    RootShell(
        presentation = NextcloudPresentation.Desktop,
        selected = NextcloudDestination.Settings,
        onSelected = {},
        identity = marketingDesktopIdentity(fixture, assets.avatar),
    ) { DesktopSettingsWorkspace(
            summary = SettingsWorkspaceSummary(
                displayName = fixture.displayName,
                cloudName = fixture.cloudName,
                serverUrl = "https://${fixture.cloudName}",
                serverVersion = "31.0.8",
                installedApps = fixture.apps.count { it.id != "dashboard" },
                syncLabel = "4 active syncs",
                storageLabel = "34.2 GB of 100 GB used",
            ),
            visibleSections = visibleSettingsSections(true, false, true),
            selectedSection = SettingsWorkspaceSection.DesktopApp, onSectionSelected = {},
        ) { section ->
            when (section) {
                SettingsWorkspaceSection.DesktopApp -> {
                    SettingsDesktopAppSectionContent(
                        preferences = settingsDesktopPreferences(true, true),
                        onPreferenceChanged = { _, _ -> },
                    )
                }
                else -> SettingsActionCard(
                    title = section.title,
                    description = section.description,
                    icon = section.icon,
                    onClick = {},
                )
            }
        }
    }
}
