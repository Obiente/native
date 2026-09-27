package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PhotoTimelineUiStateRetirementTest {
    @Test
    fun oldStateRejectsLateCompletionEvenAfterAccountReactivation() {
        val account = NextcloudSession("https://cloud.example.test", "photo-retirement", "synthetic-password")
        val key = account.accountId.storageKey
        AccountPrivateMemoryLifecycle.activateAccount(key)
        try {
            val old = PhotoTimelineUiStateRepository.stateFor(account)
            old.initialLoadCompleted.value = true
            assertTrue(old.initialLoadCompleted.value)
            AccountPrivateMemoryLifecycle.retireAccount(key)
            old.initialLoadCompleted.value = true
            assertFalse(old.initialLoadCompleted.value)
            val whileRetired = PhotoTimelineUiStateRepository.stateFor(account)
            whileRetired.initialLoadCompleted.value = true
            assertFalse(whileRetired.initialLoadCompleted.value)
            AccountPrivateMemoryLifecycle.activateAccount(key)
            val current = PhotoTimelineUiStateRepository.stateFor(account)
            old.initialLoadCompleted.value = true
            whileRetired.initialLoadCompleted.value = true
            assertFalse(old.initialLoadCompleted.value)
            assertFalse(whileRetired.initialLoadCompleted.value)
            assertFalse(current.initialLoadCompleted.value)
            assertNotSame(old, current)
            current.initialLoadCompleted.value = true
            assertTrue(current.initialLoadCompleted.value)
            assertSame(current, PhotoTimelineUiStateRepository.stateFor(account))
        } finally {
            AccountPrivateMemoryLifecycle.retireAccount(key)
        }
    }
}
