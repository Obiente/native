package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine

class AndroidFileSyncRootPickerTest {
    @Test
    fun `provider rejection remains a typed failure instead of coroutine cancellation`() {
        val rejection = AndroidPickerUriRejectedException(AndroidPickerUriRejection.OwnDocumentsProvider)

        val thrown = assertFailsWith<AndroidPickerUriRejectedException> {
            runBlocking {
                suspendCancellableCoroutine<Unit> { continuation ->
                    resumeAndroidFileSyncPickerContinuation(continuation, Result.failure(rejection))
                }
            }
        }

        assertEquals(AndroidPickerUriRejection.OwnDocumentsProvider, thrown.rejection)
        assertEquals(AndroidPickerUriRejection.OwnDocumentsProvider.message, thrown.message)
    }
}
