package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SupportRetentionNoticeUiTest {
    @BeforeTest
    @AfterTest
    fun clearPendingRequest() {
        SupportNewReportRequests.consume()
    }

    @Test
    fun homeNoticeExposesAHeadingAndTwoFocusableActions() {
        val store = InMemorySupportRetentionNoticeStore(SupportRetentionNoticeState.Pending.storedValue)
        nativeSceneTest(width = 1280, height = 800, content = { HomeWithNotice(store) {} }) {
            val title = assertNotNull(node(SUPPORT_RETENTION_NOTICE_TITLE))
            assertTrue(title.config.contains(SemanticsProperties.Heading))
            assertTrue(has(SUPPORT_RETENTION_NOTICE_BODY))
            listOf(SUPPORT_RETENTION_NOTICE_SEND, SUPPORT_RETENTION_NOTICE_DISMISS).forEach { label ->
                val action = assertNotNull(node(label))
                assertNotNull(action.config.getOrNull(SemanticsActions.OnClick)?.action)
                assertNotNull(action.config.getOrNull(SemanticsActions.RequestFocus)?.action)
            }
            val send = assertNotNull(node(SUPPORT_RETENTION_NOTICE_SEND)).boundsInRoot
            val dismiss = assertNotNull(node(SUPPORT_RETENTION_NOTICE_DISMISS)).boundsInRoot
            assertTrue(title.boundsInRoot.top < send.top)
            assertTrue(send.left < dismiss.left, "The primary action comes first in focus order.")
        }
    }

    @Test
    fun sendANewReportOpensTheReportFlowAndRemovesTheNotice() {
        val store = InMemorySupportRetentionNoticeStore(SupportRetentionNoticeState.Pending.storedValue)
        var settingsOpened = 0
        nativeSceneTest(width = 1280, height = 800, content = { HomeWithNotice(store) { settingsOpened += 1 } }) {
            click(SUPPORT_RETENTION_NOTICE_SEND)

            assertEquals(1, settingsOpened)
            assertFalse(has(SUPPORT_RETENTION_NOTICE_TITLE))
            assertEquals(SupportRetentionNoticeState.Dismissed.storedValue, store.value)
            assertTrue(SupportNewReportRequests.consume())
        }
    }

    @Test
    fun dismissRemovesTheNoticeWithoutOpeningTheReportFlow() {
        val store = InMemorySupportRetentionNoticeStore(SupportRetentionNoticeState.Pending.storedValue)
        var settingsOpened = 0
        nativeSceneTest(width = 1280, height = 800, content = { HomeWithNotice(store) { settingsOpened += 1 } }) {
            click(SUPPORT_RETENTION_NOTICE_DISMISS)

            assertEquals(0, settingsOpened)
            assertFalse(has(SUPPORT_RETENTION_NOTICE_TITLE))
            assertEquals(SupportRetentionNoticeState.Dismissed.storedValue, store.value)
            assertFalse(SupportNewReportRequests.consume())
        }
    }

    @Test
    fun dismissedNoticeStaysHiddenAfterRestart() {
        val store = InMemorySupportRetentionNoticeStore(SupportRetentionNoticeState.Dismissed.storedValue)
        nativeSceneTest(width = 1280, height = 800, content = { HomeWithNotice(store) {} }) {
            assertTrue(has("Home"))
            assertFalse(has(SUPPORT_RETENTION_NOTICE_TITLE))
        }
    }

    @Test
    fun homePresentationWithoutTheHostSlotNeverShowsTheNotice() {
        nativeSceneTest(width = 1280, height = 800, content = { HomePresentation(supportRetentionNotice = null) }) {
            assertTrue(has("Home"))
            assertFalse(has(SUPPORT_RETENTION_NOTICE_TITLE))
        }
    }
}

@Composable
private fun HomeWithNotice(store: SupportRetentionNoticeStore, onOpenSettings: () -> Unit) {
    val controller = remember(store) { SupportRetentionNoticeController(store) }
    HomePresentation(rememberHomeSupportRetentionNotice(onOpenSettings, controller))
}

@Composable
private fun HomePresentation(supportRetentionNotice: (@Composable () -> Unit)?) {
    NativeDashboardPresentation(
        state = DashboardSurfaceState.Loading,
        installedApps = emptyList(),
        workspaceLayout = defaultHomeWorkspaceLayout(HomeWorkspaceScope("a".repeat(64), HomeFormFactor.Desktop)),
        onWorkspaceLayoutChanged = { true },
        onOpenApp = {},
        onOpenStatus = null,
        onOpenLink = {},
        onBack = null,
        onRefresh = {},
        supportRetentionNotice = supportRetentionNotice,
    )
}
