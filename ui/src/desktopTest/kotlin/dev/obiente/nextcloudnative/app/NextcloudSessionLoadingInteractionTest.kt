package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NextcloudSessionLoadingInteractionTest {
    @Test
    fun unavailableSecureStorageOffersRetryAndStoredSessionReset() {
        var retries = 0
        var resets = 0

        nativeSceneTest(390, 844, content = {
            SecureSessionStorageUnavailable(
                onRetry = { retries += 1 },
                onSignInAgain = { resets += 1 },
            )
        }) {
            assertTrue(
                has(
                    "Secure session storage is locked or unavailable. Unlock it or allow " +
                        "Nextcloud Native access, then try again, or discard the stored session and sign in again.",
                ),
            )
            click("Try again")
            click("Sign in again")
            assertEquals(1, retries)
            assertEquals(1, resets)
        }
    }


    @Test
    fun unavailableLegacyMigrationOffersAStoredSessionReset() {
        var retries = 0
        var resets = 0

        nativeSceneTest(390, 844, content = {
            LegacySessionMigrationUnavailable(
                onRetry = { retries += 1 },
                onSignInAgain = { resets += 1 },
            )
        }) {
            assertTrue(
                has(
                    "The previous session needs the legacy secure-storage provider. Install the provider " +
                        "and try again, or discard the stored session and sign in again.",
                ),
            )
            click("Try again")
            click("Sign in again")
            assertEquals(1, retries)
            assertEquals(1, resets)
        }
    }
}
