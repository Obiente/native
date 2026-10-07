package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.app.design.NextcloudPresentation
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopShell
import dev.obiente.nextcloudnative.app.design.NextcloudDestination
import dev.obiente.nextcloudnative.nativeui.runtime.GenericNativeAppScreen
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutionResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutor
import dev.obiente.nextcloudnative.nativeui.runtime.NativeDatasetContext
import dev.obiente.nextcloudnative.nativeui.runtime.NativeScreenState
import dev.obiente.nextcloudnative.nativeui.runtime.preferredNativeMailComposeAction

@Composable
internal fun MarketingMailWorkspaceScenario(scenario: MarketingCaptureScenario) {
    require(
        scenario in setOf(
            MarketingCaptureScenario.MailWorkspaceDesktop,
            MarketingCaptureScenario.MailWorkspaceMobile,
            MarketingCaptureScenario.MailMessageBodyMobile,
            MarketingCaptureScenario.MailWorkspaceLoadingMobile,
            MarketingCaptureScenario.MailWorkspaceEmptyMobile,
            MarketingCaptureScenario.MailWorkspaceErrorDesktop,
        ),
    ) {
        "${scenario.id} is not a Mail workspace capture."
    }
    val desktop = scenario.presentation == NextcloudPresentation.Desktop
    val messageDetail = desktop || scenario == MarketingCaptureScenario.MailMessageBodyMobile
    val composeAction = marketingMailDescriptor.preferredNativeMailComposeAction(marketingMailSchema)
    val currentView = if (messageDetail) marketingMailBodyView else marketingMailMessageView
    val currentState = when (scenario) {
        MarketingCaptureScenario.MailWorkspaceLoadingMobile -> NativeScreenState.Loading
        MarketingCaptureScenario.MailWorkspaceEmptyMobile -> NativeScreenState.Ready(emptyList())
        MarketingCaptureScenario.MailWorkspaceErrorDesktop -> NativeScreenState.Error(
            message = "The server did not return the selected message body.",
            retry = {},
            retryLabel = "Try again",
        )
        else -> NativeScreenState.Ready(
            if (messageDetail) listOf(marketingMailBodyRecord) else marketingMailMessages,
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
            selectedRecordId = if (messageDetail) marketingMailSelectedMessage.id else null,
            selectedRecordResourceId = if (messageDetail) "messages" else null,
            showSelectedRecordDetail = messageDetail,
            onSelectRecord = {},
            datasetContext = NativeDatasetContext(
                parentResourceId = if (messageDetail) "messages" else "mailboxes",
                parentRecord = if (messageDetail) marketingMailSelectedMessage else marketingMailInbox,
                relatedRecords = mapOf(
                    "accounts" to listOf(marketingMailAccount),
                    "mailboxes" to marketingMailboxes,
                    // The desktop fixture intentionally renders a selected body after the
                    // envelope collection has been evicted. This keeps visual QA aligned with
                    // direct/deep-linked detail behavior instead of relying on a warm list cache.
                    "messages" to if (messageDetail) emptyList() else marketingMailMessages,
                    "mailboxStats" to listOf(marketingMailInboxStats),
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
internal fun MarketingDeckBoardScenario(scenario: MarketingCaptureScenario? = null) {
    val content: @Composable () -> Unit = {
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
    if (scenario?.presentation == NextcloudPresentation.Desktop) {
        NextcloudDesktopShell(
            selected = NextcloudDestination.Apps, onSelected = {},
            identity = marketingDesktopIdentity(), activeAppId = "deck",
            workspaceKind = dev.obiente.nextcloudnative.app.design.NextcloudDesktopWorkspaceKind.AppWorkspace,
            content = content,
        )
    } else content()
}
