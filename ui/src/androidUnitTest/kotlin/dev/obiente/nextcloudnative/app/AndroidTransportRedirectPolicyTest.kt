package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.OkHttpClient

class AndroidTransportRedirectPolicyTest {
    @Test
    fun crossSchemeRedirectsAreRejectedWithoutDisablingSameSchemeRedirects() {
        val client = OkHttpClient.Builder()
            .rejectTlsDowngradeRedirects()
            .build()

        assertTrue(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }
}
