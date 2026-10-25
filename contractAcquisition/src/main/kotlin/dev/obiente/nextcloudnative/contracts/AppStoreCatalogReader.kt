package dev.obiente.nextcloudnative.contracts

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Reads one app's releases from an App Store catalog, through the catalog cache. Automatic
 * acquisition reuses a cached catalog; an explicit refresh downloads it again when
 * [CatalogRefreshThrottle] allows, and a readable download replaces the cached copy.
 */
internal class AppStoreCatalogReader(
    private val httpClient: OkHttpClient,
    private val catalogCache: AppStoreCatalogCache,
    private val refreshThrottle: CatalogRefreshThrottle,
    private val maximumCatalogBytes: Long,
    private val requireHttps: Boolean,
) {
    fun read(
        catalogUrl: String,
        appId: String,
        cachePolicy: AcquisitionCachePolicy,
    ): CatalogReleases {
        requireSecureUrl(catalogUrl)
        // A refresh only bypasses the cached copy; the copy stays until a readable catalog replaces it.
        val refreshAllowed = cachePolicy == AcquisitionCachePolicy.Refresh && refreshThrottle.tryRefresh(catalogUrl)
        catalogCache.load(catalogUrl)?.takeUnless { refreshAllowed }?.let { cached ->
            return parseReleases(cached.decodeToString(), appId, catalogUrl, requireOfficialGitHubHost = requireHttps)
                .copy(source = CatalogSource.Cached)
        }
        // A retry inside the refresh window never reaches the App Store, even without a cached copy.
        if (cachePolicy == AcquisitionCachePolicy.Refresh && !refreshAllowed) {
            return CatalogReleases(emptyList(), source = CatalogSource.RefreshRefused)
        }
        val response = httpClient.newCall(Request.Builder().url(catalogUrl).get().build()).execute()
        val bytes = response.use { catalogResponse ->
            requireSuccessfulSource(catalogResponse) {
                "Loading the Nextcloud App Store catalog failed (HTTP ${catalogResponse.code})."
            }
            requireSecureResponse(catalogResponse)
            catalogResponse.readBodyLimited(maximumCatalogBytes)
        }
        // An unreadable document is not cached, so checking again downloads the catalog again.
        return parseReleases(bytes.decodeToString(), appId, catalogUrl, requireOfficialGitHubHost = requireHttps)
            .also { parsed -> if (parsed.documentReadable) catalogCache.storeBestEffort(catalogUrl, bytes) }
    }

    private fun requireSecureUrl(url: String) {
        if (requireHttps) requireVerifiedSource(url.startsWith("https://")) { "Contract sources must use HTTPS." }
    }

    private fun requireSecureResponse(response: Response) {
        if (requireHttps) requireVerifiedSource(response.request.url.isHttps) { "A contract request left HTTPS." }
    }
}
