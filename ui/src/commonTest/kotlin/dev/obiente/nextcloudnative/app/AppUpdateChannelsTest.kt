package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AppUpdateChannelsTest {
    private val directSupport = AppUpdateSupport(
        channel = AppDistributionChannel.DirectApk,
        currentVersionName = "0.1.0-alpha.1",
        currentVersionCode = 20_000_012,
        canCheckDirectUpdates = true,
        explanation = "Direct updates are available.",
    )

    @Test
    fun persistedChannelRestoresAvailableValuesAndSafelyDefaultsFutureValues() {
        assertEquals(AndroidUpdateChannel.Alpha, parseAndroidUpdateChannel(null))
        assertEquals(AndroidUpdateChannel.Alpha, parseAndroidUpdateChannel(""))
        assertEquals(AndroidUpdateChannel.Alpha, parseAndroidUpdateChannel("unknown"))
        assertEquals(AndroidUpdateChannel.Alpha, parseAndroidUpdateChannel("alpha"))
        assertEquals(AndroidUpdateChannel.Alpha, parseAndroidUpdateChannel("prerelease-v1"))
        assertEquals(AndroidUpdateChannel.Nightly, parseAndroidUpdateChannel("Nightly"))
        assertEquals(AndroidUpdateChannel.Nightly, parseAndroidUpdateChannel("nightly-v1"))
        assertEquals(AndroidUpdateChannel.Alpha, parseAndroidUpdateChannel("beta"))
        assertEquals(AndroidUpdateChannel.Alpha, parseAndroidUpdateChannel("stable-v1"))
    }

    @Test
    fun onlyAvailableDirectChannelsCanBeSelected() {
        assertTrue(canSelectAppUpdateChannel(directSupport, AndroidUpdateChannel.Alpha))
        assertTrue(canSelectAppUpdateChannel(directSupport, AndroidUpdateChannel.Nightly))
        assertFalse(canSelectAppUpdateChannel(directSupport, AndroidUpdateChannel.Beta))
        assertFalse(canSelectAppUpdateChannel(directSupport, AndroidUpdateChannel.Stable))
        assertFalse(
            canSelectAppUpdateChannel(
                directSupport.copy(
                    channel = AppDistributionChannel.GooglePlay,
                    canCheckDirectUpdates = false,
                ),
                AndroidUpdateChannel.Nightly,
            ),
        )
        assertTrue(
            canSelectAppUpdateChannel(
                directSupport.copy(channel = AppDistributionChannel.DirectDesktopPackage),
                AndroidUpdateChannel.Nightly,
            ),
        )
    }

    @Test
    fun presentationExposesRiskAndFutureChannelsWithoutEnablingThem() {
        val presentation = appUpdateChannelPresentation(
            directSupport,
            selectedChannel = AndroidUpdateChannel.Nightly,
        )

        assertTrue(presentation.selectorVisible)
        assertTrue(presentation.selectorEnabled)
        assertEquals(AndroidUpdateChannel.entries.toList(), presentation.options.map { it.channel })
        val nightly = presentation.options.single { it.channel == AndroidUpdateChannel.Nightly }
        assertTrue(nightly.selected)
        assertTrue(nightly.enabled)
        assertTrue(nightly.description.contains("less stable"))
        listOf(AndroidUpdateChannel.Beta, AndroidUpdateChannel.Stable).forEach { channel ->
            val option = presentation.options.single { it.channel == channel }
            assertFalse(option.enabled)
            assertEquals("Coming later", option.availabilityLabel)
        }

        val storePresentation = appUpdateChannelPresentation(
            directSupport.copy(
                channel = AppDistributionChannel.FDroid,
                canCheckDirectUpdates = false,
            ),
            selectedChannel = AndroidUpdateChannel.Alpha,
        )
        assertFalse(storePresentation.selectorVisible)
        assertFalse(storePresentation.selectorEnabled)
        assertTrue(storePresentation.options.none { it.enabled })
    }

    @Test
    fun changingChannelInvalidatesAResultFromThePreviousChannel() {
        val result = AppUpdateCheckResult.Current(directSupport)
        assertEquals(
            result,
            retainedAppUpdateCheckResult(
                previousChannel = AndroidUpdateChannel.Alpha,
                selectedChannel = AndroidUpdateChannel.Alpha,
                previousResult = result,
            ),
        )
        assertNull(
            retainedAppUpdateCheckResult(
                previousChannel = AndroidUpdateChannel.Alpha,
                selectedChannel = AndroidUpdateChannel.Nightly,
                previousResult = result,
            ),
        )
    }

    @Test
    fun onlyAvailableChannelsResolveCanonicalMetadataPointers() {
        assertEquals(
            "https://github.com/Obiente/nc-native/releases/download/" +
                "channel-prerelease/update-manifest.json",
            AndroidUpdateChannel.Alpha.manifestUrl(),
        )
        assertEquals(
            "https://github.com/Obiente/nc-native/releases/download/" +
                "channel-nightly/update-manifest.json",
            AndroidUpdateChannel.Nightly.manifestUrl(),
        )
        listOf(AndroidUpdateChannel.Beta, AndroidUpdateChannel.Stable).forEach { channel ->
            assertFailsWith<IllegalArgumentException> {
                channel.manifestUrl()
            }
            assertFalse(
                isCanonicalAndroidUpdateManifestUrl(
                    "https://github.com/Obiente/nc-native/releases/download/" +
                        "${channel.pointerTag}/update-manifest.json",
                    channel,
                ),
            )
        }
    }

    @Test
    fun metadataIsBoundToTheSelectedChannelAndImmutableReleaseAssets() {
        val alpha = release(
            channel = AndroidUpdateChannel.Alpha,
            versionName = "0.2.0-alpha.3",
            versionCode = 20_000_032,
            tag = "v0.2.0-alpha.3",
        )
        val nightlyTag = "nightly-20260726-0145-run123-abcdef12"
        val nightly = release(
            channel = AndroidUpdateChannel.Nightly,
            versionName = nightlyTag,
            versionCode = 20_000_041,
            tag = nightlyTag,
        )

        assertEquals(
            alpha,
            parseAndroidDirectRelease(
                Json.encodeToString(alpha).encodeToByteArray(),
                AndroidUpdateChannel.Alpha.manifestUrl(),
                AndroidUpdateChannel.Alpha,
            ),
        )
        assertEquals(
            nightly,
            parseAndroidDirectRelease(
                Json.encodeToString(nightly).encodeToByteArray(),
                AndroidUpdateChannel.Nightly.manifestUrl(),
                AndroidUpdateChannel.Nightly,
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            parseAndroidDirectRelease(
                Json.encodeToString(alpha).encodeToByteArray(),
                AndroidUpdateChannel.Alpha.manifestUrl(),
                AndroidUpdateChannel.Nightly,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validateAndroidDirectRelease(alpha, AndroidUpdateChannel.Nightly)
        }
        assertFailsWith<IllegalArgumentException> {
            validateAndroidDirectRelease(
                nightly.copy(signingCertificateSha256Digests = listOf("invalid")),
                AndroidUpdateChannel.Nightly,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validateAndroidDirectRelease(
                nightly.copy(
                    apkUrl =
                        "https://github.com/Obiente/nc-native/releases/download/" +
                            "nightly-20260726-0145-run124-abcdef12/" +
                            "nextcloud-native-$nightlyTag-android.apk",
                ),
                AndroidUpdateChannel.Nightly,
            )
        }
    }

    @Test
    fun versionCodeOrderingPreventsDowngradesAcrossChannels() {
        val newerNightly = release(
            channel = AndroidUpdateChannel.Nightly,
            versionName = "nightly-20260726-0145-run123-abcdef12",
            versionCode = 20_000_041,
            tag = "nightly-20260726-0145-run123-abcdef12",
        )
        val olderAlpha = release(
            channel = AndroidUpdateChannel.Alpha,
            versionName = "0.2.0-alpha.3",
            versionCode = 20_000_032,
            tag = "v0.2.0-alpha.3",
        )

        assertTrue(isNewerAndroidRelease(20_000_032, newerNightly))
        assertFalse(isNewerAndroidRelease(20_000_041, olderAlpha))
        assertFalse(isNewerAndroidRelease(20_000_041, newerNightly))
    }

    private fun release(
        channel: AndroidUpdateChannel,
        versionName: String,
        versionCode: Long,
        tag: String,
    ) = AndroidDirectRelease(
        schemaVersion = 1,
        channel = channel.manifestChannel,
        versionName = versionName,
        versionCode = versionCode,
        packageName = "dev.obiente.nextcloudnative",
        minimumAndroidSdk = 26,
        apkUrl =
            "https://github.com/Obiente/nc-native/releases/download/$tag/" +
                "nextcloud-native-$versionName-android.apk",
        apkSize = 123_456,
        apkSha256 = "a".repeat(64),
        signingCertificateSha256Digests = listOf("b".repeat(64)),
        releaseNotesUrl = "https://github.com/Obiente/nc-native/releases/tag/$tag",
    )
}
