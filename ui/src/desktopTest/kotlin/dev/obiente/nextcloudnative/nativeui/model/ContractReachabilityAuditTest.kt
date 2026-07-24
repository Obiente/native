package dev.obiente.nextcloudnative.nativeui.model

import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequest
import dev.obiente.nextcloudnative.contracts.OpenApiContractSourceKind
import dev.obiente.nextcloudnative.contracts.SignedAppStoreContractAcquirer
import kotlinx.serialization.json.Json
import kotlin.test.Test

class ContractReachabilityAuditTest {
    @Test
    fun `live signed package reachability audit is sanitized and read only`() {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_REACHABILITY_AUDIT") != "1") return
        val targets = listOf(
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
        val acquirer = SignedAppStoreContractAcquirer()
        targets.forEach { (appId, installedVersion) ->
            val contract = runCatching {
                acquirer.acquire(ContractAcquisitionRequest(appId, "34.0.1", installedVersion))
            }.getOrElse { failure ->
                println("reachability-audit app=$appId outcome=error:${failure::class.simpleName}")
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
                println("reachability-audit app=$appId outcome=compile-error:${failure::class.simpleName}")
                return@forEach
            }
            val rootActionIds = descriptor.planDynamicNavigation()
                .rootDestinations
                .mapTo(mutableSetOf(), DynamicNavigationDestination::actionId)
            val linkedActionIds = descriptor.links.mapNotNull { link ->
                (link.target as? DynamicLinkTarget.Action)?.actionId
            }.toSet()
            val surfacedActionIds = descriptor.layouts.mapNotNull(DynamicLayout::sourceActionId).toSet()
            val stranded = descriptor.actions.filter { action ->
                action.id in surfacedActionIds &&
                    action.id !in rootActionIds &&
                    action.id !in linkedActionIds &&
                    action.binding.requiredInputNames().isNotEmpty()
            }
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
                    "roots=${rootActionIds.size} stranded=${stranded.size} " +
                    "version-placeholders=${embeddedVersions.size} duplicates=${duplicates.size}",
            )
            stranded.forEach { action ->
                println(
                    "reachability-stranded app=$appId action=${action.id} " +
                        "inputs=${action.binding.requiredInputNames().sorted().joinToString(",")}",
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
        }
    }
}

private fun DynamicHttpBinding.requiredInputNames(): Set<String> =
    (pathParameters + queryParameters.filter(HttpParameter::required))
        .mapTo(linkedSetOf(), HttpParameter::name)
