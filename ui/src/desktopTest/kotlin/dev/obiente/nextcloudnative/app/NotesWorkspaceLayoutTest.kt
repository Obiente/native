package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudWorkspaceCapabilities
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Notes list and editor at phone, tablet, and desktop widths over a synthetic Notes API. */
class NotesWorkspaceLayoutTest {
    @Test
    fun phoneKeepsTheFullPageEditorRoute() {
        val fixture = Fixture()
        val opened = mutableListOf<Long>()
        nativeSceneTest(390, 844, content = { Notes(fixture, onOpenNote = { opened += it.id }) }) {
            settleUntil(TIMEOUT) { has("Groceries") }
            click("Groceries")
            settle()
            assertEquals(listOf(GroceriesId), opened, "Phones open the note as its own page")
            assertFalse(has("Close note"))
            assertFalse(has("Select a note"))
        }
    }

    @Test
    fun desktopOpensNotesBesideTheListAndSwitchesBetweenThem() {
        val fixture = Fixture()
        val opened = mutableListOf<Long>()
        nativeSceneTest(1280, 800, content = { Desktop { Notes(fixture, onOpenNote = { opened += it.id }) } }) {
            settleUntil(TIMEOUT) { has("Groceries") }
            assertTrue(has("Select a note"), "An empty editor pane explains what to do")
            click("Groceries")
            settleUntil(TIMEOUT) { hasFieldText("Milk and bread") }
            assertTrue(hasFieldText("Milk and bread"), "The note content loads into the editor")
            assertTrue(has("Close note"), "The editor opens beside the list")
            assertTrue(has("Meeting notes"), "The list stays visible")
            click("Meeting notes")
            settleUntil(TIMEOUT) { hasFieldText("Agenda items") }
            assertTrue(hasFieldText("Agenda items"), "The chosen note opens")
            assertFalse(hasFieldText("Milk and bread"))
            click("Close note")
            settleUntil { has("Select a note") }
            assertTrue(opened.isEmpty(), "Desktop never leaves the workspace for the full-page editor")
        }
    }

    @Test
    fun choosingAnotherNoteAsksBeforeDiscardingADraft() {
        val fixture = Fixture()
        nativeSceneTest(1280, 800, content = { Desktop { Notes(fixture) } }) {
            settleUntil(TIMEOUT) { has("Groceries") }
            click("Groceries")
            settleUntil(TIMEOUT) { hasFieldText("Milk and bread") }
            assertTrue(hasFieldText("Milk and bread"), "The note content loads into the editor")
            replaceText("Milk and bread", "Milk, bread, and eggs")
            settleUntil { has("Unsaved changes") }
            assertTrue(has("Unsaved changes"))
            click("Meeting notes")
            settleUntil { has("Discard unsaved changes?") }
            click("Keep editing")
            settleUntil { !has("Discard unsaved changes?") }
            assertTrue(hasFieldText("Milk, bread, and eggs"), "Keeping the draft leaves the same note open")
            click("Meeting notes")
            settleUntil { has("Discard unsaved changes?") }
            click("Discard")
            settleUntil(TIMEOUT) { hasFieldText("Agenda items") }
            assertTrue(hasFieldText("Agenda items"), "The chosen note opens")
            assertEquals(0, fixture.updates, "Discarding never writes the draft")
        }
    }

    @Test
    fun newNoteOpensStraightInTheEditor() {
        val fixture = Fixture()
        nativeSceneTest(1280, 800, content = { Desktop { Notes(fixture) } }) {
            settleUntil(TIMEOUT) { has("Groceries") }
            click("Create note")
            settleUntil(TIMEOUT) { fixture.created.isNotEmpty() && has("Close note") }
            assertEquals(listOf(NEW_NOTE_TITLE to ""), fixture.created, "One note is created in the current folder")
            assertTrue(has("Markdown"), "The new note opens in the editor without a form first")
        }
    }

