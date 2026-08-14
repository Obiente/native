package dev.obiente.nextcloudnative.nativeui.model

import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequest
import dev.obiente.nextcloudnative.contracts.OpenApiContractSourceKind
import dev.obiente.nextcloudnative.contracts.SignedAppStoreContractAcquirer
import dev.obiente.nextcloudnative.contracts.VerifiedContractKind
import dev.obiente.nextcloudnative.nativeui.runtime.nativeRecordActions
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChoresLiveContractCompatibilityTest {
    @Test
    fun `signed Chores routes expose the audited team chore and completion workflows`() {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_APPSTORE_TEST") != "1") return
        val contract = assertNotNull(
            SignedAppStoreContractAcquirer().acquire(
                ContractAcquisitionRequest("chores", "34.0.1", "0.1.0"),
            ),
        )
        assertEquals(OpenApiContractSourceKind.SignedAppPackage, contract.sourceKind)
        assertEquals(VerifiedContractKind.VerifiedReadRoutes, contract.contractKind)

        val contractDocument = Json.parseToJsonElement(contract.document)
        val descriptor = DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("chores", "Chores", "0.1.0"),
                endpointPolicy = EndpointPolicy(
                    serverOrigin = "https://cloud.example.test",
                    approvedApiPrefixes = listOf("/apps/chores"),
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    documentUrl = contract.sourceUrl,
                    document = contractDocument,
                    trust = OpenApiTrust.nextcloudSignedAppPackage,
                ),
            ),
        )
        val teamRead = descriptor.action(HttpMethod.GET, "/apps/chores/api/v1.0/team")
        val choreRead = descriptor.action(HttpMethod.GET, "/apps/chores/api/v1.0/team/{teamId}/chores")
        val workRead = descriptor.action(HttpMethod.GET, "/apps/chores/api/v1.0/team/{teamId}/work")
        val invitationsRead = descriptor.action(
            HttpMethod.GET,
            "/apps/chores/api/v1.0/account/invites",
        )
        val choreDelete = descriptor.action(
            HttpMethod.DELETE,
            "/apps/chores/api/v1.0/team/{teamId}/chores/{choreId}",
        )
        val createTeam = descriptor.action(HttpMethod.POST, "/apps/chores/api/v1.0/team")
        val inviteMember = descriptor.action(
            HttpMethod.POST,
            "/apps/chores/api/v1.0/team/{teamId}/invites",
        )
        val acceptInvitation = descriptor.action(
            HttpMethod.POST,
            "/apps/chores/api/v1.0/account/invites/accept",
        )
        val createChore = descriptor.action(
            HttpMethod.POST,
            "/apps/chores/api/v1.0/team/{teamId}/chores",
        )
        val editChore = descriptor.action(
            HttpMethod.PATCH,
            "/apps/chores/api/v1.0/team/{teamId}/chores/{choreId}",
        )
        val completeChore = descriptor.action(
            HttpMethod.POST,
            "/apps/chores/api/v1.0/team/{teamId}/work",
        )
        val removeMember = descriptor.action(
            HttpMethod.DELETE,
            "/apps/chores/api/v1.0/team/{teamId}/members/{userIdToRemove}",
        )

        assertEquals(ActionIntent.list, choreRead.intent)
        assertEquals(ActionIntent.list, workRead.intent)
        assertEquals(ActionIntent.create, createTeam.intent)
        assertEquals(ActionIntent.create, createChore.intent)
        assertEquals(ActionIntent.update, editChore.intent)
        assertEquals(ActionIntent.execute, acceptInvitation.intent)
        assertTrue("id" in teamRead.responseFieldIds, "team fields=${teamRead.responseFieldIds}")
        assertTrue("id" in choreRead.responseFieldIds, "chore fields=${choreRead.responseFieldIds}")
        assertTrue(
            setOf("id", "work_time", "chore_id", "member").all(workRead.responseFieldIds::contains),
            "work fields=${workRead.responseFieldIds}",
        )
        assertTrue(
            setOf("inviteId", "teamId", "teamName", "userId")
                .all(invitationsRead.responseFieldIds::contains),
            "invitation fields=${invitationsRead.responseFieldIds}",
        )
        assertTrue(listOf(createTeam, inviteMember, acceptInvitation, createChore, editChore, completeChore).all {
            it.binding.body != null && it.provenance.any { evidence ->
                evidence.kind == ProvenanceKind.verifiedAppPackage
            }
        })
        assertEquals(ActionRisk.destructive, removeMember.risk)
        assertTrue(removeMember.requiresConfirmation)
        assertEquals(setOf("teamId", "userIdToRemove"), removeMember.binding.pathParameters.mapTo(linkedSetOf()) { it.name })
        assertEquals(ActionRisk.destructive, choreDelete.risk)
        assertTrue(choreDelete.requiresConfirmation)
        assertTrue(choreDelete.binding.body == null)
        assertEquals(setOf("teamId", "choreId"), choreDelete.binding.pathParameters.mapTo(linkedSetOf()) { it.name })

        val nativeSchema = descriptor.toNativeAppSchema()
        val nativeChores = nativeSchema.resources.single { resource -> resource.id == choreRead.resourceId }
        val assignee = assertNotNull(
            nativeChores.fields.singleOrNull { field -> field.id == "assignee" },
            "chore fields=${nativeChores.fields.map { field -> field.id to field.kind }}; " +
                "edit resource=${editChore.resourceId}; read resource=${choreRead.resourceId}",
        )
        assertEquals(FieldKind.userReference, assignee.kind)
        val createPlan = nativeRecordActions(
            schema = nativeSchema,
            resource = nativeChores,
            navigationContext = mapOf("teamId" to "opaque-team"),
        ).create
        assertNotNull(
            createPlan,
            "fields=${nativeChores.fields.map { field -> field.id to field.kind }}; " +
                "actionFields=${nativeSchema.actions.single { it.id == createChore.id }.binding.bodyFieldNames}",
        )
        val choreInputs = assertNotNull(createPlan.fields.single().repeatableObjectInput).fields
        val repeatInput = choreInputs.single { field -> field.id == "repeat" }
        assertEquals("Does not repeat", repeatInput.enumLabels?.get("s:1:-"))
        assertEquals("Every week", repeatInput.enumLabels?.get("w:1"))
        assertEquals("Every year", repeatInput.enumLabels?.get("m:12"))
        val authorizedChores = nativeChores.copy(
            fields = nativeChores.fields + FieldSpec(
                id = "canEdit",
                label = "Can edit",
                kind = FieldKind.boolean,
                required = false,
                readOnly = true,
            ),
        )
        val authorizedSchema = nativeSchema.copy(
            resources = nativeSchema.resources.map { resource ->
                if (resource.id == authorizedChores.id) authorizedChores else resource
            },
        )
        val editPlan = assertNotNull(
            nativeRecordActions(
                schema = authorizedSchema,
                resource = authorizedChores,
                record = dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord(
                    id = "7",
                    values = mapOf(
                        "name" to "Clean the kitchen",
                        "repeat" to "w:1",
                        "canEdit" to "true",
                    ),
                ),
                navigationContext = mapOf("teamId" to "opaque-team"),
            ).edit,
        )
        val editRepeatInput = editPlan.fields.single { field -> field.id == "repeat" }
        assertEquals("Every week", editRepeatInput.enumLabels?.get("w:1"))
        assertEquals("Every year", editRepeatInput.enumLabels?.get("m:12"))

        val root = descriptor.planDynamicNavigation().rootDestinations
        assertTrue(
            root.any { destination -> destination.actionId == teamRead.id },
            "root=${root.map { destination -> destination.actionId }}",
        )
        assertTrue(
            descriptor.planDynamicNavigation().rootFormActions.none { form ->
                form.actionId == acceptInvitation.id
            },
        )
        val invitationLayout = assertNotNull(
            descriptor.layouts.singleOrNull { layout ->
                layout.sourceActionId == invitationsRead.id
            },
        )
        val invitationPlan = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(
                resourceId = invitationsRead.resourceId,
                recordId = "invite-7",
                fieldValues = mapOf(
                    "inviteId" to "invite-7",
                    "teamId" to "42",
                    "teamName" to "Home",
                    "userId" to "alice",
                ),
                currentLayoutId = invitationLayout.id,
            ),
        )
        val acceptInvitationForm = assertNotNull(
            invitationPlan.contextualFormActions
                .singleOrNull { form -> form.actionId == acceptInvitation.id },
            "accept=${acceptInvitation.id}; forms=" +
                invitationPlan.contextualFormActions.map { form ->
                    form.actionId to form.pathParameterValues
                },
        )
        assertEquals(mapOf("teamId" to "42"), acceptInvitationForm.pathParameterValues)
        val teamPlan = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(
                resourceId = teamRead.resourceId,
                recordId = "opaque-team",
                actionSafeIdentity = false,
            ),
        )
        assertTrue(teamPlan.contextualChildDestinations.any { destination ->
            destination.actionId == choreRead.id && destination.pathParameterValues["teamId"] == "opaque-team"
        })
        assertTrue(teamPlan.contextualChildDestinations.any { destination ->
            destination.actionId == workRead.id && destination.pathParameterValues["teamId"] == "opaque-team"
        })
        val observedChorePlan = descriptor.planDynamicNavigation(
            DynamicResourceRecordContext(
                resourceId = choreRead.resourceId,
                recordId = "opaque-chore",
                parameterValues = mapOf("teamId" to "opaque-team"),
                actionSafeIdentity = false,
            ),
        )
        assertTrue(observedChorePlan.contextualFormActions.none { form -> form.actionId == choreDelete.id })
        assertEquals(
            null,
            descriptor.resolveDynamicRecordReadParameters(
                choreDelete.id,
                DynamicResourceRecordContext(
                    resourceId = choreRead.resourceId,
                    recordId = "opaque-chore",
                    actionSafeIdentity = false,
                ),
            ),
        )
        assertFalse(descriptor.validationErrors().isNotEmpty())
    }

    private fun DynamicAppDescriptor.action(method: HttpMethod, path: String): DynamicAction =
        actions.singleOrNull { action -> action.binding.method == method && action.binding.path == path }
            ?: error(
                "Missing $method $path; available=" + actions.joinToString { action ->
                    "${action.binding.method} ${action.binding.path}"
                },
            )
}
