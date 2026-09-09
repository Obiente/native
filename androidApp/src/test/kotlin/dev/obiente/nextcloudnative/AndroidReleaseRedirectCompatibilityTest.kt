package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class AndroidReleaseRedirectCompatibilityTest {
    @Test
    fun canonicalNewsRequestsDoNotRequireAReleaseAssetUrl() {
        for (url in listOf("https://nati.ve/news-feed-v1.json", "https://nati.ve/screenshots/mobile-home.png")) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                assertEquals(url, chain.request().url.toString())
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("fixture").body("fixture".toResponseBody()).build()
            }.build()
            executeWithTrustedGitHubReleaseRedirect(client, Request.Builder().url(url).build()).use {
                assertEquals(200, it.code)
            }
        }
    }

    @Test
    fun legacyAssetsUseCanonicalGithubBeforeTheTrustedStorageRedirect() {
        for (repository in listOf("Obiente/nc-native", "Obiente/native", "obiente/native")) {
            val observed = mutableListOf<String>()
            val storage = "https://release-assets.githubusercontent.com/fixture/package.apk?sig=synthetic"
            val client = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
                val request = chain.request()
                observed += request.url.toString()
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(if (observed.size == 1) 302 else 200).message("fixture")
                    .header("Location", storage).body("fixture".toResponseBody()).build()
            }.build()
            executeWithTrustedGitHubReleaseRedirect(client, Request.Builder()
                .url("https://github.com/$repository/releases/download/v1/package.apk").build()).use {
                assertEquals(200, it.code)
            }
            assertEquals(listOf("https://github.com/obiente/native/releases/download/v1/package.apk", storage), observed)
        }
    }

    @Test
    fun canonicalizingTheSourceDoesNotTrustAnArbitraryRedirectDestination() {
        val client = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(302).message("fixture").header("Location", "https://downloads.invalid/package.apk")
                .body("".toResponseBody()).build()
        }.build()
        assertFailsWith<IllegalStateException> {
            executeWithTrustedGitHubReleaseRedirect(client, Request.Builder()
                .url("https://github.com/Obiente/nc-native/releases/download/v1/package.apk").build()).close()
        }
    }
}
