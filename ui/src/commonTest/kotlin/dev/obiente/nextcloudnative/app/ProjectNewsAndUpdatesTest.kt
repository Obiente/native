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
            releaseNotesUrl = "https://nc-native.obiente.dev/news/release-0-2-0/",
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
    }
}
