package dev.obiente.nextcloudnative.app

import java.util.prefs.Preferences
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopVirtualFileProviderPreferencesTest {
    @Test
    fun `provider activation key fits Java Preferences and remains account scoped`() {
        val firstAccountId = "a".repeat(64)
        val secondAccountId = "b".repeat(64)

        val firstKey = virtualFileProviderPreferenceKey(firstAccountId)
        val secondKey = virtualFileProviderPreferenceKey(secondAccountId)

        assertEquals("vfp-active.$firstAccountId", firstKey)
        assertTrue(firstKey.length <= Preferences.MAX_KEY_LENGTH)
        assertNotEquals(firstKey, secondKey)
    }

    @Test
    fun `failed provider cleanup retains the provider and blocks replacement`() {
        val cleanupFailure = IllegalStateException("simulated disconnect failure")
        var detached = false

        val returnedFailure = closeVirtualFileProviderForReplacement(
            provider = AutoCloseable { throw cleanupFailure },
            detach = { detached = true },
        )

        assertFalse(detached)
        assertSame(cleanupFailure, returnedFailure)
    }

    @Test
    fun `successful provider cleanup permits replacement detachment`() {
        var detached = false

        val returnedFailure = closeVirtualFileProviderForReplacement(
            provider = AutoCloseable {},
            detach = { detached = true },
        )

        assertTrue(detached)
        assertEquals(null, returnedFailure)
    }

    @Test
    fun `remote revocation attempt never restores a pre-disabled provider`() {
        var providerEnabled = false
        val removed = removeDesktopCredentialWithoutProviderReactivation(
            providerWasEnabled = false,
            clearProviderPreference = { providerEnabled = false },
            restoreProviderPreference = { enabled -> providerEnabled = enabled },
            removeCredential = { false },
        )

        assertFalse(removed)
        assertFalse(providerEnabled)
        assertFalse(shouldResumeDesktopWritesAfterRemovalFailure(false, true, false))
    }

    @Test
    fun `provider restore failure does not prevent in-memory account recovery`() {
        val events = mutableListOf<String>()
        val restoreFailure = IllegalStateException("synthetic preference flush failure")

        val recoveryFailure = recoverDesktopAccountAfterPrecommitFailure(
            restoreProviderPreference = { events += "restore"; throw restoreFailure },
            resumeVirtualFileSystem = { events += "resume" },
            reopenSession = { events += "reopen" },
            restartLifecycle = { events += "restart" },
        )

        assertSame(restoreFailure, recoveryFailure)
        assertEquals(listOf("restore", "resume", "reopen", "restart"), events)
    }

    @Test
    fun `aborted account removal leaves virtual file providers attached`() = runBlocking {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            commitDesktopAccountRemovalBeforeVirtualFileTeardown(
                commitRemoval = {
                    events += "remove"
                    error("credential removal failed")
                },
                teardownVirtualFiles = { events += "teardown" },
            )
        }

        assertEquals(listOf("remove"), events)
    }

    @Test
    fun `committed account removal tears down virtual file providers afterward`() = runBlocking {
        val events = mutableListOf<String>()

        commitDesktopAccountRemovalBeforeVirtualFileTeardown(
            commitRemoval = { events += "remove" },
            teardownVirtualFiles = { events += "teardown" },
        )

        assertEquals(listOf("remove", "teardown"), events)
    }

    @Test
    fun `committed removal clears support identities even when provider teardown fails`() {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            finishCommittedDesktopAccountRemoval(
                markRemovalCommitted = { events += "committed" },
                teardownVirtualFiles = {
                    events += "teardown"
                    error("synthetic unmount failure")
                },
                clearDiagnosticIdentity = { events += "diagnostics" },
                clearIntakeIdentity = { events += "intake" },
            )
        }

        assertEquals(listOf("committed", "teardown", "diagnostics", "intake"), events)
    }
}
