package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.obiente.nextcloudnative.app.FakeTalkServer.Companion.SELF
import dev.obiente.nextcloudnative.app.FakeTalkServer.Companion.TOKEN
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudWorkspaceCapabilities
import java.lang.reflect.Proxy
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TalkWorkspaceInteractionTest {
    private val session = NextcloudSession("https://cloud.example.test", SELF, "synthetic-app-password")
    private val rooms = listOf(
        TalkRoom(TOKEN, "Synthetic team", "Hello", 0),
        TalkRoom("synthroom2", "Second synthetic room", null, 2),
        TalkRoom("synthroom3", "Third synthetic room", null, 0),
    )
    private val desktop = NextcloudWorkspaceCapabilities(isDesktop = true, usesDenseControls = true, supportsAuxiliaryPane = true)
    private val phone = NextcloudWorkspaceCapabilities(isDesktop = false, usesDenseControls = false, supportsAuxiliaryPane = false)

    private fun server(features: List<String> = FakeTalkServer.ALL_FEATURES) =
        FakeTalkServer(features = features, nowSeconds = Clock.System.now().epochSeconds).apply {
            post("alice", "Hello from a synthetic account", actorDisplayName = "Alice")
            post(SELF, "My synthetic note", actorDisplayName = "Self")
        }

    private val services: NextcloudPlatformServices = Proxy.newProxyInstance(
        NextcloudPlatformServices::class.java.classLoader,
        arrayOf(NextcloudPlatformServices::class.java),
    ) { proxy, method, arguments ->
        when (method.name) {
            "toString" -> "SyntheticTalkServices"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments?.singleOrNull()
            "listTalkRooms" -> rooms
            // No draft storage: an unresolved send lives only as long as the open conversation.
            "textEditorDraftStore" -> null
            else -> error("Unexpected synthetic Talk operation: ${method.name}")
        }
    } as NextcloudPlatformServices

    private fun NativeSceneTestDriver.actionsOf(text: String): SemanticsNode = assertNotNull(
        nodes().firstOrNull { node ->
            node.config.getOrNull(SemanticsActions.CustomActions) != null &&
                node.findText(text)
        },
        "No message with actions shows '$text'",
    )

    private fun SemanticsNode.findText(text: String): Boolean =
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true || children.any { it.findText(text) }

    private fun SemanticsNode.actionLabels(): List<String> =
        config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    private suspend fun NativeSceneTestDriver.invoke(node: SemanticsNode, label: String) {
        assertTrue(node.config[SemanticsActions.CustomActions].first { it.label == label }.action())
        settle()
    }

    @Test
    fun `phone shows one pane and replies through the message actions`() {
        val server = server()
        val selected = mutableStateOf<TalkRoom?>(rooms.first())
        nativeSceneTest(390, 844, content = {
            CompositionLocalProvider(LocalNextcloudWorkspaceCapabilities provides phone) {
                TalkWorkspaceScreen(
                    services, session, SELF, selected.value,
                    onSelectRoom = { selected.value = it }, onCloseRoom = { selected.value = null }, onExit = {},
                    onOpenAttachment = {}, execute = server::execute,
                )
            }
        }) {
            settleUntil { has("Hello from a synthetic account") }
            assertFalse(has("Second synthetic room"), "A compact window shows only the conversation.")
            assertTrue(has("Back"))
            val incoming = actionsOf("Hello from a synthetic account")
            assertEquals(listOf("Reply", "React"), incoming.actionLabels())
            assertEquals(listOf("Reply", "React", "Edit", "Delete"), actionsOf("My synthetic note").actionLabels())

            invoke(incoming, "Reply")
            assertTrue(has("Cancel reply"))
            replaceText("", "A synthetic reply")
            click("Send message")
            settleUntil { has("A synthetic reply") && !has("Cancel reply") }
            val write = server.writes().single()
            assertEquals("1", write.formFields.toMap()["replyTo"])
            assertTrue(nodes().none { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "A synthetic reply" })

            click("Back")
            assertEquals(null, selected.value)
            settleUntil { has("Second synthetic room") }
        }
    }

    @Test
    fun `phone reacts edits and deletes with confirmation`() {
        val server = server()
        nativeSceneTest(390, 844, content = {
            CompositionLocalProvider(LocalNextcloudWorkspaceCapabilities provides phone) {
                ChatScreen(services, session, SELF, rooms.first(), onBack = {}, onOpenAttachment = {}, execute = server::execute)
            }
        }) {
            settleUntil { has("My synthetic note") }
            invoke(actionsOf("Hello from a synthetic account"), "React")
            click("React with 👍")
            settleUntil { has("Remove your 👍 reaction, 1 in total") }
            click("Remove your 👍 reaction, 1 in total")
            settleUntil { !has("Remove your 👍 reaction, 1 in total") }

            invoke(actionsOf("My synthetic note"), "Edit")
            assertTrue(has("Cancel editing"))
            replaceText("My synthetic note", "My edited note")
            click("Save edit")
            settleUntil { has("My edited note") && !has("Cancel editing") }

            invoke(actionsOf("My edited note"), "Delete")
            assertTrue(has("Delete this message?"))
            click("Cancel")
            assertFalse(server.writes().any { it.isMessageDelete() })
            invoke(actionsOf("My edited note"), "Delete")
            click("Delete")
            settleUntil { !has("My edited note") }
            assertTrue(server.writes().any { it.isMessageDelete() })
        }
    }

    @Test
    fun `a server without optional features offers no message actions`() {
        val server = server(features = emptyList())
        nativeSceneTest(390, 844, content = {
            CompositionLocalProvider(LocalNextcloudWorkspaceCapabilities provides phone) {
                ChatScreen(services, session, SELF, rooms.first(), onBack = {}, onOpenAttachment = {}, execute = server::execute)
            }
        }) {
            settleUntil { has("My synthetic note") }
            assertTrue(nodes().none { it.config.getOrNull(SemanticsActions.CustomActions) != null })
            assertTrue(has("Send message"))
        }
    }

    @Test
    fun `a read only conversation explains why the composer is unavailable`() {
        val server = server().apply { readOnly = 1 }
        nativeSceneTest(390, 844, content = {
            ChatScreen(services, session, SELF, rooms.first(), onBack = {}, onOpenAttachment = {}, execute = server::execute)
        }) {
            settleUntil { has("This conversation is read-only.") }
            assertTrue(nodes().none { it.config.getOrNull(SemanticsActions.CustomActions) != null })
        }
    }

    @Test
    fun `visible chat receives live messages and stops when hidden`() {
        val server = server()
        val visibility = MutableStateFlow(true)
        nativeSceneTest(390, 844, content = {
            CompositionLocalProvider(LocalAppWindowVisibility provides visibility) {
                ChatScreen(services, session, SELF, rooms.first(), onBack = {}, onOpenAttachment = {}, execute = server::execute)
            }
        }) {
            settleUntil { server.pollsWaiting == 1 }
            assertTrue(has("Live"))
            server.post("alice", "A live synthetic message", actorDisplayName = "Alice")
            server.releasePoll()
            settleUntil { has("A live synthetic message") }
            assertTrue(has("A live synthetic message"))

            visibility.value = false
            settleUntil { has("Talk") }
            server.post("alice", "Sent while hidden", actorDisplayName = "Alice")
            server.releasePoll()
            settle()
            assertFalse(has("Sent while hidden"))
            visibility.value = true
            settleUntil { server.pollsWaiting > 0 }
            server.releasePoll()
            settleUntil { has("Sent while hidden") }
            assertTrue(has("Sent while hidden"))
        }
    }

    @Test
    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    fun `desktop keeps the list beside the conversation with keyboard switching`() {
        val server = server()
        val selected = mutableStateOf<TalkRoom?>(null)
        nativeSceneTest(1280, 800, content = {
            CompositionLocalProvider(LocalNextcloudWorkspaceCapabilities provides desktop) {
                TalkWorkspaceScreen(
                    services, session, SELF, selected.value,
                    onSelectRoom = { selected.value = it }, onCloseRoom = { selected.value = null }, onExit = {},
                    onOpenAttachment = {}, execute = { request ->
                        if (selected.value?.token == TOKEN) server.execute(request) else FakeTalkServer().execute(request)
                    },
                )
            }
        }) {
            settleUntil { has("Synthetic team") }
            assertTrue(has("Choose a conversation to read and reply."))
            click("Open Synthetic team")
            assertEquals(TOKEN, selected.value?.token)
            settleUntil { has("Hello from a synthetic account") }
            assertTrue(has("Second synthetic room"), "The list stays beside the open conversation.")
            assertTrue(has("Actions for message from Alice"), "Desktop shows an overflow for each message.")
            assertTrue(nodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Selected) == true && node.findText("Synthetic team")
            })

            // Tab reaches the list header first; Alt+Down then switches conversations from anywhere.
            key(Key.Tab)
            key(Key.DirectionDown, alt = true)
            assertEquals("synthroom2", selected.value?.token)
            key(Key.DirectionUp, alt = true)
            assertEquals(TOKEN, selected.value?.token)
            key(Key.MoveEnd, alt = true)
            assertEquals("synthroom3", selected.value?.token)
        }
    }

    @Test
    fun `list keys move between conversations and ignore other keys`() {
        assertEquals("synthroom2", talkRoomKeyboardTarget(rooms, TOKEN, Key.DirectionDown)?.token)
        assertEquals(TOKEN, talkRoomKeyboardTarget(rooms, null, Key.DirectionDown)?.token)
        assertEquals("synthroom3", talkRoomKeyboardTarget(rooms, null, Key.DirectionUp)?.token)
        assertEquals(null, talkRoomKeyboardTarget(rooms, "synthroom3", Key.DirectionDown), "The last room stays selected.")
        assertEquals(TOKEN, talkRoomKeyboardTarget(rooms, "synthroom3", Key.MoveHome)?.token)
        assertEquals(null, talkRoomKeyboardTarget(rooms, TOKEN, Key.A))
        assertEquals(null, talkRoomKeyboardTarget(emptyList(), null, Key.DirectionDown))
    }

    private fun TalkTransportRequest.isMessageDelete(): Boolean =
        method == TalkTransportMethod.DELETE && "/chat/" in relativePath

    @OptIn(InternalComposeUiApi::class)
    private suspend fun NativeSceneTestDriver.key(key: Key, alt: Boolean = false) {
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isAltPressed = alt))
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isAltPressed = alt))
        settle()
    }
}
