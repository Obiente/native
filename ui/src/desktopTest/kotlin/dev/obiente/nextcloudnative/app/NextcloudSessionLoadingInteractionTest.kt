package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NextcloudSessionLoadingInteractionTest {
    @Test
    fun unavailableSecureStorageExplainsRecoveryAndOffersRetry() {
        var retries = 0

        nativeSceneTest(390, 844, content = {
            SecureSessionStorageUnavailable(onRetry = { retries += 1 })
        }) {
            assertTrue(
                has(
                    "Secure session storage is locked or unavailable. Unlock it or allow " +
                        "Nextcloud Native access, then try again.",
                ),
            )
            click("Try again")
            assertEquals(1, retries)
        }
    }
}
