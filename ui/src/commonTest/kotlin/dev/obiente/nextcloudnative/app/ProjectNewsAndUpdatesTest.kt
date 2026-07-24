package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ProjectNewsAndUpdatesTest {
    @Test
    fun nativeNewsParserAcceptsOnlyTheVersionedCanonicalBoundedFeed() {
        val article = ProjectNewsArticle(
            id = "native-news",
            title = "Native project news",
            description = "A fixture-only development update.",
            publishedDate = "2026-07-24",
            lastUpdated = "2026-07-25",
            tags = listOf("native UI"),
            bodyMarkdown = "News is rendered with native Compose components.",
            webUrl = "https://nc-native.obiente.dev/news/native-news/",
            contentSha256 = "a".repeat(64),
        )
        val bytes = Json.encodeToString(
            ProjectNewsFeed(
                schemaVersion = 1,
                feedRevision = "b".repeat(64),
                entries = listOf(article),
            ),
        ).encodeToByteArray()

        assertEquals(article, parseProjectNewsFeed(bytes).entries.single())
        val multilineArticle = article.copy(bodyMarkdown = "A heading\n\nA Markdown paragraph.")
        assertEquals(
            multilineArticle,
            parseProjectNewsFeed(
                Json.encodeToString(
                    ProjectNewsFeed(
                        schemaVersion = 1,
                        feedRevision = "b".repeat(64),
                        entries = listOf(multilineArticle),
                    ),
                ).encodeToByteArray(),
            ).entries.single(),
        )
        assertFailsWith<IllegalArgumentException> {
            parseProjectNewsFeed(
                Json.encodeToString(
                    ProjectNewsFeed(
                        schemaVersion = 1,
                        feedRevision = "b".repeat(64),
                        entries = listOf(article.copy(webUrl = "https://other.invalid/news/native-news/")),
                    ),
                ).encodeToByteArray(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseProjectNewsFeed(ByteArray(MAX_PROJECT_NEWS_FEED_BYTES + 1))
        }
        assertFailsWith<IllegalArgumentException> {
            parseProjectNewsFeed(
                Json.encodeToString(
                    ProjectNewsFeed(
                        schemaVersion = 1,
                        feedRevision = "b".repeat(64),
                        entries = listOf(article.copy(bodyMarkdown = "Invalid\u0000Markdown")),
                    ),
                ).encodeToByteArray(),
            )
        }
    }

    @Test
    fun directReleaseParserPinsOriginHashesCertificateAndVersion() {
        val release = AndroidDirectRelease(
            schemaVersion = 1,
            channel = "direct",
            versionName = "0.2.0",
            versionCode = 2,
            apkUrl = "https://nc-native.obiente.dev/releases/android/nc-native-0.2.0.apk",
            apkSize = 12_345,
            apkSha256 = "c".repeat(64),
            signingCertificateSha256 = "d".repeat(64),
            releaseNotesUrl = "https://nc-native.obiente.dev/releases/android/0.2.0/",
        )
        val encoded = Json.encodeToString(release).encodeToByteArray()

        assertEquals(release, parseAndroidDirectRelease(encoded))
        assertTrue(isNewerAndroidRelease(1, release))
        assertFalse(isNewerAndroidRelease(2, release))
        assertFailsWith<IllegalArgumentException> {
            validateAndroidDirectRelease(
                release.copy(apkUrl = "https://downloads.invalid/nc-native.apk"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validateAndroidDirectRelease(
                release.copy(signingCertificateSha256 = "unknown"),
            )
        }
        listOf(
            "https://nc-native.obiente.dev/releases/android/../update.apk",
            "https://nc-native.obiente.dev/releases/android/%2e%2e/update.apk",
            "https://nc-native.obiente.dev/releases/android/update%2Fescaped.apk",
            "https://nc-native.obiente.dev/releases/android//update.apk",
            "https://nc-native.obiente.dev/releases/android/folder\\update.apk",
            "https://nc-native.obiente.dev/releases/android/update.apk?download=1",
            "https://nc-native.obiente.dev/releases/android/update.apk#download",
            "https://nc-native.obiente.dev/releases/android/update.zip",
            "https://nc-native.obiente.dev.evil.invalid/releases/android/update.apk",
        ).forEach { invalidUrl ->
            assertFailsWith<IllegalArgumentException>(invalidUrl) {
                validateAndroidDirectRelease(release.copy(apkUrl = invalidUrl))
            }
        }
        listOf(
            "https://nc-native.obiente.dev/releases/../private/",
            "https://nc-native.obiente.dev/releases/%2e%2e/private/",
            "https://nc-native.obiente.dev/releases/android//0.2.0/",
            "https://nc-native.obiente.dev/releases/android/0.2.0",
            "https://nc-native.obiente.dev/releases/android/0.2.0/?source=app",
        ).forEach { invalidUrl ->
            assertFailsWith<IllegalArgumentException>(invalidUrl) {
                validateAndroidDirectRelease(release.copy(releaseNotesUrl = invalidUrl))
            }
        }
    }
}
