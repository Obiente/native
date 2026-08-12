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
    fun persistedChannelsMigrateToTheEnforcedNightlyTrack() {
        assertTrue(appUpdateChannelSelectionLocked)
        assertEquals(AndroidUpdateChannel.Nightly, enforcedAppUpdateChannel)
        assertEquals(AndroidUpdateChannel.Nightly, parseAndroidUpdateChannel(null))
        assertEquals(AndroidUpdateChannel.Nightly, parseAndroidUpdateChannel(""))
        assertEquals(AndroidUpdateChannel.Nightly, parseAndroidUpdateChannel("unknown"))
        assertEquals(AndroidUpdateChannel.Nightly, parseAndroidUpdateChannel("alpha"))
        assertEquals(AndroidUpdateChannel.Nightly, parseAndroidUpdateChannel("prerelease-v1"))
        assertEquals(AndroidUpdateChannel.Nightly, parseAndroidUpdateChannel("Nightly"))
        assertEquals(AndroidUpdateChannel.Nightly, parseAndroidUpdateChannel("nightly-v1"))
        assertEquals(AndroidUpdateChannel.Nightly, parseAndroidUpdateChannel("beta"))
        assertEquals(AndroidUpdateChannel.Nightly, parseAndroidUpdateChannel("stable-v1"))
    }

    @Test
    fun directChannelSelectionRemainsLocked() {
        assertFalse(canSelectAppUpdateChannel(directSupport, AndroidUpdateChannel.Alpha))
        assertFalse(canSelectAppUpdateChannel(directSupport, AndroidUpdateChannel.Nightly))
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
        assertFalse(
            canSelectAppUpdateChannel(
                directSupport.copy(channel = AppDistributionChannel.DirectDesktopPackage),
                AndroidUpdateChannel.Nightly,
            ),
        )
    }

    @Test
    fun presentationShowsOnlyTheLockedNightlyTrack() {
        val presentation = appUpdateChannelPresentation(
            directSupport,
            selectedChannel = AndroidUpdateChannel.Alpha,
        )

        assertTrue(presentation.selectorVisible)
        assertFalse(presentation.selectorEnabled)
        assertEquals(AndroidUpdateChannel.Nightly, presentation.selectedChannel)
        val nightly = presentation.options.single()
        assertEquals(AndroidUpdateChannel.Nightly, nightly.channel)
        assertTrue(nightly.selected)
        assertFalse(nightly.enabled)
        assertEquals("Locked for now", nightly.availabilityLabel)
        assertTrue(nightly.description.contains("signed APK"))
        assertTrue(nightly.description.contains("less stable"))

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

        val desktopPresentation = appUpdateChannelPresentation(
            directSupport.copy(channel = AppDistributionChannel.DirectDesktopPackage),
            selectedChannel = AndroidUpdateChannel.Nightly,
        )
        assertTrue(desktopPresentation.options.none { it.description.contains("signed", ignoreCase = true) })
    }

    @Test
    fun updateReviewRequestsAreOfferedOnceEvenWithoutAnAccountSession() {
        assertNull(unhandledAppUpdateReviewRequest(requested = 0L, handled = 0L))
        assertEquals(1L, unhandledAppUpdateReviewRequest(requested = 1L, handled = 0L))
        assertNull(unhandledAppUpdateReviewRequest(requested = 1L, handled = 1L))
        assertEquals(2L, unhandledAppUpdateReviewRequest(requested = 2L, handled = 1L))
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
