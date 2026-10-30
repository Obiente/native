package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudWorkspaceCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetailPresentationInteractionTest {
    @Test
    fun phoneDeckCardOpensInASheetOverTheBoardAndCloseDismissesIt() {
        val selected = mutableStateOf<Long?>(null)
        var dismissals = 0
        nativeSceneTest(390, 844, content = {
            SyntheticDeckBoard(selected.value, onSelect = { selected.value = it }, onDismiss = { dismissals++; selected.value = null })
        }) {
            assertFalse(has("Close card"))
            click("Draft the agenda")
            settleUntil { has("Close card") && has("Agenda body") }
            assertTrue(has("Agenda body"), "The sheet shows the card details")
            assertTrue(has("Write the notes"), "The board stays behind the sheet")
            // Under load the sheet may still be sliding in; tap Close where it rests, not mid-flight.
            var frames = 0
            do {
                val before = node("Close card")?.boundsInRoot
                settle()
            } while (node("Close card")?.boundsInRoot != before && ++frames < 20)
            click("Close card")
            settleUntil { !has("Close card") && dismissals > 0 }
            assertEquals(1, dismissals)
            assertEquals(null, selected.value)
        }
    }

    @OptIn(InternalComposeUiApi::class)
    @Test
    fun escapeDismissesThePhoneDeckSheet() {
        val selected = mutableStateOf<Long?>(DraftCardId)
        var dismissals = 0
        nativeSceneTest(390, 844, content = {
            SyntheticDeckBoard(selected.value, onSelect = { selected.value = it }, onDismiss = { dismissals++; selected.value = null })
        }) {
            settleUntil { has("Close card") }
            scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyUp))
            settleUntil { !has("Close card") }
            assertEquals(1, dismissals, "Escape, like Back, dismisses through the host callback once")
        }
    }

    @Test
    fun restoredDeckSelectionReopensTheSheetOnTheSameCard() {
        nativeSceneTest(390, 844, content = {
            SyntheticDeckBoard(DraftCardId, onSelect = {}, onDismiss = {})
        }) {
            settleUntil { has("Close card") && has("Agenda body") }
            assertTrue(has("Agenda body"))
        }
    }

    @Test
    fun wideDeckInspectorSlidesBetweenCardsAndLeavesWhenSelectionClears() {
        val selected = mutableStateOf<Long?>(DraftCardId)
        nativeSceneTest(1280, 800, content = {
            Desktop { SyntheticDeckBoard(selected.value, onSelect = { selected.value = it }, onDismiss = { selected.value = null }) }
        }) {
            settleUntil { has("Agenda body") }
            assertTrue(has("Agenda body"))
            assertFalse(has("Close card"), "Wide boards use the side inspector, not a sheet")
            selected.value = NotesCardId
            settleUntil { has("Notes body") }
            assertFalse(has("Agenda body"))
            selected.value = null
            settleUntil { !has("Notes body") }
        }
    }

    @Test
    fun phoneSyncOpensASheetOverTheListAndCloseReturnsWithoutRunningWork() {
        var runRequests = 0
        nativeSceneTest(390, 844, content = {
            SyncWorkspace(onRun = { runRequests++ })
        }) {
            click("Example documents")
            settleUntil { has("Close sync details") }
            assertTrue(has("Current work"))
            assertTrue(has("Add sync"), "The list stays behind the sheet")
            click("Close sync details")
            settleUntil { !has("Current work") }
            assertEquals(0, runRequests)
        }
    }

    @Test
    fun phoneSyncSheetSurvivesRecreationThroughSavedSelection() {
        val composed = mutableStateOf(true)
        nativeSceneTest(390, 844, content = {
            val holder = rememberSaveableStateHolder()
            if (composed.value) holder.SaveableStateProvider("sync") { SyncWorkspace(onRun = {}) }
        }) {
            click("Example documents")
            settleUntil { has("Current work") }
            composed.value = false
            settleUntil { !has("Current work") }
            composed.value = true
            settleUntil { has("Current work") }
            assertTrue(has("Close sync details"), "Restored selection reopens the same sync's sheet")
        }
    }

    @Test
    fun narrowDesktopSyncKeepsTheFullPageWithAllSyncsBack() {
        nativeSceneTest(700, 800, content = {
            Desktop { SyncWorkspace(onRun = {}) }
        }) {
            click("Example documents")
            settleUntil { has("All syncs") }
            assertFalse(has("Add sync"))
            assertFalse(has("Close sync details"))
            click("All syncs")
            settleUntil { has("Add sync") }
        }
    }

    @Composable
    private fun Desktop(content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalNextcloudWorkspaceCapabilities provides NextcloudWorkspaceCapabilities(
                isDesktop = true,
                usesDenseControls = true,
                supportsAuxiliaryPane = true,
            ),
            content = content,
        )
    }

    @Composable
    private fun SyntheticDeckBoard(selectedCardId: Long?, onSelect: (Long) -> Unit, onDismiss: () -> Unit) {
        NativeDeckBoardSurface(
            state = DeckWorkspaceState.Board(board = board(), stacks = stacks(), selectedCardId = selectedCardId),
            onExit = {},
            onSelectBoard = {},
            onBackToBoards = {},
            onOpenCard = {},
            onSelectCard = { onSelect(it.id) },
            onDismissCard = onDismiss,
            onRetry = {},
        )
    }

    @Composable
    private fun SyncWorkspace(onRun: (FileSyncPairSummary) -> Unit) {
        FileSyncWorkspace(
            snapshot = FileSyncCenterSnapshot(FileSyncCenterSupport.Available, listOf(pair())),
            loading = false,
            busyPairId = null,
            onAdd = {},
            onRun = onRun,
            onRemove = {},
            onResolve = { _, _, _ -> },
            fillAvailableHeight = true,
        )
    }

    private fun board() = DeckBoard(
        id = 1, title = "Synthetic board", color = null, archived = false, owner = null, labels = emptyList(),
        permissions = DeckPermissions(canRead = true, canEdit = true, canManage = false, canShare = false),
        shared = false, lastModified = null, etag = null,
    )

    private fun stacks() = listOf(
        DeckStack(
            id = 10, boardId = 1, title = "Next", order = 0, doneColumn = false,
            cards = listOf(card(DraftCardId, "Draft the agenda", "Agenda body"), card(NotesCardId, "Write the notes", "Notes body")),
            lastModified = null, etag = null,
        ),
    )

    private fun card(id: Long, title: String, description: String) = DeckCard(
        id = id, boardId = 1, stackId = 10, title = title, descriptionMarkdown = description, ownerId = null,
        color = null, order = id, dueAt = null, startAt = null, completedAt = null, archived = false,
        overdue = false, labels = emptyList(), assignees = emptyList(), attachmentCount = 0,
        unreadCommentCount = 0, etag = null,
    )

    private fun pair() = FileSyncPairSummary(
        id = "synthetic-pair", localDisplayName = "Example documents", localRootPath = "Documents/Example",
        remoteRootPath = "Example", configuration = FileSyncConfiguration(deviceLabel = "Test device"),
        readyCount = 2, runningCount = 0, conflicts = emptyList(), failedCount = 0,
        skippedCount = 0, lastScanEpochMillis = 0,
    )

    private companion object {
        const val DraftCardId = 11L
        const val NotesCardId = 12L
    }
}
