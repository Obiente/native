package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeBrandCompatibilityTest {
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
