package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.accountRecord
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidDurableUploadAccountResolutionTest {
    @Test
    fun `inactive retained account defers when its credential is temporarily unavailable`() {
        val retainedSession = fixtureSession()

        val resolution = resolveDurableUploadSession(
            expectedAccountId = NextcloudDocumentIds.accountKey(retainedSession),
            registry = DurableUploadAccountRegistry.Available(listOf(retainedSession.accountRecord())),
            loadSession = { null },
        )

        assertEquals(DurableUploadAccountResolution.DeferAccountActivation, resolution)
    }

    @Test
    fun `active retained account retries when its credential is temporarily unavailable`() {
        val retainedSession = fixtureSession()

        val resolution = resolveDurableUploadSession(
            expectedAccountId = NextcloudDocumentIds.accountKey(retainedSession),
            registry = DurableUploadAccountRegistry.Available(
                accounts = listOf(retainedSession.accountRecord()),
                activeAccountId = retainedSession.accountId,
            ),
            loadSession = { null },
        )

        assertEquals(DurableUploadAccountResolution.CredentialUnavailable, resolution)
    }

    private fun fixtureSession() = NextcloudSession(
        serverUrl = "https://cloud.example.test/nextcloud",
        loginName = "alice",
        appPassword = "fixture-password",
    )
}
