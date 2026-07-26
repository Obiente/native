package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ProjectNewsAndUpdatesTest {
    private fun fixtureImage() = ProjectNewsImage(
        url = "https://nc-native.obiente.dev/screenshots/mobile-home.png",
        alt = "A fixture-only native app screen",
        width = 1080,
        height = 1920,
        sha256 = "f".repeat(64),
    )

    private fun feedOf(vararg articles: ProjectNewsArticle): ProjectNewsFeed {
        val entries = articles.toList()
        return ProjectNewsFeed(
            schemaVersion = 1,
            feedRevision = projectNewsFeedRevision(entries),
            entries = entries,
        )
    }

    @Test
    fun nativeNewsParserAcceptsOnlyTheVersionedCanonicalBoundedFeed() {
        val bodyMarkdown = "News is rendered with native Compose components."
        val article = ProjectNewsArticle(
            id = "native-news",
            title = "Native project news",
            description = "A fixture-only development update.",
            publishedDate = "2026-07-24",
            lastUpdated = "2026-07-25",
            tags = listOf("native UI"),
            bodyMarkdown = bodyMarkdown,
            webUrl = "https://nc-native.obiente.dev/news/native-news/",
            contentSha256 = publicContentSha256(bodyMarkdown.encodeToByteArray()),
            image = fixtureImage(),
        )
        val bytes = Json.encodeToString(feedOf(article)).encodeToByteArray()

        assertEquals(article, parseProjectNewsFeed(bytes).entries.single())
        val multilineBody = "A heading\n\nA Markdown paragraph."
        val multilineArticle = article.copy(
            bodyMarkdown = multilineBody,
            contentSha256 = publicContentSha256(multilineBody.encodeToByteArray()),
        )
        assertEquals(
            multilineArticle,
            parseProjectNewsFeed(
                Json.encodeToString(
                    feedOf(multilineArticle),
                ).encodeToByteArray(),
            ).entries.single(),
        )
        assertFailsWith<IllegalArgumentException> {
            parseProjectNewsFeed(
                Json.encodeToString(
                    feedOf(article.copy(webUrl = "https://other.invalid/news/native-news/")),
                ).encodeToByteArray(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseProjectNewsFeed(ByteArray(MAX_PROJECT_NEWS_FEED_BYTES + 1))
        }
        assertFailsWith<IllegalArgumentException> {
            parseProjectNewsFeed(
                Json.encodeToString(
                    feedOf(article.copy(bodyMarkdown = "Invalid\u0000Markdown")),
                ).encodeToByteArray(),
            )
        }
        listOf(
            article.copy(bodyMarkdown = "${article.bodyMarkdown} Corrupted."),
            article.copy(bodyMarkdown = article.bodyMarkdown.dropLast(1)),
            article.copy(contentSha256 = "0".repeat(64)),
        ).forEach { corruptedArticle ->
            assertFailsWith<IllegalArgumentException> {
                parseProjectNewsFeed(
                    Json.encodeToString(
                        feedOf(corruptedArticle),
                    ).encodeToByteArray(),
                )
            }
        }
    }

    @Test
    fun nativeNewsHashUsesExactUtf8BytesAndLowercaseHex() {
        val bodyMarkdown = "Café updates\n\nNative cloud news."
        val expected = "1ed3e899c261b24e48e36cf15a890d5c106c1141a22ee1e800f26ca881bd7f1a"
        assertEquals(expected, publicContentSha256(bodyMarkdown.encodeToByteArray()))

        val article = ProjectNewsArticle(
            id = "utf8-news",
            title = "UTF-8 news",
            description = "A non-ASCII fixture.",
            publishedDate = "2026-07-25",
            tags = emptyList(),
            bodyMarkdown = bodyMarkdown,
            webUrl = "https://nc-native.obiente.dev/news/utf8-news/",
            contentSha256 = expected,
            image = fixtureImage(),
        )
        val feed = feedOf(article)
        assertEquals(
            "59f390aea5824aaccc91e7ee81a1cd7130dbda746ae28312a9fad4aecd55c1d3",
            feed.feedRevision,
        )

        assertEquals(
            article,
            parseProjectNewsFeed(Json.encodeToString(feed).encodeToByteArray()).entries.single(),
        )
        assertEquals(article.image, projectNewsArticlePresentation(article).heroImage)
        listOf(
            article.copy(title = "Changed title"),
            article.copy(publishedDate = "2026-07-24"),
            article.copy(tags = listOf("changed")),
        ).forEach { changedArticle ->
            assertFailsWith<IllegalArgumentException> {
                parseProjectNewsFeed(
                    Json.encodeToString(feed.copy(entries = listOf(changedArticle))).encodeToByteArray(),
                )
            }
        }
    }

    @Test
    fun prereleaseParserMatchesTheStableChannelPointerContract() {
        val metadataUrl = AndroidUpdateChannel.Alpha.manifestUrl()
        val release = AndroidDirectRelease(
            schemaVersion = 1,
            channel = "prerelease-v1",
            versionName = "0.1.0-alpha.1",
            versionCode = 1,
            packageName = "dev.obiente.nextcloudnative",
            minimumAndroidSdk = 26,
            apkUrl =
                "https://github.com/Obiente/nc-native/releases/download/v0.1.0-alpha.1/" +
                    "nextcloud-native-0.1.0-alpha.1-android.apk",
            apkSize = 123_456,
            apkSha256 = "a".repeat(64),
            signingCertificateSha256Digests = listOf("b".repeat(64)),
            releaseNotesUrl =
                "https://github.com/Obiente/nc-native/releases/tag/v0.1.0-alpha.1",
        )
        val encoded = Json.encodeToString(release).encodeToByteArray()
        val immutableMetadataUrl =
            "https://github.com/Obiente/nc-native/releases/download/" +
                "v0.1.0-alpha.1/update-manifest.json"

        assertEquals(release, parseAndroidDirectRelease(encoded, metadataUrl))
        assertEquals(release, parseAndroidDirectRelease(encoded, immutableMetadataUrl))
        assertTrue(isNewerAndroidRelease(0, release))
        assertFalse(isNewerAndroidRelease(1, release))
        assertTrue(isCanonicalAndroidPrereleaseManifestUrl(metadataUrl))
        assertFailsWith<IllegalArgumentException> {
            validateAndroidDirectRelease(
                release.copy(apkUrl = "https://downloads.invalid/nc-native.apk"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validateAndroidDirectRelease(
                release.copy(signingCertificateSha256Digests = listOf("unknown")),
            )
        }
        listOf(
            "https://github.com/Obiente/nc-native/releases/download/v0.1.0-alpha.1/../update.apk",
            "https://github.com/Obiente/nc-native/releases/download/v0.1.0-alpha.1/%2e%2e.apk",
            "https://github.com/Obiente/nc-native/releases/download/v0.1.0-alpha.1/update%2Fbad.apk",
            "https://github.com/Obiente/nc-native/releases/download/v0.1.0-alpha.1//update.apk",
            "https://github.com/Obiente/nc-native/releases/download/v0.1.0-alpha.1/update.apk?x=1",
            "https://github.com/Obiente/nc-native/releases/download/v0.1.0-alpha.1/update.zip",
        ).forEach { invalidUrl ->
            assertFailsWith<IllegalArgumentException>(invalidUrl) {
                validateAndroidDirectRelease(release.copy(apkUrl = invalidUrl))
            }
        }
        listOf(
            "https://github.com/Obiente/nc-native/releases/tag/../private",
            "https://github.com/Obiente/nc-native/releases/tag/v0.1.0-alpha.2",
            "https://github.com/Obiente/nc-native/releases/tag/v0.1.0-alpha.1?source=app",
        ).forEach { invalidUrl ->
            assertFailsWith<IllegalArgumentException>(invalidUrl) {
                validateAndroidDirectRelease(release.copy(releaseNotesUrl = invalidUrl))
            }
        }
    }

    @Test
    fun nightlyParserBindsManifestAndAssetsToTheImmutableTag() {
        val tag = "nightly-20260726-1430-run42-abcdef12"
        val metadataUrl = AndroidUpdateChannel.Nightly.manifestUrl()
        val release = AndroidDirectRelease(
            schemaVersion = 1,
            channel = "nightly-v1",
            versionName = tag,
            versionCode = 20_000_421,
            packageName = "dev.obiente.nextcloudnative",
            minimumAndroidSdk = 26,
            apkUrl =
                "https://github.com/Obiente/nc-native/releases/download/$tag/" +
                    "nextcloud-native-$tag-android.apk",
            apkSize = 123_456,
            apkSha256 = "a".repeat(64),
            signingCertificateSha256Digests = listOf("b".repeat(64)),
            releaseNotesUrl = "https://github.com/Obiente/nc-native/releases/tag/$tag",
        )
        val encoded = Json.encodeToString(release).encodeToByteArray()
        val immutableMetadataUrl =
            "https://github.com/Obiente/nc-native/releases/download/$tag/update-manifest.json"

        assertEquals(release, parseAndroidDirectRelease(encoded, metadataUrl))
        assertEquals(release, parseAndroidDirectRelease(encoded, immutableMetadataUrl))
        assertTrue(isCanonicalAndroidUpdateManifestUrl(metadataUrl))
        assertTrue(isCanonicalAndroidUpdateManifestUrl(immutableMetadataUrl))
        assertFailsWith<IllegalArgumentException> {
            parseAndroidDirectRelease(
                encoded,
                AndroidUpdateChannel.Alpha.manifestUrl(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validateAndroidDirectRelease(release.copy(channel = "stable-v1"))
        }
    }

    @Test
    fun updateChannelPreferencesAcceptStableIdentifiersAndSafelyDefault() {
        assertEquals(
            AndroidUpdateChannel.Alpha,
            parseAndroidUpdateChannel(AndroidUpdateChannel.Alpha.manifestChannel),
        )
        assertEquals(
            AndroidUpdateChannel.Nightly,
            parseAndroidUpdateChannel(AndroidUpdateChannel.Nightly.name),
        )
        assertEquals(AndroidUpdateChannel.Alpha, parseAndroidUpdateChannel(null))
        assertEquals(AndroidUpdateChannel.Alpha, parseAndroidUpdateChannel("stable-v1"))
    }
}
