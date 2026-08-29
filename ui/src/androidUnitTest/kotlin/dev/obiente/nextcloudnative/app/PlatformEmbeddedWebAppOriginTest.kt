package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlatformEmbeddedWebAppOriginTest {
    @Test
    fun explicitAndImplicitDefaultPortsHaveTheSameOrigin() {
        assertEquals(
            canonicalEmbeddedWebOrigin("https", "cloud.example.test", -1),
            canonicalEmbeddedWebOrigin("https", "cloud.example.test", 443),
        )
        assertEquals(
            canonicalEmbeddedWebOrigin("http", "cloud.example.test", -1),
            canonicalEmbeddedWebOrigin("http", "cloud.example.test", 80),
        )
    }

    @Test
    fun nonWebSchemesCannotBecomeEmbeddedOrigins() {
        assertNull(canonicalEmbeddedWebOrigin("file", "cloud.example.test", -1))
    }
}
