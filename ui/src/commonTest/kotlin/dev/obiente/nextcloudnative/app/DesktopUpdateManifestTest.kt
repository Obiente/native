package dev.obiente.nextcloudnative.app

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopUpdateManifestTest {
    private val tag = "nightly-20260731-1543-run358-02200472"
    private val releaseRoot = "https://github.com/obiente/native/releases/download/$tag/"
    private val manifest = DesktopUpdateManifest(
        schemaVersion = 1,
        channel = AndroidUpdateChannel.Nightly.manifestChannel,
        versionName = tag,
        versionCode = 20_002_921,
        packageVersion = "1.0.2921",
        releaseNotesUrl = "https://github.com/obiente/native/releases/tag/$tag",
        assets = listOf(
            DesktopUpdateAsset(
                platform = "linux",
                format = "rpm",
                architecture = "x86_64",
                url = "${releaseRoot}nextcloudnative-1.0.2921-1.x86_64.rpm",
                size = 131_102_933,
                sha256 = "a".repeat(64),
            ),
            DesktopUpdateAsset(
                platform = "linux",
                format = "deb",
                architecture = "x86_64",
                url = "${releaseRoot}nextcloudnative_1.0.2921_amd64.deb",
                size = 118_392_008,
                sha256 = "b".repeat(64),
            ),
            DesktopUpdateAsset(
                platform = "windows",
                format = "msi",
                architecture = "x86_64",
                url = "${releaseRoot}NextcloudNative-1.0.2921.msi",
                size = 128_215_557,
                sha256 = "c".repeat(64),
            ),
            DesktopUpdateAsset(
                platform = "macos",
                format = "dmg",
                architecture = "x86_64",
                url = "${releaseRoot}NextcloudNative-1.0.2921.dmg",
                size = 139_841_723,
                sha256 = "d".repeat(64),
            ),
        ),
    )

    @Test
    fun desktopManifestSelectsTheExactLinuxPackageWithoutLosingFuturePlatformAssets() {
        val bytes = Json.encodeToString(manifest).encodeToByteArray()
        val release = parseDesktopDirectRelease(
            bytes = bytes,
            metadataUrl = AndroidUpdateChannel.Nightly.desktopManifestUrl(),
            expectedChannel = AndroidUpdateChannel.Nightly,
            platform = "linux",
            format = "rpm",
            architecture = "x86_64",
        )

        assertEquals(manifest.versionName, release.versionName)
        assertEquals(manifest.versionCode, release.versionCode)
        assertEquals(AndroidUpdateChannel.Nightly, release.updateChannel)
        assertEquals("rpm", release.asset.format)
        assertEquals("windows", manifest.assets.single { it.format == "msi" }.platform)
        assertTrue(release.changes.isEmpty())
        assertTrue(isNewerAppRelease(manifest.versionCode - 1, release))
        assertFalse(isNewerAppRelease(manifest.versionCode, release))

        val bytesWithUnknownField =
            bytes.decodeToString().dropLast(1) + ",\"futureField\":true}"
        assertEquals(
            release,
            parseDesktopDirectRelease(
                bytes = bytesWithUnknownField.encodeToByteArray(),
                metadataUrl = AndroidUpdateChannel.Nightly.desktopManifestUrl(),
                expectedChannel = AndroidUpdateChannel.Nightly,
                platform = "linux",
                format = "rpm",
                architecture = "x86_64",
            ),
        )
    }

    @Test
    fun desktopManifestRejectsMissingTargetsCrossChannelAssetsAndUntrustedUrls() {
        val metadataUrl = AndroidUpdateChannel.Nightly.desktopManifestUrl()
        assertFailsWith<IllegalStateException> {
            parseDesktopDirectRelease(
                Json.encodeToString(manifest).encodeToByteArray(),
                metadataUrl,
                AndroidUpdateChannel.Nightly,
                platform = "linux",
                format = "deb",
                architecture = "aarch64",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validateDesktopUpdateManifest(
                manifest.copy(channel = AndroidUpdateChannel.Alpha.manifestChannel),
                AndroidUpdateChannel.Nightly,
                metadataUrl,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validateDesktopUpdateManifest(
                manifest.copy(
                    assets = manifest.assets.map { asset ->
                        if (asset.format == "rpm") asset.copy(url = "https://downloads.invalid/update.rpm") else asset
                    },
                ),
                AndroidUpdateChannel.Nightly,
                metadataUrl,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validateDesktopUpdateManifest(
                manifest.copy(assets = manifest.assets + manifest.assets.first()),
                AndroidUpdateChannel.Nightly,
                metadataUrl,
            )
        }
    }

    @Test
    fun onlyCanonicalPointerAndImmutableDesktopManifestUrlsAreAccepted() {
        assertTrue(
            isCanonicalDesktopUpdateManifestUrl(
                AndroidUpdateChannel.Nightly.desktopManifestUrl(),
                AndroidUpdateChannel.Nightly,
            ),
        )
        assertTrue(
            isCanonicalDesktopUpdateManifestUrl(
                "${releaseRoot}desktop-update-manifest.json",
                AndroidUpdateChannel.Nightly,
            ),
        )
        assertFalse(
            isCanonicalDesktopUpdateManifestUrl(
                "${releaseRoot}desktop-update-manifest.json?download=1",
                AndroidUpdateChannel.Nightly,
            ),
        )
        assertFalse(
            isCanonicalDesktopUpdateManifestUrl(
                AndroidUpdateChannel.Alpha.desktopManifestUrl(),
                AndroidUpdateChannel.Nightly,
            ),
        )
    }
}
