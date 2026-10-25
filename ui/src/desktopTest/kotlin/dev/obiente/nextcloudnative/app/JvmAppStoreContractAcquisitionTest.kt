package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.contracts.AcquisitionCachePolicy
import dev.obiente.nextcloudnative.contracts.ContractAcquisitionOutcome
import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequest
import dev.obiente.nextcloudnative.contracts.ContractUnavailableReason
import dev.obiente.nextcloudnative.contracts.MemoryVerifiedContractCache
import dev.obiente.nextcloudnative.contracts.SignedAppStoreContractAcquirer
import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequestException
import dev.obiente.nextcloudnative.contracts.ContractSourceHttpException
import dev.obiente.nextcloudnative.contracts.ContractSourceTooLargeException
import dev.obiente.nextcloudnative.contracts.ContractSourceVerificationException
import dev.obiente.nextcloudnative.contracts.OpenApiContractSourceKind
import dev.obiente.nextcloudnative.contracts.VerifiedContractCache
import dev.obiente.nextcloudnative.contracts.VerifiedContractKind
import dev.obiente.nextcloudnative.contracts.VerifiedContractRemoval
import dev.obiente.nextcloudnative.contracts.VerifiedOpenApiContract
import dev.obiente.nextcloudnative.contracts.VerifiedContractStore
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class JvmAppStoreContractAcquisitionTest {
    private val request = AppStoreContractRequest("example", "34.0.3", "1.0.0")

    @Test
    fun verifiedContractKeepsItsProvenanceAndKind() {
        for ((source, expectedSource) in listOf(
            OpenApiContractSourceKind.SignedAppPackage to AcquiredOpenApiContractSourceKind.SignedAppPackage,
            OpenApiContractSourceKind.SignedCompatibleAppPackage to
                AcquiredOpenApiContractSourceKind.SignedCompatibleAppPackage,
            OpenApiContractSourceKind.AppStoreLinkedExactGitHubTag to
                AcquiredOpenApiContractSourceKind.AppStoreLinkedExactGitHubTag,
            OpenApiContractSourceKind.AppStoreLinkedCompatibleGitHubTag to
                AcquiredOpenApiContractSourceKind.AppStoreLinkedCompatibleGitHubTag,
        )) {
            for ((kind, expectedKind) in listOf(
                VerifiedContractKind.OpenApi to AcquiredContractKind.OpenApi,
                VerifiedContractKind.VerifiedReadRoutes to AcquiredContractKind.VerifiedReadRoutes,
                VerifiedContractKind.OpenApiWithVerifiedReadRoutes to AcquiredContractKind.OpenApiWithVerifiedReadRoutes,
            )) {
                val outcome = acquireAppStoreContract(request) { _, _ ->
                    ContractAcquisitionOutcome.Acquired(verified(source, kind))
                }
                val acquired = (outcome as AppStoreContractAcquisition.Acquired).contract
                assertEquals(expectedSource, acquired.sourceKind)
                assertEquals(expectedKind, acquired.contractKind)
                assertEquals("1.0.1", acquired.appVersion)
                assertEquals("1.0.0", acquired.contractVersion)
                assertEquals("https://fixture.invalid/example.tar.gz#openapi.json", acquired.sourceUrl)
            }
        }
        for ((reason, expected) in listOf(
            ContractUnavailableReason.NoMatchingRelease to AppStoreContractUnavailableReason.NoMatchingRelease,
            ContractUnavailableReason.SourceTemporarilyUnavailable to
                AppStoreContractUnavailableReason.SourceTemporarilyUnavailable,
            ContractUnavailableReason.NoUsableContract to AppStoreContractUnavailableReason.NoUsableContract,
            ContractUnavailableReason.MalformedSource to AppStoreContractUnavailableReason.MalformedSource,
            ContractUnavailableReason.RecentlyChecked to AppStoreContractUnavailableReason.RecentlyChecked,
            ContractUnavailableReason.UnreadableCatalog to AppStoreContractUnavailableReason.UnreadableCatalog,
        )) {
            assertEquals(
                AppStoreContractAcquisition.Unavailable(expected),
                acquireAppStoreContract(request) { _, _ -> ContractAcquisitionOutcome.Unavailable(reason) },
            )
        }
    }

    @Test
    fun acquisitionFailuresBecomeTypedFailuresWithTheirOriginalCause() {
        for ((failure, expected) in listOf(
            SocketTimeoutException("synthetic") to AppStoreContractFailureKind.Network,
            ContractSourceHttpException(503, "synthetic") to AppStoreContractFailureKind.SourceUnavailable,
            ContractSourceVerificationException("synthetic") to AppStoreContractFailureKind.VerificationFailed,
            ContractAcquisitionRequestException("synthetic") to AppStoreContractFailureKind.InvalidRequest,
            ContractSourceTooLargeException(1, "synthetic") to AppStoreContractFailureKind.SourceUnusable,
            IllegalStateException("synthetic") to AppStoreContractFailureKind.Unexpected,
        )) {
            val typed = assertFailsWith<AppStoreContractAcquisitionException> {
                acquireAppStoreContract(request) { _, _ -> throw failure }
            }
            assertEquals(expected, typed.kind)
            assertSame(failure, typed.cause)
        }
    }

    @Test
    fun runtimeLinkageFailuresAreTypedWithoutExposingTheClassName() {
        for (failure in listOf(
            NoClassDefFoundError("dev.obiente.nextcloudnative.contracts.AppOwnedOpenApiContractKt"),
            ExceptionInInitializerError(IllegalArgumentException("Syntax error near index 3")),
        )) {
            val typed = assertFailsWith<AppStoreContractAcquisitionException> {
                acquireAppStoreContract(request) { _, _ -> throw failure }
            }
            assertEquals(AppStoreContractFailureKind.RuntimeIncompatible, typed.kind)
            assertSame(failure, typed.cause)
            assertFalse("Kt" in typed.message.orEmpty() || "Syntax" in typed.message.orEmpty())
        }
    }

    @Test
    fun cancellationAndUnrelatedErrorsAreNotConverted() {
        val cancelled = CancellationException("stop")
        assertSame(cancelled, assertFailsWith<CancellationException> { acquireAppStoreContract(request) { _, _ -> throw cancelled } })
        val overflow = StackOverflowError()
        assertSame(overflow, assertFailsWith<StackOverflowError> { acquireAppStoreContract(request) { _, _ -> throw overflow } })
    }

    @Test
    fun theRequestAndItsCatalogPolicyCrossTheBoundaryUnchanged() {
        for ((policy, expected) in listOf(
            AppStoreCachePolicy.UseCache to AcquisitionCachePolicy.UseCache,
            AppStoreCachePolicy.Refresh to AcquisitionCachePolicy.Refresh,
        )) {
            var seen: Pair<ContractAcquisitionRequest, AcquisitionCachePolicy>? = null
            acquireAppStoreContract(request.copy(cachePolicy = policy)) { contractRequest, cachePolicy ->
                seen = contractRequest to cachePolicy
                ContractAcquisitionOutcome.Unavailable(ContractUnavailableReason.NoMatchingRelease)
            }
            assertEquals(ContractAcquisitionRequest("example", "34.0.3", "1.0.0") to expected, seen)
        }
    }

    @Test
    fun discardingForgetsOnlyTheExactCachedContract() {
        val cache = MemoryVerifiedContractCache()
        val exact = ContractAcquisitionRequest("example", "34.0.3", "1.0.0")
        val other = ContractAcquisitionRequest("example", "34.0.3", "1.0.1")
        val contract = verified(OpenApiContractSourceKind.SignedAppPackage, VerifiedContractKind.OpenApi)
        cache.store(exact, contract)
        cache.store(other, contract)
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()
        val outcome = acquireAppStoreContract(
            request.copy(cachePolicy = AppStoreCachePolicy.DiscardCachedContract),
            SignedAppStoreContractAcquirer(verifiedContractCache = cache),
            diagnostics::add,
        )
        assertEquals(AppStoreContractAcquisition.Unavailable(AppStoreContractUnavailableReason.NoUsableContract), outcome)
        assertEquals(emptyList<SupportDiagnosticEventDraft>(), diagnostics)
        assertEquals(null, cache.load(exact))
        assertEquals(contract, cache.load(other))
        assertFailsWith<IllegalArgumentException> {
            acquireAppStoreContract(request.copy(cachePolicy = AppStoreCachePolicy.DiscardCachedContract)) { _, _ ->
                error("A discard must not acquire.")
            }
        }
    }

    @Test
    fun anUndeletableCachedContractIsRecordedAsATypedDiagnostic() {
        for (removal in listOf(VerifiedContractRemoval.MarkedInvalid, VerifiedContractRemoval.InvalidatedForProcess)) {
            val cache = object : VerifiedContractCache {
                override fun load(request: ContractAcquisitionRequest) = null
                override fun store(request: ContractAcquisitionRequest, contract: VerifiedOpenApiContract) =
                    VerifiedContractStore.Stored
                override fun remove(request: ContractAcquisitionRequest) = removal
            }
            val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()
            val outcome = acquireAppStoreContract(
                request.copy(cachePolicy = AppStoreCachePolicy.DiscardCachedContract),
                SignedAppStoreContractAcquirer(verifiedContractCache = cache),
                diagnostics::add,
            )
            assertEquals(AppStoreContractAcquisition.Unavailable(AppStoreContractUnavailableReason.NoUsableContract), outcome)
            val diagnostic = diagnostics.single()
            assertEquals(SupportDiagnosticComponent.Cache, diagnostic.component)
            assertEquals("VERIFIED_CONTRACT_DELETION_FAILED", diagnostic.code)
            assertEquals(listOf(SupportDiagnosticFieldDraft("removal", removal.name)), diagnostic.fields)
            assertEquals(null, diagnostic.message)
            assertEquals(null, diagnostic.exception)
        }
    }

    private fun verified(source: OpenApiContractSourceKind, kind: VerifiedContractKind) = VerifiedOpenApiContract(
        appId = "example",
        appVersion = "1.0.1",
        contractVersion = "1.0.0",
        specFile = "openapi.json",
        document = """{"openapi":"3.0.3","paths":{}}""",
        catalogUrl = "https://fixture.invalid/apps.json",
        packageUrl = "https://fixture.invalid/example.tar.gz",
        sourceUrl = "https://fixture.invalid/example.tar.gz#openapi.json",
        sourceKind = source,
        contractKind = kind,
    )
}
