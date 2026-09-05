package dev.obiente.nextcloudnative.app

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReleaseRepositoryCompatibilityTest {
    private val tag = "nightly-20260731-1543-run358-02200472"
    private val channel = AndroidUpdateChannel.Nightly

    private fun android(repository: String) = AndroidDirectRelease(
        schemaVersion = 1, channel = channel.manifestChannel,
        versionName = tag, versionCode = 20_002_921,
        packageName = "dev.obiente.nextcloudnative", minimumAndroidSdk = 26,
        apkUrl = "https://github.com/$repository/releases/download/$tag/app.apk",
        apkSize = 123_456, apkSha256 = "a".repeat(64),
        signingCertificateSha256Digests = listOf("b".repeat(64)),
        releaseNotesUrl = "https://github.com/$repository/releases/tag/$tag",
    )

    private fun desktop(repository: String) = DesktopUpdateManifest(
        schemaVersion = 1, channel = channel.manifestChannel,
        versionName = tag, versionCode = 20_002_921, packageVersion = "1.0.2921",
        releaseNotesUrl = "https://github.com/$repository/releases/tag/$tag",
        assets = listOf(DesktopUpdateAsset(
            platform = "linux", format = "deb", architecture = "x86_64",
            url = "https://github.com/$repository/releases/download/$tag/app.deb",
            size = 123_456, sha256 = "a".repeat(64),
        )),
    )

    @Test
    fun immutableAndPointerManifestsAcceptBothRepositoriesAndPreserveAssetUrls() {
        for (repository in listOf("Obiente/nc-native", "Obiente/native", "obiente/native")) {
            for (manifestTag in listOf(tag, "channel-nightly")) {
                val root = "https://github.com/$repository/releases/download/$manifestTag/"
                val android = android(repository)
                assertEquals(android, parseAndroidDirectRelease(
                    Json.encodeToString(android).encodeToByteArray(),
                    root + "update-manifest.json", channel,
                ))
                val desktop = desktop(repository)
                val parsed = parseDesktopDirectRelease(
                    Json.encodeToString(desktop).encodeToByteArray(),
                    root + "desktop-update-manifest.json", channel, "linux", "deb", "x86_64",
                )
                assertEquals(desktop.assets.single(), parsed.asset)
                assertEquals(desktop.releaseNotesUrl, parsed.releaseNotesUrl)
            }
        }
    }

    @Test
    fun legacyRepositorySupportDoesNotAcceptOtherOriginsOrNoncanonicalPaths() {
        val release = android("Obiente/nc-native")
        val manifest = desktop("Obiente/nc-native")
        val root = "https://github.com/Obiente/nc-native/releases/download/$tag/"
        for (url in listOf(
            root.replace("github.com", "github.com.example.org") + "app.apk",
            root.replace("Obiente/nc-native", "other/nc-native") + "app.apk",
            root.replace("nc-native/", "nc-native-extra/") + "app.apk",
            root.replace("https:", "http:") + "app.apk",
            root + "../app.apk", root + "%2e%2e/app.apk", root + "app.apk?download=1",
        )) {
            assertFailsWith<IllegalArgumentException>(url) {
                validateAndroidDirectRelease(release.copy(apkUrl = url), channel)
            }
            assertFailsWith<IllegalArgumentException>(url) {
                validateDesktopUpdateManifest(
                    manifest.copy(assets = listOf(manifest.assets.single().copy(url = url.replace(".apk", ".deb")))),
                    channel, channel.desktopManifestUrl(),
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            validateAndroidDirectRelease(release.copy(releaseNotesUrl = release.releaseNotesUrl + "/extra"), channel)
        }
        assertFailsWith<IllegalArgumentException> {
            parseAndroidDirectRelease(Json.encodeToString(release).encodeToByteArray(),
                root + "update-manifest.json?token=example", channel)
        }
    }
}
