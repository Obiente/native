package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TalkMessagesTest {
    @Test
    fun rendersRichTextParameters() {
        val message = parseTalkMessageJson(
            """
            {
              "id": 12,
              "actorDisplayName": "Ada",
              "actorId": "ada",
              "actorType": "users",
              "message": "Hello {mention-call1}",
              "messageParameters": {
                "mention-call1": {"type": "user", "id": "lin", "name": "Lin"}
              },
              "messageType": "comment",
              "systemMessage": "",
              "timestamp": 42,
              "markdown": true
            }
            """.trimIndent(),
        )

        assertNotNull(message)
        assertEquals("Hello Lin", message.message)
        assertEquals(TalkMessageType.Comment, message.messageType)
        assertEquals("users", message.actorType)
        assertTrue(assertIs<TalkMessageContent.Text>(message.content).markdown)
        assertFalse(message.isSystemMessage)
    }

    @Test
    fun parsesSharedFileIntoTypedAttachment() {
        val message = parseTalkMessageJson(
            """
            {
              "id": 27,
              "actorDisplayName": "Ada",
              "actorId": "ada",
              "actorType": "users",
              "message": "{actor} shared {file}",
              "messageParameters": {
                "actor": {"type": "user", "id": "ada", "name": "Ada"},
                "file": {
                  "type": "file",
                  "id": "901",
                  "name": "sunset.jpg",
                  "path": "Talk/sunset.jpg",
                  "mimetype": "image/jpeg",
                  "size": "4096",
                  "preview-available": "yes",
                  "hide-download": "no",
                  "etag": "abc",
                  "width": "2048",
                  "height": "1536"
                }
              },
              "messageType": "object_shared",
              "systemMessage": "file_shared",
              "timestamp": 99
            }
            """.trimIndent(),
        )

        val fileShare = assertIs<TalkMessageContent.FileShare>(assertNotNull(message).content)
        assertEquals("Ada shared sunset.jpg", fileShare.summary)
        assertEquals(1, fileShare.attachments.size)
        assertEquals(901L, fileShare.attachment.fileId)
        assertEquals(TalkAttachmentKind.Image, fileShare.attachment.kind)
        assertEquals(4096L, fileShare.attachment.size)
        assertTrue(fileShare.attachment.previewAvailable)
        assertFalse(fileShare.attachment.hideDownload)
        assertEquals(2048, fileShare.attachment.width)
    }

    @Test
    fun keepsEveryFileParameterInAMultiAttachmentMessage() {
        val message = parseTalkMessageJson(
            """
            {
              "id": 28,
              "actorDisplayName": "Ada",
              "message": "Shared {file1} and {file2}",
              "messageParameters": {
                "file1": {"type": "file", "id": "1", "name": "one.jpg", "mimetype": "image/jpeg"},
                "file2": {"type": "file", "id": "2", "name": "two.mp3", "mimetype": "audio/mpeg"}
              },
              "messageType": "comment",
              "timestamp": 100
            }
            """.trimIndent(),
        )

        val files = assertIs<TalkMessageContent.FileShare>(assertNotNull(message).content).attachments
        assertEquals(listOf("one.jpg", "two.mp3"), files.map(TalkFileAttachment::name))
        assertEquals(listOf(TalkAttachmentKind.Image, TalkAttachmentKind.Audio), files.map(TalkFileAttachment::kind))
    }

    @Test
    fun parsesCallSystemMessageIntoCallEvent() {
        val message = parseTalkMessageJson(
            """
            {
              "id": 31,
              "actorDisplayName": "Ada",
              "actorId": "ada",
              "actorType": "users",
              "message": "{actor} started a call",
              "messageParameters": {
                "actor": {"type": "user", "id": "ada", "name": "Ada"}
              },
              "messageType": "system",
              "systemMessage": "call_started",
              "timestamp": 101
            }
            """.trimIndent(),
        )

        val call = assertIs<TalkMessageContent.Call>(assertNotNull(message).content)
        assertEquals(TalkCallEventType.Started, call.event.type)
        assertEquals("Ada started a call", call.summary)
        assertTrue(message.isSystemMessage)
    }

    @Test
    fun keepsUnknownSystemMessageTypedAndReadable() {
        val message = parseTalkMessageJson(
            """
            {
              "id": 44,
              "actorDisplayName": "Nextcloud",
              "actorId": "system",
              "actorType": "users",
              "message": "",
              "messageParameters": {},
              "messageType": "system",
              "systemMessage": "future_server_event",
              "timestamp": 102
            }
            """.trimIndent(),
        )

        val system = assertIs<TalkMessageContent.System>(assertNotNull(message).content)
        assertEquals(TalkSystemMessageType.Unknown, system.event.type)
        assertEquals("future_server_event", system.event.rawType)
        assertEquals("Future server event", system.summary)
    }

    @Test
    fun preservesThreadReplyReactionAndEditState() {
        val message = parseTalkMessageJson(
            """
            {
              "id": 72,
              "actorDisplayName": "Ada",
              "actorId": "ada",
              "actorType": "users",
              "message": "Updated answer",
              "messageType": "comment",
              "timestamp": 200,
              "threadId": 41,
              "isThread": true,
              "threadTitle": "Release planning",
              "threadReplies": 3,
              "isReplyable": true,
              "reactions": {"👍": 4, "🎉": 1, "ignored": 0},
              "reactionsSelf": ["👍"],
              "lastEditTimestamp": 201,
              "lastEditActorDisplayName": "Ada",
              "silent": true,
              "expirationTimestamp": 900,
              "referenceId": "local-72",
              "parent": {
                "id": 41,
                "actorDisplayName": "Lin",
                "message": "Ship {file}",
                "messageParameters": {
                  "file": {"type": "file", "id": "5", "name": "roadmap.md"}
                }
              }
            }
            """.trimIndent(),
        )

        assertNotNull(message)
        assertEquals(41L, message.threadId)
        assertTrue(message.isThread)
        assertEquals("Release planning", message.threadTitle)
        assertEquals(3, message.threadReplies)
        assertTrue(message.isReplyable)
        assertEquals("Ship roadmap.md", message.parent?.summary)
        assertEquals("Lin", message.parent?.actorDisplayName)
        assertEquals(listOf("👍", "🎉"), message.reactions.map(TalkReaction::emoji))
        assertTrue(message.reactions.first().reactedByMe)
        assertEquals(4, message.reactions.first().count)
        assertEquals(201L, message.editedAt)
        assertTrue(message.silent)
        assertEquals(900L, message.expiresAt)
        assertEquals("local-72", message.referenceId)
    }

    @Test
    fun preservesDeletedAndScheduledState() {
        val message = parseTalkMessageJson(
            """
            {
              "id": 73,
              "actorDisplayName": "Ada",
              "message": "Later",
              "messageType": "comment_deleted",
              "deleted": true,
              "sendAt": 450,
              "timestamp": 205
            }
            """.trimIndent(),
        )

        assertNotNull(message)
        assertTrue(message.deleted, message.toString())
        assertEquals(450L, message.scheduledAt)
    }

    @Test
    fun rejectsMalformedJson() {
        assertNull(parseTalkMessageJson("not-json"))
    }
}