    @Test
    fun narrowingTheWindowKeepsTheOpenNoteAndItsDraft() {
        val fixture = Fixture()
        val width = mutableStateOf(1280)
        nativeSceneTest(1280, 800, content = {
            Desktop { Box(Modifier.width(width.value.dp).fillMaxHeight()) { Notes(fixture) } }
        }) {
            settleUntil(TIMEOUT) { has("Groceries") }
            click("Groceries")
            settleUntil(TIMEOUT) { hasFieldText("Milk and bread") }
            assertTrue(hasFieldText("Milk and bread"), "The note content loads into the editor")
            replaceText("Milk and bread", "Milk and oat bread")
            width.value = 700
            settleUntil { has("Back to notes") }
            assertTrue(hasFieldText("Milk and oat bread"), "The draft survives the narrower layout")
            assertFalse(has("Meeting notes"), "Narrow windows show the editor alone")
            width.value = 1280
            settleUntil { has("Close note") }
            assertTrue(hasFieldText("Milk and oat bread"))
            assertTrue(has("Meeting notes"), "The list returns beside the same note")
        }
    }

    @Test
    fun tabletOpensNotesInPlaceAndRecreationRestoresTheOpenNote() {
        val fixture = Fixture()
        val composed = mutableStateOf(true)
        nativeSceneTest(1000, 800, content = {
            val holder = rememberSaveableStateHolder()
            if (composed.value) holder.SaveableStateProvider("notes") { Notes(fixture) }
        }) {
            settleUntil(TIMEOUT) { has("Groceries") }
            click("Groceries")
            settleUntil(TIMEOUT) { hasFieldText("Milk and bread") && has("Close note") }
            composed.value = false
            settleUntil { !has("Close note") }
            composed.value = true
            settleUntil(TIMEOUT) { has("Close note") && hasFieldText("Milk and bread") }
            assertTrue(has("Meeting notes"))
        }
    }

    @Composable
    private fun Notes(fixture: Fixture, onOpenNote: (NextcloudNote) -> Unit = {}) {
        NextcloudNotesScreen(fixture.services, fixture.session, onBack = {}, onOpenNote = onOpenNote)
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

    private class Fixture {
        // Each test uses its own account so the process-wide Notes cache never leaks between tests.
        val session = NextcloudSession("https://notes-layout-${nextAccount++}.invalid", "alice", "synthetic")
        val created = mutableListOf<Pair<String, String>>()
        var updates = 0
        private val notes = mutableListOf(
            note(GroceriesId, "Groceries", "Milk and bread"),
            note(MeetingId, "Meeting notes", "Agenda items"),
        )

        val services = Proxy.newProxyInstance(
            NextcloudPlatformServices::class.java.classLoader,
            arrayOf(NextcloudPlatformServices::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "SyntheticNotesLayoutServices"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.singleOrNull()
                else -> {
                    val operation: suspend () -> Any? = {
                        when (method.name) {
                            "loadDurableMutationRecovery" -> null
                            "listNotesConditionally" -> NextcloudConditionalRead.Modified(
                                notes.map { it.copy(content = null) },
                                responseEtag = null,
                            )
                            "loadNoteConditionally" -> NextcloudConditionalRead.Modified(
                                notes.first { it.id == args!![1] as Long },
                                responseEtag = null,
                            )
                            "createNote" -> {
                                created += (args!![1] as String) to (args[3] as String)
                                note(NewNoteId, args[1] as String, args[2] as String).also(notes::add)
                            }
                            "updateNote" -> {
                                updates++
                                error("Synthetic notes are read-only in layout tests.")
                            }
                            else -> error("Unexpected synthetic notes operation: ${method.name}")
                        }
                    }
                    @Suppress("UNCHECKED_CAST")
                    operation.startCoroutineUninterceptedOrReturn(args!!.last() as Continuation<Any?>)
                }
            }
        } as NextcloudPlatformServices

        private fun note(id: Long, title: String, content: String) = NextcloudNote(
            id = id, title = title, modified = 0L, category = "", favorite = false, readOnly = false,
            content = content, etag = "\"etag-$id\"",
        )
    }

    private companion object {
        const val TIMEOUT = 5_000L
        const val GroceriesId = 11L
        const val MeetingId = 12L
        const val NewNoteId = 13L
        var nextAccount = 0
    }
}
