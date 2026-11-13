package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TalkConversationSettingsTest {
    @Test
    fun `room settings parse moderator state and conservative defaults`() {
        val settings = assertNotNull(parseTalkConversationSettings(response(200, room(type = 2, participantType = 2))))
        assertEquals("Team", settings.name)
        assertEquals(TalkNotificationLevel.Mentions, settings.notificationLevel)
        assertTrue(settings.canModerate)
        assertTrue(settings.canRename)
        assertFalse(settings.guestsAllowed)
        val unknownReadOnly = assertNotNull(parseTalkConversationSettings(response(200,
            ocs("""{"token":"abc","displayName":"Team","type":2}"""))))
        assertTrue(unknownReadOnly.readOnly)
        assertEquals(null, parseTalkConversationSettings(response(404, "")))
        assertEquals(null, parseTalkConversationSettings(response(200, "garbage")))
    }

    @Test
    fun `client gate mirrors moderator and conversation-type rules`() {
        val member = assertNotNull(parseTalkConversationSettings(response(200, room(type = 2, participantType = 3))))
        assertTrue(member.allows(TalkConversationChange.Notifications(TalkNotificationLevel.Never)))
        assertFalse(member.allows(TalkConversationChange.Rename("x")))
        assertFalse(member.allows(TalkConversationChange.Lobby(true)))
        val oneToOne = assertNotNull(parseTalkConversationSettings(response(200, room(type = 1, participantType = 1))))
        assertFalse(oneToOne.allows(TalkConversationChange.Rename("x")))
        assertFalse(oneToOne.allows(TalkConversationChange.GuestAccess(true)))
        val guestModerator = assertNotNull(parseTalkConversationSettings(response(200, room(type = 3, participantType = 6))))
        assertTrue(guestModerator.allows(TalkConversationChange.Lobby(true)))
        assertFalse(guestModerator.allows(TalkConversationChange.GuestAccess(false)))
    }

    @Test
    fun `changes use the reviewed Spreed routes`() {
        fun plan(change: TalkConversationChange) = TalkSettingsRequests.change("tok+en", change)
        plan(TalkConversationChange.Rename(" New ")).let {
            assertEquals(TalkTransportMethod.PUT, it.method)
            assertEquals("/ocs/v2.php/apps/spreed/api/v4/room/tok%2Ben", it.relativePath)
            assertEquals("roomName=New", it.encodedFormBody()?.decodeToString())
        }
        assertEquals("level=3", plan(TalkConversationChange.Notifications(TalkNotificationLevel.Never)).encodedFormBody()?.decodeToString())
        assertTrue(plan(TalkConversationChange.Lobby(true)).relativePath.endsWith("/webinar/lobby"))
        plan(TalkConversationChange.GuestAccess(false)).let {
            assertEquals(TalkTransportMethod.DELETE, it.method)
            assertTrue(it.relativePath.endsWith("/public"))
        }
        plan(TalkConversationChange.RemoveParticipant(42, "Bob")).let {
            assertEquals(TalkTransportMethod.DELETE, it.method)
            assertEquals(listOf("attendeeId" to "42"), it.queryParameters)
        }
        assertFailsWith<IllegalArgumentException> { plan(TalkConversationChange.Rename(" ")) }
        assertFailsWith<IllegalArgumentException> { plan(TalkConversationChange.Moderator(0, true)) }
        assertFailsWith<IllegalArgumentException> { TalkSettingsRequests.change("", TalkConversationChange.Lobby(true)) }
        assertFailsWith<IllegalArgumentException> { TalkSettingsRequests.change("tok en", TalkConversationChange.Lobby(true)) }
        assertEquals(
            "key=typing_privacy&value=1",
            TalkSettingsRequests.userSetting(TalkUserSettingKey.TypingPrivacy, shared = false).encodedFormBody()?.decodeToString(),
        )
    }

    @Test
    fun `participants and privacy capabilities parse with feature gates`() {
        val participants = assertNotNull(parseTalkParticipants(response(200, ocs("""[
            {"attendeeId":2,"actorType":"users","actorId":"bob","displayName":"Bob","participantType":3},
            {"attendeeId":1,"actorType":"users","actorId":"alice","displayName":"Alice","participantType":1},
            {"attendeeId":0,"actorType":"users","actorId":"ghost"}
        ]"""))))
        assertEquals(listOf("Alice", "Bob"), participants.map { it.displayName })
        assertTrue(participants.reflects(TalkConversationChange.RemoveParticipant(9, "x")))
        assertFalse(participants.reflects(TalkConversationChange.Moderator(2, promote = true)))

        val privacy = assertNotNull(parseTalkUserPrivacy(response(200, ocs("""{"capabilities":{"spreed":{
            "features":["chat-read-status"],"config":{"chat":{"read-privacy":0,"typing-privacy":1}}}}}"""))))
        assertEquals(true, privacy.shareReadStatus)
        // Typing privacy is ignored without its feature flag.
        assertEquals(null, privacy.shareTyping)
        assertEquals(TalkUserPrivacySettings(null, null), parseTalkUserPrivacy(response(200, ocs("""{"capabilities":{}}"""))))
    }

    @Test
    fun `controller verifies a change with a fresh read and reports rejection`() = runBlocking {
        var notificationLevel = 2
        val requests = mutableListOf<TalkTransportRequest>()
        val controller = TalkConversationSettingsController(CoroutineScope(Dispatchers.Unconfined), "abc") { request ->
            requests += request
            when {
                request.relativePath.endsWith("/notify") -> {
                    notificationLevel = 3
                    response(200, ocs("[]"))
                }
                request.relativePath.endsWith("/webinar/lobby") -> response(403, "")
                request.relativePath.endsWith("/participants") -> response(200, ocs("[]"))
                else -> response(200, room(type = 2, participantType = 1, notificationLevel = notificationLevel))
            }
        }
        controller.refresh()
        assertNotNull(controller.state.settings)
        controller.apply(TalkConversationChange.Notifications(TalkNotificationLevel.Never))
        assertEquals(TalkNotificationLevel.Never, controller.state.settings?.notificationLevel)
        assertFalse(controller.state.messageIsError)
        controller.apply(TalkConversationChange.Lobby(true))
        assertTrue(controller.state.messageIsError)
        assertEquals(1, requests.count { it.relativePath.endsWith("/webinar/lobby") })
    }

    private fun room(type: Int, participantType: Int, notificationLevel: Int = 2) = ocs(
        """{"token":"abc","displayName":"Team","description":"Desc","type":$type,"readOnly":0,"lobbyState":0,
            "notificationLevel":$notificationLevel,"hasPassword":false,"participantType":$participantType}""",
    )

    private fun ocs(data: String) = """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":$data}}"""

    private fun response(status: Int, body: String) = TalkTransportResponse(status, body.encodeToByteArray(), null)
}
