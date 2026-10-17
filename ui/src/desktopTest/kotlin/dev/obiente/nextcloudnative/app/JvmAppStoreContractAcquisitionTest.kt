package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.contracts.CatalogPolicy
import dev.obiente.nextcloudnative.contracts.ContractAcquisitionOutcome
import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequest
import dev.obiente.nextcloudnative.contracts.ContractUnavailableReason
import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequestException
import dev.obiente.nextcloudnative.contracts.ContractSourceHttpException
import dev.obiente.nextcloudnative.contracts.ContractSourceVerificationException
import dev.obiente.nextcloudnative.contracts.OpenApiContractSourceKind
import dev.obiente.nextcloudnative.contracts.VerifiedContractKind
import dev.obiente.nextcloudnative.contracts.VerifiedOpenApiContract
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
            AppStoreCatalogPolicy.UseCache to CatalogPolicy.UseCache,
            AppStoreCatalogPolicy.RefreshCatalog to CatalogPolicy.Refresh,
        )) {
            var seen: Pair<ContractAcquisitionRequest, CatalogPolicy>? = null
            acquireAppStoreContract(request.copy(catalogPolicy = policy)) { contractRequest, catalogPolicy ->
                seen = contractRequest to catalogPolicy
                ContractAcquisitionOutcome.Unavailable(ContractUnavailableReason.NoMatchingRelease)
            }
            assertEquals(ContractAcquisitionRequest("example", "34.0.3", "1.0.0") to expected, seen)
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
