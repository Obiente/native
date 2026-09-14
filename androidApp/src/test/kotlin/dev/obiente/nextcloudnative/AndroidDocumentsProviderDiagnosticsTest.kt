package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.accountRecord
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AndroidDocumentsProviderDiagnosticsTest {
    private val original = NextcloudSession("https://CLOUD.example.test:443/", "alice", "synthetic-old")
    private val current = NextcloudSession("https://cloud.example.test", "alice", "synthetic-new")

    @Test
    fun staleCanonicalAndVerifiedAliasLookupFailuresUseCurrentRawDiagnosticScope() {
        val resolver = NextcloudDocumentsAccountResolver({ listOf(current.accountRecord()) }, { current },
            { NextcloudDocumentIncarnation.Versioned("a".repeat(32)) }, { setOf(NextcloudDocumentIds.accountKey(original)) })
        for (key in listOf(NextcloudDocumentIds.documentAccountKey(current), NextcloudDocumentIds.accountKey(original))) {
            val scopes = mutableListOf<String?>()
            assertFailsWith<FileNotFoundException> {
                androidDocumentsProviderCall("This document is no longer valid.", null,
                    { resolver.diagnosticSessionForAccountKey(key) }, { current }, { scope, _ -> scopes += scope }) {
                    resolver.requireDocument(NextcloudDocumentIds.documentId(key, NextcloudDocumentIncarnation.Legacy, "file"))
                }
            }
            assertEquals<List<String?>>(listOf(NextcloudDocumentIds.accountKey(current)), scopes)
            assertNotEquals(NextcloudDocumentIds.documentAccountKey(current), scopes.single())
        }
    }

    @Test
    fun removedUnknownAndBusyAccountsCannotRecreateAnUnownedDiagnosticScope(): Unit = runBlocking {
        val scopes = mutableListOf<String>()
        val guard = AndroidAccountOperationGuard()
        recordAndroidResolvedProviderFailure({ original }, { null }, guard, scopes::add)
        recordAndroidResolvedProviderFailure({ throw IllegalArgumentException("Unknown account") }, { current }, guard, scopes::add)
        guard.withAccount(current.accountId.storageKey) {
            recordAndroidResolvedProviderFailure({ current }, { current }, guard, scopes::add)
        }
        assertTrue(scopes.isEmpty())
        recordAndroidResolvedProviderFailure({ original }, { current }, guard, scopes::add)
        assertEquals<List<String?>>(listOf(NextcloudDocumentIds.accountKey(current)), scopes)
    }

    @Test
    fun cancellationDoesNotPublishAResolverFailure() {
        val scopes = mutableListOf<String?>()
        assertFailsWith<CancellationException> {
            androidDocumentsProviderCall("Unavailable", null, { current }, { current }, { scope, _ -> scopes += scope }) {
                throw CancellationException("cancelled")
            }
        }
        assertTrue(scopes.isEmpty())
    }
}
