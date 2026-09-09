package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.obiente.nextcloudnative.nativeui.runtime.LocalNativeInlineEditorNavigation
import dev.obiente.nextcloudnative.nativeui.runtime.NativeInlineEditorNavigation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CalendarInteractionRenderTest {
    @Test
    fun shellNavigationKeepsCalendarDraftUntilExplicitDiscard() {
        val navigation = NativeInlineEditorNavigation()
        val editing = mutableStateOf(true)
        var rootNavigations = 0
        var dismissals = 0
        nativeSceneTest(1280, 900, content = {
            CompositionLocalProvider(LocalNativeInlineEditorNavigation provides navigation) {
                Column(Modifier.fillMaxSize()) {
                    Button(onClick = { navigation.navigate { rootNavigations++ } }) { Text("Home") }
                    Box(Modifier.weight(1f)) {
                        if (editing.value) EventEditorDialog(
                            event = event(), initialDate = "20260804", calendars = listOf(calendar()),
                            onDismiss = { dismissals++; editing.value = false }, error = null,
                            onSave = { _, _ -> }, inPlace = true,
                        )
                    }
                }
            }
        }) {
            assertTrue(navigation.active)
            replaceText("Example planning", "Unsaved shell navigation draft")
            click("Home")
            assertTrue(has("Discard unsaved event changes?"))
            assertEquals(0, rootNavigations)
            click("Keep editing")
            assertEquals(0, dismissals)
            assertTrue(nodes().any {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Unsaved shell navigation draft"
            })
            click("Home")
            click("Discard")
            assertEquals(1, dismissals)
            assertEquals(1, rootNavigations)
            assertFalse(navigation.active)
            assertFalse(editing.value)
        }
    }

    @Test
    fun pendingCalendarSaveBlocksShellNavigationAndDoesNotReplayItAfterCompletion() {
        val navigation = NativeInlineEditorNavigation()
        val busy = mutableStateOf(false)
        var rootNavigations = 0
        var saves = 0
        var dismissals = 0
        nativeSceneTest(1280, 900, content = {
            CompositionLocalProvider(LocalNativeInlineEditorNavigation provides navigation) {
                Column(Modifier.fillMaxSize()) {
                    Row {
                        Button(onClick = { navigation.navigate { rootNavigations++ } }) { Text("Home") }
                        Button(onClick = { navigation.navigate { rootNavigations++ } }) { Text("Settings") }
                    }
                    Box(Modifier.weight(1f)) {
                        EventEditorDialog(
                            event = event(), initialDate = "20260804", calendars = listOf(calendar()),
                            onDismiss = { dismissals++ }, error = null, mutationInProgress = busy.value,
                            onSave = { _, _ -> saves++; busy.value = true }, inPlace = true,
                        )
                    }
                }
            }
        }) {
            replaceText("Example planning", "Saving calendar draft")
            click("Save")
            assertEquals(1, saves)
            for (destination in listOf("Home", "Settings")) {
                click(destination)
                assertTrue(has("Save not finished"))
                assertFalse(has("Discard"))
                click("Stay here")
            }
            assertEquals(0, rootNavigations)
            assertEquals(0, dismissals)
            busy.value = false
            settle()
            assertEquals(0, rootNavigations)
            assertEquals(1, saves)
            assertTrue(navigation.active)
        }
    }

    @Test
    @OptIn(InternalComposeUiApi::class)
    fun escapeFromAFocusedInPlaceFieldOffersDiscardInsteadOfLosingTheDraft() {
        var dismissed = 0
        nativeSceneTest(1280, 900, content = {
            EventEditorDialog(
                event = event(), initialDate = "20260804", calendars = listOf(calendar()),
                onDismiss = { dismissed++ }, error = null, onSave = { _, _ -> },
                inPlace = true, embedded = true,
            )
        }) {
            val titleField = assertNotNull(nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Example planning"
            })
            click(titleField.boundsInRoot.center)
            replaceText("Example planning", "Unsaved keyboard edit")
            scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyUp))
            settle()
            assertTrue(has("Discard unsaved event changes?"))
            assertEquals(0, dismissed)
            click("Keep editing")
            assertTrue(nodes().any {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Unsaved keyboard edit"
            })
        }
    }

    @Test
    fun existingEventUsesTheWorkspaceAndKeepsDirtyBackAndSaveGuards() {
        for (width in listOf(390, 1280)) {
            var dismissed = 0
            var saved: EventDraft? = null
            nativeSceneTest(width, 900, content = {
                EventEditorDialog(
                    event = event(), initialDate = "20260804", calendars = listOf(calendar()),
                    onDismiss = { dismissed++ }, error = null, onSave = { draft, _ -> saved = draft },
                    inPlace = true, embedded = true,
                )
            }) {
                assertTrue(has("Back to calendar"))
                assertFalse(has("Close dialog"), "Editing an existing event must not open a modal")
                val field = assertNotNull(nodes().firstOrNull {
                    it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Example planning"
                })
                assertTrue(field.boundsInRoot.width <= 688f)
                click("Save")
                assertEquals(null, saved)
                replaceText("Example planning", "Changed in workspace")
                click("Back to calendar")
                assertTrue(has("Discard unsaved event changes?"))
                assertEquals(0, dismissed)
                click("Keep editing")
                click("Save")
                assertEquals("Changed in workspace", assertNotNull(saved).title)
                capture("calendar-in-place-editor-$width")
                click("Back to calendar")
                click("Discard")
                assertEquals(1, dismissed)
            }
        }
    }

    @Test
    fun phoneEventDetailsAndEditingStayInTheSamePage() {
        val editing = mutableStateOf(false)
        var leftEvent = 0
        nativeSceneTest(390, 844, content = {
            if (editing.value) EventEditorDialog(
                event = event(), initialDate = "20260804", calendars = listOf(calendar()),
                onDismiss = { editing.value = false }, error = null, onSave = { _, _ -> },
                inPlace = true, backLabel = "Back to event", embedded = true,
            ) else EventDetailDialog(
                event = event(), canEdit = true, onDismiss = { leftEvent++ },
                onEdit = { editing.value = true }, onDelete = {}, error = null, inPlace = true,
            )
        }) {
            assertTrue(has("Back to calendar"))
            assertFalse(has("Close dialog"))
            click("Edit")
            assertTrue(has("Back to event"))
            assertTrue(has("Choose event date"))
            assertFalse(has("Close dialog"))
            click("Back to event")
            assertTrue(has("Edit"))
            assertEquals(0, leftEvent)
            click("Back to calendar")
            assertEquals(1, leftEvent)
        }
    }

    @Test
    fun inPlaceEditorKeepsBackAndSaveVisibleAtLargePhoneFontSize() {
        nativeSceneTest(320, 900, fontScale = 1.5f, content = {
            EventEditorDialog(
                event = event(), initialDate = "20260804", calendars = listOf(calendar()),
                onDismiss = {}, error = null, onSave = { _, _ -> }, inPlace = true,
            )
        }) {
            listOf("Back to calendar", "Cancel", "Save").forEach { label ->
                val bounds = assertNotNull(node(label)).boundsInRoot
                assertTrue(bounds.left >= 0f && bounds.right <= 320f)
                assertTrue(bounds.top >= 0f && bounds.bottom <= 900f)
            }
            capture("calendar-in-place-small-phone-large-font")
        }
    }

    @Test
    fun largeFontSmallPhoneKeepsCloseAndSaveWithinTheViewport() {
        nativeSceneTest(320, 900, fontScale = 1.5f, content = {
            EventEditorDialog(
                event = event(), initialDate = "20260804", calendars = listOf(calendar()),
                onDismiss = {}, error = null, onSave = { _, _ -> }, embedded = true,
            )
        }) {
            listOf("Close dialog", "Cancel", "Save").forEach { label ->
                val bounds = assertNotNull(node(label)).boundsInRoot
                assertTrue(bounds.left >= 0f && bounds.right <= 320f, "$label must not clip horizontally")
                assertTrue(bounds.top >= 0f && bounds.bottom <= 900f, "$label must remain visible")
                assertTrue(bounds.width > 0f && bounds.height > 0f)
            }
            capture("calendar-small-phone-large-font")
        }
    }

    @Test
    fun sharedEditorIsBoundedAndDirtyCloseKeepsTheDraftUntilConfirmed() {
        for (width in listOf(390, 1280)) {
            var dismissed = 0
            var saved: EventDraft? = null
            nativeSceneTest(width, 900, content = {
                EventEditorDialog(
                    event = event(), initialDate = "20260804", calendars = listOf(calendar()),
                    onDismiss = { dismissed++ }, error = null, onSave = { draft, _ -> saved = draft },
                    embedded = true,
                )
            }) {
                val field = assertNotNull(nodes().firstOrNull {
                    it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Example planning"
                })
                assertTrue(field.boundsInRoot.width <= 560f, "Desktop forms must remain bounded")
                assertTrue(has("Choose event date"), "The real editor must expose its date picker")
                click("Save")
                assertEquals(null, saved, "Unchanged events must not submit a mutation")
                replaceText("Example planning", "Updated planning")
                click("Close dialog")
                assertTrue(has("Discard unsaved event changes?"))
                assertEquals(0, dismissed)
                click("Keep editing")
                assertFalse(has("Discard unsaved event changes?"))
                assertTrue(nodes().any {
                    it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Updated planning"
                })
                click("Save")
                assertEquals("Updated planning", assertNotNull(saved).title)
                assertEquals(0, dismissed)
                capture("calendar-editor-$width")
                click("Close dialog")
                click("Discard")
                assertEquals(1, dismissed)
            }
        }
    }

    @Test
    fun aPendingMutationDisablesCloseRecoveryAndSaveAndClearsDiscardPrompt() {
        val busy = mutableStateOf(false)
        var dismissed = 0
        var recovery = 0
        var saves = 0
        nativeSceneTest(600, 900, content = {
            EventEditorDialog(
                event = event(), initialDate = "20260804", calendars = listOf(calendar()),
                onDismiss = { dismissed++ }, error = null, recoveryAvailable = true,
                onOpenRecovery = { recovery++ }, mutationInProgress = busy.value,
                onSave = { _, _ -> saves++ }, embedded = true, inPlace = true,
            )
        }) {
            replaceText("Example planning", "Changed before pending result")
            click("Back to calendar")
            assertTrue(has("Discard unsaved event changes?"))
            busy.value = true
            settle()
            assertFalse(has("Discard unsaved event changes?"))
            click("Back to calendar")
            click("Recovery options")
            click("Save")
            assertEquals(0, dismissed)
            assertEquals(0, recovery)
            assertEquals(0, saves)
            busy.value = false
            settle()
            click("Recovery options")
            assertEquals(1, recovery)
        }
    }

    @Test
    fun externalNavigationReplacesLocalDiscardPromptAndKeepsItsOwnCallback() {
        val navigation = mutableStateOf<NextcloudPendingNavigationRequest?>(null)
        var dismissed = 0
        var discardedNavigation = 0
        var cancelledNavigation = 0
        nativeSceneTest(600, 900, content = {
            EventEditorDialog(
                event = event(), initialDate = "20260804", calendars = listOf(calendar()),
                onDismiss = { dismissed++ }, error = null, navigationRequest = navigation.value,
                onNavigationDiscardConfirmed = { discardedNavigation++; navigation.value = null },
                onNavigationCancelled = { cancelledNavigation++; navigation.value = null },
                onSave = { _, _ -> }, embedded = true, inPlace = true,
            )
        }) {
            replaceText("Example planning", "Unfinished event")
            click("Back to calendar")
            navigation.value = NextcloudPendingNavigationRequest.Native(
                NextcloudNativeNavigationRequest(1, NextcloudNativeRoute.Home),
            )
            settle()
            assertEquals(1, nodes().count {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                    text.text == "Discard unsaved event changes?"
                } == true
            }, "Only one discard prompt should own the pending navigation")
            click("Keep editing")
            assertEquals(1, cancelledNavigation)
            assertEquals(0, dismissed)
            navigation.value = NextcloudPendingNavigationRequest.Native(
                NextcloudNativeNavigationRequest(2, NextcloudNativeRoute.Home),
            )
            settle()
            click("Discard")
            assertEquals(1, discardedNavigation)
            assertEquals(0, dismissed)
        }
    }

    @Test
    fun desktopPaneControlsReturnSpaceWithoutMutatingTheEventOrItsCalendar() {
        var mutations = 0
        val occurrence = event().copy(recurrenceRule = "FREQ=WEEKLY", isGeneratedOccurrence = true)
        nativeSceneTest(1440, 900, content = {
            DesktopGroupwareCalendarWorkspace(
                month = CalendarMonth(2026, 8), selectedDate = "20260804", view = CalendarWorkspaceView.Month,
                calendars = listOf(calendar()), events = listOf(occurrence), hiddenCalendarHrefs = emptySet(),
                query = "", selectedEvent = occurrence, onPrevious = {}, onNext = {}, onToday = {},
                onViewChanged = {}, onQueryChanged = {}, onCalendarVisibilityChanged = { _, _ -> mutations++ },
                onSelectDate = {}, onSelectEvent = {}, onCreateEvent = { mutations++ }, onRefresh = {},
                onEditEvent = { mutations++ }, onDeleteEvent = { mutations++ },
            )
        }) {
            assertTrue(has("My calendars"))
            assertTrue(has("Edit series"))
            click("Edit series")
            click("Delete series")
            assertEquals(0, mutations, "Generated occurrences must stay read-only")
            val originalDayWidth = assertNotNull(node("Mon")).boundsInRoot.width
            click("Details")
            assertFalse(has("Edit series"))
            click("Calendars")
            assertFalse(has("My calendars"))
            assertTrue(assertNotNull(node("Mon")).boundsInRoot.width > originalDayWidth + 30f)
            assertEquals(0, mutations, "Pane visibility must not alter calendar visibility or event state")
            capture("calendar-desktop-collapsed-panes")
        }
    }

    private fun calendar() = GroupwareCalendar(
        href = "/calendars/synthetic/", displayName = "Example calendar", color = null, writable = true,
    )

    private fun event() = GroupwareCalendarEvent(
        href = "/calendars/synthetic/example.ics", etag = "\"synthetic\"", calendarHref = calendar().href,
        uid = "example", title = "Example planning", start = "20260804T090000Z", end = "20260804T100000Z",
        allDay = false, rawCalendar = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n",
    )
}
