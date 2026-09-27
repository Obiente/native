package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidPickerProviderFeedbackTest {
    private val applicationId = "dev.obiente.nextcloudnative.dev"
    private val ownAuthority = nextcloudDocumentsAuthority(applicationId)

    @Test
    fun `own document and tree uris are rejected before grant or capability publication`() {
        val sideEffects = mutableListOf<String>()
        val ownUris = listOf(
            "content://$ownAuthority/document/nc2%3Aaccount%3Aincarnation%3Afile",
            "content://$ownAuthority/tree/root/document/root%2Ffolder",
            "content://${ownAuthority.uppercase()}/document/file",
            "content://10@$ownAuthority/tree/root",
            "content://dev%2Eobiente%2Enextcloudnative%2Edev%2Edocuments/document/file",
        )

        ownUris.forEach { uri ->
            val failure = assertFailsWith<AndroidPickerUriRejectedException> {
                requireExternalAndroidPickerUri(uri, applicationId)
                sideEffects += "take-grant"
                sideEffects += "publish-capability"
            }
            assertEquals(AndroidPickerUriRejection.OwnDocumentsProvider, failure.rejection)
        }

        assertEquals(emptyList(), sideEffects)
    }

    @Test
    fun `malformed picker uris fail before durable state`() {
        val sideEffects = mutableListOf<String>()
        val malformedUris = listOf(
            "file://$ownAuthority/document/file",
            "content:///document/file",
            "content://user@external.documents/document/file",
            "content://10@@external.documents/document/file",
            "content://external%2Fdocuments/document/file",
            "content://external.documents/%broken",
        )

        malformedUris.forEach { uri ->
            val failure = assertFailsWith<AndroidPickerUriRejectedException> {
                requireExternalAndroidPickerUri(uri, applicationId)
                sideEffects += "create-durable-state"
            }
            assertEquals(AndroidPickerUriRejection.Invalid, failure.rejection)
        }

        assertEquals(emptyList(), sideEffects)
    }

    @Test
    fun `unrelated external document and tree providers remain accepted`() {
        val accepted = mutableListOf<String>()
        val externalUris = listOf(
            "content://com.android.providers.downloads.documents/document/42",
            "content://EXTERNAL.PROVIDER/tree/root/document/root%2Ffolder",
            "content://10@external_provider/tree/root",
        )

        externalUris.forEach { uri ->
            requireExternalAndroidPickerUri(uri, applicationId)
            accepted += uri
        }

        assertEquals(externalUris, accepted)
    }
}
