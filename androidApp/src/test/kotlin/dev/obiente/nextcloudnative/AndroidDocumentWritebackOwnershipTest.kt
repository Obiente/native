package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.json.JSONObject

class AndroidDocumentWritebackOwnershipTest {
    @Test
    fun legacyWritebackMigratesOnceAndSurvivesEquivalentAddressChanges() {
        val first = NextcloudSession("https://cloud.example.test", "alice", "old-password")
        val next = first.copy(serverUrl = "https://cloud.example.test/", appPassword = "new-password")
        assertEquals(first.accountId, next.accountId)
        val root = Files.createTempDirectory("writeback-owner-test").toFile()
        try {
            val stage = File(root, "writeback-fixture.stage").apply { writeText("preserved edit") }
            val manifest = File(root, stage.name + ".json").apply {
                writeText(JSONObject().put("version", 1).put("account", NextcloudDocumentIds.accountKey(first))
                    .put("stage", stage.name).put("path", "notes.txt").put("etag", "etag-one")
                    .put("startedAt", 1L).put("ready", true).toString())
            }
            assertNull(parseOwnedAndroidDocumentWriteback(root, manifest, "f".repeat(64), emptySet()))
            val migrated = assertNotNull(parseOwnedAndroidDocumentWriteback(
                root, manifest, first.accountId.storageKey, setOf(NextcloudDocumentIds.accountKey(first)),
            ))
            assertEquals(first.accountId.storageKey, migrated.accountId)
            assertEquals(first.accountId.storageKey, JSONObject(manifest.readText()).getString("account"))
            assertNotNull(parseOwnedAndroidDocumentWriteback(root, manifest, next.accountId.storageKey, emptySet()))
            assertEquals("preserved edit", stage.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun changeNotificationsIncludeVerifiedLegacyObservers() {
        val session = NextcloudSession("https://cloud.example.test", "alice", "password")
        val legacy = "a".repeat(32)
        val keys = androidDocumentNotificationAccountKeys(session, setOf(legacy))
        assertEquals(setOf(NextcloudDocumentIds.documentAccountKey(session), NextcloudDocumentIds.accountKey(session), legacy), keys)
        val incarnation = NextcloudDocumentIncarnation.Legacy
        for (key in keys) {
            assertEquals(key, NextcloudDocumentIds.parse(NextcloudDocumentIds.documentId(key, incarnation, "notes.txt")).accountKey)
        }
    }
}
