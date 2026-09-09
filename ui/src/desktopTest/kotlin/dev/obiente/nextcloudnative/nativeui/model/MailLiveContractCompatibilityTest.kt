package dev.obiente.nextcloudnative.nativeui.model

import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequest
import dev.obiente.nextcloudnative.contracts.SignedAppStoreContractAcquirer
import dev.obiente.nextcloudnative.app.DynamicPaginationMode
import dev.obiente.nextcloudnative.app.resolvedDynamicPaginationSpec
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.NativeDatasetContext
import dev.obiente.nextcloudnative.nativeui.runtime.NativeMailMessageActionKind
import dev.obiente.nextcloudnative.nativeui.runtime.nativeMailMessageActionPlan
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MailLiveContractCompatibilityTest {
    @Test
    fun signedMailContractKeepsAccountMailboxMessageNavigation() {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_APPSTORE_TEST") != "1") return
        val contract = assertNotNull(
            SignedAppStoreContractAcquirer().acquire(
                ContractAcquisitionRequest("mail", "34.0.1", "5.10.9"),
            ),
        )
        val descriptor = DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("mail", "Mail", "5.10.9"),
                endpointPolicy = EndpointPolicy(
                    serverOrigin = "https://cloud.example.test",
                    approvedApiPrefixes = listOf(
                        "/apps/mail",
                        "/ocs/v1.php/apps/mail",
                        "/ocs/v2.php/apps/mail",
                        "/index.php/apps/mail",
                    ),
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    documentUrl = "https://apps.nextcloud.com/packages/mail#${contract.specFile}",
                    document = Json.parseToJsonElement(contract.document),
                    trust = OpenApiTrust.nextcloudSignedAppPackage,
                ),
            ),
        )

        val account = descriptor.resources.firstOrNull { it.id.resourceWord() in setOf("account", "accounts") }
        val mailboxes = descriptor.resources.firstOrNull { it.id.resourceWord() in setOf("mailbox", "mailboxes") }
        val messages = descriptor.resources.firstOrNull { it.id.resourceWord() == "messages" }
            ?: descriptor.resources.firstOrNull { it.id.resourceWord() == "message" }
        assertNotNull(account)
        assertNotNull(mailboxes)
        assertNotNull(messages)
        assertTrue(
            descriptor.planDynamicNavigation().rootDestinations.firstOrNull()?.resourceId == account.id,
            "roots=${descriptor.planDynamicNavigation().rootDestinations}",
        )
        val messageList = assertNotNull(
            descriptor.actions.firstOrNull { action ->
                action.intent == ActionIntent.list && action.binding.path.contains("/mailboxes/") &&
                    action.binding.path.endsWith("/messages")
            },
            descriptor.actions.joinToString("\n") { action ->
                "${action.id} ${action.intent} ${action.binding.method} ${action.binding.path}"
            },
        )
        assertTrue(messageList.fallbackActionIds.isNotEmpty())
        val messageFallback = descriptor.actions.first { action ->
            action.id in messageList.fallbackActionIds
        }
        assertTrue(messageFallback.binding.apiRequestHeader)
        val pagination = assertNotNull(descriptor.resolvedDynamicPaginationSpec(messageList.id))
        assertTrue(pagination.mode == DynamicPaginationMode.RecordCursor)
        assertTrue(
            pagination.nextValue(
                nextPageNumber = 2,
                loadedRecordCount = 50,
                lastPage = listOf(
                    NativeRecord("33361", emptyMap(), displayValues = mapOf("dateInt" to "1784748442")),
                ),
            ) == "1784748442",
        )
        val messageDetail = assertNotNull(
            descriptor.layouts.firstOrNull { layout ->
                layout.kind == LayoutKind.detail && layout.resourceId.sameDynamicResourceAs(messageList.resourceId)
            },
        )
        val detailParameters = assertNotNull(descriptor.resolveDynamicRecordReadParameters(
            assertNotNull(messageDetail.sourceActionId),
            DynamicResourceRecordContext(
                resourceId = messageList.resourceId,
                recordId = "33361",
                actionSafeIdentity = true,
            ),
        ))
        assertTrue(detailParameters.values.contains("33361"))
        val messageContext = DynamicResourceRecordContext(
            resourceId = messageList.resourceId,
            recordId = "33361",
            fieldValues = mapOf("id" to "33361"),
            actionSafeIdentity = false,
        )
        val messageFacets = descriptor.planDynamicNavigation(messageContext).contextualChildDestinations
        assertTrue(
            messageFacets.any { destination -> destination.resourceId.resourceWord() == "body" },
            "messageFacets=$messageFacets",
        )
        val secondaryFacetWords = messageFacets
            .filter { destination -> descriptor.isSecondaryTechnicalDestination(messageContext, destination) }
            .mapTo(hashSetOf()) { destination -> destination.resourceId.resourceWord() }
        assertTrue(
            secondaryFacetWords.any { word ->
                word in setOf("dkim", "itineraries", "needstranslation", "raw", "smartreply", "source", "thread")
            },
            "secondaryFacetWords=$secondaryFacetWords messageFacets=$messageFacets",
        )
        assertTrue(
            messageFacets.filter { destination -> destination.resourceId.resourceWord() == "body" }
                .none { destination -> descriptor.isSecondaryTechnicalDestination(messageContext, destination) },
        )
        assertTrue(
            descriptor.actions.any { action ->
                action.binding.method == HttpMethod.POST && action.binding.path.endsWith("/mailboxes/{id}/sync")
            },
        )
        val move = assertNotNull(
            descriptor.actions.firstOrNull { action ->
                action.binding.method == HttpMethod.POST &&
                    action.binding.path.endsWith("/messages/{id}/move")
            },
            descriptor.actions.joinToString("\n") { action ->
                "${action.id} ${action.intent} ${action.binding.method} ${action.binding.path}"
            },
        )
        val schema = descriptor.toNativeAppSchema()
        assertTrue(
            schema.actions.first { action -> action.id == move.id }
                .binding.requiredBodyFieldNames == listOf("destFolderId"),
        )
        val nativeMessages = assertNotNull(schema.resources.firstOrNull { it.id == messages.id })
        val archivePlan = nativeMailMessageActionPlan(
            schema = schema,
            displayedResource = nativeMessages,
            displayedRecord = NativeRecord(
                id = "33361",
                values = mapOf(
                    "subject" to "Contract audit",
                    "from" to "sender@example.test",
                    "body" to "Read-only synthetic record",
                    "accountId" to "7",
                ),
            ),
            context = NativeDatasetContext(
                relatedRecords = mapOf(
                    account.id to listOf(
                        NativeRecord("7", mapOf("id" to "7", "archiveMailboxId" to "18")),
                    ),
                ),
            ),
        ).archive
        assertNotNull(archivePlan)
        assertTrue(archivePlan.kind == NativeMailMessageActionKind.Archive)
        assertTrue(archivePlan.request().action.id == move.id)

        val accountContext = DynamicResourceRecordContext(account.id, "7")
        val accountChildren = descriptor.planDynamicNavigation(accountContext).contextualChildDestinations
        assertTrue(accountChildren.any { it.resourceId == mailboxes.id })

        val mailboxDestination = accountChildren.first { it.resourceId == mailboxes.id }
        assertTrue(
            descriptor.preferredSemanticContextualChild(accountContext)?.actionId == mailboxDestination.actionId,
        )
        val mailboxContext = DynamicResourceRecordContext(
            resourceId = mailboxes.id,
            recordId = "9",
            parameterValues = mailboxDestination.pathParameterValues,
        )
        val mailboxChildren = descriptor.planDynamicNavigation(mailboxContext).contextualChildDestinations
        val diagnostics = descriptor.explainDynamicChildNavigation(
            mailboxContext,
        )
        assertTrue(
            mailboxChildren.any { it.resourceId == messages.id },
            "resources=${descriptor.resources.map(DynamicResource::id)} " +
                "mailboxes=${mailboxes.id} messages=${messages.id} children=$mailboxChildren diagnostics=$diagnostics",
        )
        assertTrue(
            descriptor.preferredSemanticContextualChild(mailboxContext)?.resourceId == messages.id,
        )
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    private fun String.resourceWord(): String = lowercase().substringAfterLast('.').substringAfterLast('-')
}
