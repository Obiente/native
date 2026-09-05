package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeBrandCompatibilityTest {
    @Test
    fun newsRequestsUseTheNewHostWithoutChangingTheFrozenFeed() {
        assertEquals("https://nati.ve/news-feed-v1.json", PROJECT_NEWS_FEED_URL)
        assertEquals("https://nati.ve/screenshots/mobile-home.png", canonicalProjectNewsImageRequestUrl(
            "https://nc-native.obiente.dev/screenshots/mobile-home.png",
        ))
        assertEquals("https://nati.ve/screenshots/mobile-home.png", canonicalProjectNewsImageRequestUrl(
            "https://nati.ve/screenshots/mobile-home.png",
        ))
        assertFailsWith<IllegalArgumentException> {
            canonicalProjectNewsImageRequestUrl("https://nc-native.obiente.dev.example.org/screenshots/mobile-home.png")
        }
    }

    @Test
    fun newsImagesAcceptBothProductOriginsWithoutBroadeningTrust() {
        assertTrue(isCanonicalProjectNewsImageUrl("https://nati.ve/screenshots/mobile-home.png"))
        assertTrue(isCanonicalProjectNewsImageUrl("https://nc-native.obiente.dev/screenshots/mobile-home.png"))
        for (url in listOf(
            "https://nati.ve.example.com/screenshots/mobile-home.png",
            "http://nati.ve/screenshots/mobile-home.png",
            "https://nati.ve/screenshots/../private.png",
            "https://nati.ve/screenshots/%2e%2e/private.png",
            "https://nati.ve/screenshots/mobile-home.png?token=example",
        )) assertFalse(isCanonicalProjectNewsImageUrl(url), url)
    }
}
