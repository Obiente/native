package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.accountRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidDocumentLegacyAliasesTest {
    private val original = NextcloudSession("https://CLOUD.example.test:443", "alice", "synthetic-old-password")
    private val replacement = original.copy(serverUrl = "https://cloud.example.test", appPassword = "synthetic-new-password")

    @Test
    fun historicalRawGrantSurvivesEquivalentReauthenticationAndRestart() {
        val disk = mutableMapOf<String, String>()
        val aliases = AndroidDocumentLegacyAliases(disk::get) { key, value -> disk[key] = value; true }
        val incarnation = NextcloudDocumentIncarnation.Legacy
        aliases.remember(replacement, original, incarnation)
        val restarted = AndroidDocumentLegacyAliases(disk::get) { _, _ -> error("Read-only lookup") }
        val resolver = NextcloudDocumentsAccountResolver(
            { listOf(replacement.accountRecord()) }, { replacement }, { incarnation },
            { id -> restarted.read(id.storageKey, incarnation) },
        )
        val oldId = NextcloudDocumentIds.rootId(NextcloudDocumentIds.accountKey(original), incarnation)
        assertEquals(replacement, resolver.requireDocument(oldId).session)
        assertEquals(replacement, resolver.requireRoot(NextcloudDocumentIds.accountKey(original)).session)
        val next = NextcloudDocumentIncarnation.Versioned("a".repeat(32))
        assertTrue(restarted.read(replacement.accountId.storageKey, next).isEmpty())
        aliases.clear(replacement.accountId.storageKey)
        assertTrue(restarted.read(replacement.accountId.storageKey, incarnation).isEmpty())
    }

    @Test
    fun malformedOrUncommittedAliasesFailClosed() {
        val malformed = AndroidDocumentLegacyAliases({ "legacy\ninvalid" }, { _, _ -> true })
        assertFailsWith<IllegalArgumentException> { malformed.read(original.accountId.storageKey, NextcloudDocumentIncarnation.Legacy) }
        val failed = AndroidDocumentLegacyAliases({ null }, { _, _ -> false })
        assertFailsWith<IllegalStateException> { failed.remember(replacement, original, NextcloudDocumentIncarnation.Legacy) }
        assertFailsWith<IllegalArgumentException> {
            failed.remember(replacement, original.copy(loginName = "bob"), NextcloudDocumentIncarnation.Legacy)
        }
    }

    @Test
    fun verifiedRecoveryRetiresMalformedAliasesAndRetainsOnlyKnownSessions() {
        val key = original.accountId.storageKey
        val incarnation = NextcloudDocumentIncarnation.Legacy
        var encoded = "legacy\ninvalid"
        val aliases = AndroidDocumentLegacyAliases({ encoded }, { _, value -> encoded = value; true })
        assertTrue(aliases.readVerified(key, incarnation).isEmpty())
        aliases.remember(replacement, original, incarnation)
        assertEquals(setOf(NextcloudDocumentIds.accountKey(original), NextcloudDocumentIds.accountKey(replacement)),
            aliases.read(key, incarnation))
        aliases.clear(key)
        assertEquals("removed", encoded)
        val wrongType = AndroidDocumentLegacyAliases({ throw ClassCastException() }, { _, _ -> true })
        assertTrue(wrongType.readVerified(key, incarnation).isEmpty())
        val inaccessible = AndroidDocumentLegacyAliases({ throw java.io.IOException() }, { _, _ -> true })
        assertFailsWith<java.io.IOException> { inaccessible.readVerified(key, incarnation) }
        assertFailsWith<IllegalArgumentException> { aliases.readVerified("invalid", incarnation) }
    }
}
