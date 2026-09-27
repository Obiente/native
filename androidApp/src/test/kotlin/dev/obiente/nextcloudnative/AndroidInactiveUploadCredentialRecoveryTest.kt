package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.accountRecord
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidInactiveUploadCredentialRecoveryTest {
    @Test
    fun `inactive account resumes as soon as its credential is available`() {
        val session = fixtureSession()
        val registry = DurableUploadAccountRegistry.Available(listOf(session.accountRecord()))
        assertEquals(DurableUploadAccountResolution.CredentialUnavailable,
            resolveDurableUploadSession(NextcloudDocumentIds.accountKey(session), registry) { null })
        assertEquals(DurableUploadAccountResolution.Available(session),
            resolveDurableUploadSession(NextcloudDocumentIds.accountKey(session), registry) { session })
    }

    private fun fixtureSession() = NextcloudSession(
        serverUrl = "https://cloud.example.test/nextcloud",
        loginName = "alice",
        appPassword = "fixture-password",
    )
}
