package dev.obiente.nextcloudnative.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Contract results compose with catalog publication: none rests on a catalog a newer download replaced. */
class CatalogEvidenceFenceTest {
    private val request = ContractAcquisitionRequest("cospend", "34.0.3", "4.0.2")
    private val catalogs = CatalogPublicationFence()
    private val fence = ContractPublicationFence(catalogs)
    private val older = CatalogObservation.of(URL, "[older]".encodeToByteArray())
    private val newer = CatalogObservation.of(URL, "[newer]".encodeToByteArray())
    private val noRelease = ContractAcquisitionOutcome.Unavailable(ContractUnavailableReason.NoMatchingRelease)

    @Test
    fun `a result read from a catalog that was replaced is not accepted, so the acquisition runs again`() {
        catalogs.publish(older, catalogs.start(URL)) {}
        catalogs.publish(newer, catalogs.start(URL)) {}
        val published = mutableListOf<String>()

        assertNull(fence.publish(request, fence.start(request), contract("stale"), listOf(older)) { published += it.document })
        assertNull(fence.complete(request, fence.start(request), noRelease, listOf(older)))
        assertEquals(emptyList<String>(), published)
        // A transient ending proves nothing and fences nothing, whatever it read.
        val transient = ContractAcquisitionOutcome.Unavailable(ContractUnavailableReason.UnreadableCatalog)
        assertEquals(transient, fence.complete(request, fence.start(request), transient, listOf(older)))
        // Identical content downloaded again is still current.
        catalogs.publish(CatalogObservation.of(URL, "[newer]".encodeToByteArray()), catalogs.start(URL)) {}
        assertEquals(contract("current"), acquired(fence.publish(request, fence.start(request), contract("current"), listOf(newer)) {}))
    }

    @Test
    fun `a refresh that read the newer catalog outranks a later acquisition that read the older one`() {
        catalogs.publish(older, catalogs.start(URL)) {}
        val refresh = fence.start(request)
        val automatic = fence.start(request)
        // The automatic acquisition read the cached older catalog and published first.
        assertEquals(contract("obsolete"), acquired(fence.publish(request, automatic, contract("obsolete"), listOf(older)) {}))
        // The refresh's newer download then replaced the catalog and found no release.
        catalogs.publish(newer, catalogs.start(URL)) {}
        assertEquals(noRelease, fence.complete(request, refresh, noRelease, listOf(newer)))
        // Automatic discovery reports the refresh's result, not the contract from the older catalog.
        assertEquals(noRelease, fence.serveCached(request, fence.start(request), contract("obsolete")))
    }

    @Test
    fun `a superseded result is still superseded while the newer result rests on current catalogs`() {
        catalogs.publish(newer, catalogs.start(URL)) {}
        val olderGeneration = fence.start(request)
        fence.complete(request, fence.start(request), noRelease, listOf(newer))
        assertEquals(noRelease, fence.publish(request, olderGeneration, contract("older"), listOf(newer)) {})

        // An invalidation rests on no catalog, so it is never outranked this way.
        val beforeInvalidation = fence.start(request)
        fence.invalidate(request) {}
        assertEquals(
            ContractAcquisitionOutcome.Unavailable(ContractUnavailableReason.NoUsableContract),
            fence.publish(request, beforeInvalidation, contract("older"), listOf(newer)) {},
        )
    }

    private fun acquired(outcome: ContractAcquisitionOutcome?) =
        (outcome as? ContractAcquisitionOutcome.Acquired)?.contract

    private fun contract(document: String) = VerifiedOpenApiContract(
        appId = "cospend",
        appVersion = "4.0.2",
        contractVersion = "4.0.2",
        specFile = "openapi.json",
        document = document,
        catalogUrl = URL,
        packageUrl = "https://fixture.invalid/cospend.tar.gz",
        sourceUrl = "https://fixture.invalid/cospend.tar.gz#openapi.json",
        sourceKind = OpenApiContractSourceKind.SignedAppPackage,
        contractKind = VerifiedContractKind.OpenApi,
    )

    private companion object {
        const val URL = "https://fixture.invalid/api/v1/platform/34.0.3/apps.json"
    }
}
