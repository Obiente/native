package dev.obiente.nextcloudnative.nativeui.model

import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequest
import dev.obiente.nextcloudnative.contracts.FileAppStoreCatalogCache
import dev.obiente.nextcloudnative.contracts.FileVerifiedContractCache
import dev.obiente.nextcloudnative.contracts.OpenApiContractSourceKind
import dev.obiente.nextcloudnative.contracts.SignedAppStoreContractAcquirer
import java.io.File
import kotlinx.serialization.json.Json
import kotlin.test.Test

class ContractReachabilityAuditTest {
    @Test
    fun `live signed package reachability audit is sanitized and read only`() {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_REACHABILITY_AUDIT") != "1") return
        val requestedAppIds = System.getenv("NEXTCLOUD_REACHABILITY_APP_IDS")
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.distinct()
            ?.takeIf(List<String>::isNotEmpty)
        val printMutations = System.getenv("NEXTCLOUD_REACHABILITY_PRINT_MUTATIONS") == "1"
        require(requestedAppIds.orEmpty().size <= 500) { "The audit app list is outside the limit." }
        require(requestedAppIds.orEmpty().all { appId -> appId.matches(Regex("^[a-z0-9_]{1,64}$")) }) {
            "The audit app list contains an invalid app ID."
        }
        val targets = requestedAppIds?.map { appId -> appId to null } ?: listOf(
            "deck" to "1.18.2",
            "mail" to "5.10.9",
            "music" to "3.1.1",
            "cookbook" to "0.11.9",
            "tables" to "2.2.0",
            "cospend" to "4.0.2",
            "budget" to "2.39.1",
            "notes" to null,
            "spreed" to null,
            "memories" to null,
        )
        val cacheRoot = File(System.getProperty("user.home"), ".cache/nextcloud-native/contracts")
        val acquirer = SignedAppStoreContractAcquirer(
            catalogCache = FileAppStoreCatalogCache(File(cacheRoot, "catalogs")),
            verifiedContractCache = FileVerifiedContractCache(File(cacheRoot, "verified")),
        )
        targets.forEach { (appId, installedVersion) ->
            val contract = runCatching {
                acquirer.acquire(ContractAcquisitionRequest(appId, "34.0.1", installedVersion))
            }.getOrElse { failure ->
                val reason = failure.message.orEmpty()
                    .replace(Regex("\\s+"), " ")
                    .replace(Regex("https?://[^ ]+"), "<url>")
                    .take(240)
                println(
                    "reachability-audit app=$appId outcome=error:${failure::class.simpleName} " +
                        "reason=$reason",
                )
                return@forEach
            }
            if (contract == null) {
                println("reachability-audit app=$appId outcome=metadata-only")
                return@forEach
            }
            val descriptor = runCatching {
                DynamicAppDescriptorCompiler().compile(
                    DynamicDiscoveryInput(
                        app = AppIdentity(appId, appId, contract.appVersion),
                        endpointPolicy = EndpointPolicy(
                            serverOrigin = "https://cloud.example.test",
                            approvedApiPrefixes = listOf(
                                "/apps/$appId",
                                "/index.php/apps/$appId",
                                "/ocs/v1.php/apps/$appId",
                                "/ocs/v2.php/apps/$appId",
                                "/ocs/v2.php/cloud/capabilities",
                            ),
                        ),
                        advertisedOpenApi = AdvertisedOpenApi(
                            documentUrl = contract.sourceUrl,
                            document = Json.parseToJsonElement(contract.document),
                            trust = when (contract.sourceKind) {
                                OpenApiContractSourceKind.SignedAppPackage ->
                                    OpenApiTrust.nextcloudSignedAppPackage
                                OpenApiContractSourceKind.SignedCompatibleAppPackage ->
                                    OpenApiTrust.nextcloudSignedCompatibleAppPackage
                                OpenApiContractSourceKind.AppStoreLinkedExactGitHubTag ->
                                    OpenApiTrust.appStoreLinkedExactGitHubTag
                                OpenApiContractSourceKind.AppStoreLinkedCompatibleGitHubTag ->
                                    OpenApiTrust.appStoreLinkedCompatibleGitHubTag
                            },
                        ),
                    ),
                )
            }.getOrElse { failure ->
                if (System.getenv("NEXTCLOUD_REACHABILITY_FAIL_ON_COMPILE_ERROR") == "1") {
                    throw failure
                }
                val reason = failure.message.orEmpty()
                    .replace(Regex("\\s+"), " ")
                    .replace(Regex("https?://[^ ]+"), "<url>")
                    .take(240)
                println(
                    "reachability-audit app=$appId outcome=compile-error:${failure::class.simpleName} " +
                        "reason=$reason",
                )
                return@forEach
            }
            val rootActionIds = descriptor.planDynamicNavigation()
                .rootDestinations
                .mapTo(mutableSetOf(), DynamicNavigationDestination::actionId)
            val operationAudit = descriptor.auditDynamicOperationSurfaces()
            val embeddedVersions = descriptor.actions.filter { action ->
                action.binding.pathParameters.any { parameter ->
                    parameter.name.equals("version", true) ||
                        parameter.name.equals("apiVersion", true)
                }
            }
            val duplicates = descriptor.actions.groupBy { action ->
                action.binding.method to action.binding.path
            }.filterValues { actions -> actions.size > 1 }
            println(
                "reachability-audit app=$appId outcome=success kind=${contract.contractKind} " +
                    "actions=${descriptor.actions.size} layouts=${descriptor.layouts.size} " +
                    "roots=${rootActionIds.size} unsurfaced=${operationAudit.unsurfacedActionIds.size} " +
                    "version-placeholders=${embeddedVersions.size} duplicates=${duplicates.size}",
            )
            println(
                "reachability-surfaces app=$appId values=" +
                    DynamicOperationSurface.entries.joinToString(",") { surface ->
                        "${surface.name}:${operationAudit.counts[surface] ?: 0}"
                    },
            )
            if (printMutations) {
                descriptor.actions
                    .filter { action -> action.binding.method != HttpMethod.GET }
                    .sortedBy(DynamicAction::id)
                    .forEach { action ->
                        println(
                            "reachability-mutation app=$appId action=${action.id} " +
                                "intent=${action.intent} method=${action.binding.method} " +
                                "surface=${operationAudit.surfacesByActionId[action.id]} " +
                                "resource=${action.resourceId} path=${action.binding.path}",
                        )
                    }
            }
            if (appId == "budget") {
                println(
                    "reachability-roots app=budget resources=" +
                        descriptor.planDynamicNavigation().rootDestinations
                            .map(DynamicNavigationDestination::resourceId)
                            .sorted()
                            .joinToString(","),
                )
                println(
                    "reachability-intents app=budget values=" +
                        descriptor.actions.groupingBy(DynamicAction::intent).eachCount()
                            .entries.sortedBy { it.key.name }
                            .joinToString(",") { (intent, count) -> "${intent.name}:$count" },
                )
            }
            operationAudit.unsurfacedActionIds.forEach { actionId ->
                val action = descriptor.actions.single { candidate -> candidate.id == actionId }
                println(
                    "reachability-unsurfaced app=$appId action=$actionId " +
                        "intent=${action.intent} method=${action.binding.method} path=${action.binding.path}",
                )
            }
            embeddedVersions.forEach { action ->
                println("reachability-version app=$appId action=${action.id} path=${action.binding.path}")
            }
            duplicates.forEach { (binding, actions) ->
                println(
                    "reachability-duplicate app=$appId method=${binding.first} path=${binding.second} " +
                        "actions=${actions.map(DynamicAction::id).sorted().joinToString(",")}",
                )
            }
            if (System.getenv("NEXTCLOUD_REACHABILITY_FAIL_ON_UNSURFACED") == "1") {
                check(operationAudit.unsurfacedActionIds.isEmpty()) {
                    "$appId has unsurfaced dynamic operations: " +
                        operationAudit.unsurfacedActionIds.joinToString(",")
                }
            }
        }
    }
}
