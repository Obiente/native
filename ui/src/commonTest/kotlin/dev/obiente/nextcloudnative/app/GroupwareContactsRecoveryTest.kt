package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupwareContactsRecoveryTest {
    private val accountScope = durableMutationAccountScope(
        NextcloudSession("https://fixture.invalid", "synthetic-user", "synthetic-password"),
    )

    @Test
    fun delayedEffectCannotMixEarlierDecodeWithNewValidRecoveryRecord() = runBlocking {
        val href = "/remote.php/dav/addressbooks/users/synthetic-user/contacts/contact.vcf"
        val draft = ContactDraft("Synthetic Contact", "", "", "", "", "")
        val create = ContactMutationPostcondition.Upsert(
            href, href.substringBeforeLast('/') + "/", "synthetic-contact", null, draft,
        )
        listOf(create, create.copy(previousEtag = "old-etag"), ContactMutationPostcondition.Delete(href))
            .forEach { postcondition ->
                var liveRecord: String? = null
                val captured = contactRecoverySnapshot(liveRecord, accountScope)
                val releaseEffect = CompletableDeferred<Unit>()
                var recoveryDialogShown = false
                val effect = launch(start = CoroutineStart.UNDISPATCHED) {
                    releaseEffect.await()
                    // Old code paired this live non-null record with captured.postcondition == null.
                    assertTrue(liveRecord != null)
                    recoveryDialogShown = captured.unreadable
                }
                liveRecord = ContactMutationRecoveryState(accountScope, postcondition).encodeForSavedState()
                releaseEffect.complete(Unit)
                effect.join()
                assertFalse(recoveryDialogShown)
                val current = contactRecoverySnapshot(liveRecord, accountScope)
                assertFalse(current.unreadable)
                assertFalse(current.readyToVerify(loaded = true, requestRunning = true))
                assertTrue(current.readyToVerify(loaded = true, requestRunning = false))
                assertFalse(current.readyToVerify(loaded = false, requestRunning = false))
                assertTrue(current != captured)
                assertEquals(postcondition, current.postcondition)
                assertFalse(contactRecoverySnapshot(null, accountScope).hasRecord)
            }
    }

    @Test
    fun malformedAndOtherAccountRecoveryRemainProtected() {
        assertTrue(contactRecoverySnapshot("invalid", accountScope).unreadable)
        val encoded = ContactMutationRecoveryState(accountScope,
            ContactMutationPostcondition.Delete("/contacts/one.vcf")).encodeForSavedState()
        val anotherScope = durableMutationAccountScope(
            NextcloudSession("https://fixture.invalid", "another-user", "synthetic-password"),
        )
        assertTrue(contactRecoverySnapshot(encoded, anotherScope).unreadable)
    }
}
