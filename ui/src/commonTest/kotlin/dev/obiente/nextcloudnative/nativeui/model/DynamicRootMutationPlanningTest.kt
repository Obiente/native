package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DynamicRootMutationPlanningTest {
    @Test
    fun `GET helper requiring a body key cannot become an app root`() {
        val helper = action("photo-by-key", "contacts", ActionIntent.read).copy(
            binding = DynamicHttpBinding(
                method = HttpMethod.GET,
                path = "/contacts",
                body = HttpBody(
                    contentType = "application/json",
                    required = true,
                    schema = buildJsonObject {
                        put("required", buildJsonArray { add(JsonPrimitive("key")) })
                    },
                ),
            ),
        )
        val descriptor = descriptor(
            resources = listOf(resource("contacts")),
            layouts = listOf(layout("contacts", helper.id)),
            forms = emptyList(),
            actions = listOf(helper),
        )

        assertTrue(descriptor.planDynamicNavigation().rootDestinations.isEmpty())
    }

    @Test
    fun `verified self contained app command is available without a matching read root`() {
        val listProjects = action("list-projects", "projects", ActionIntent.list)
        val evidence = listOf(
            Provenance(
                kind = ProvenanceKind.appStoreLinkedSourceTag,
                source = "signed catalog release",
                detail = "Exact source tag contract",
            ),
        )
        val importData = action(
            "import-data", "imports", ActionIntent.execute, method = HttpMethod.POST,
        ).copy(provenance = evidence)
        val importForm = form(
            "import-data.form", "Import data", "imports", importData.id,
        ).copy(provenance = evidence)
        val descriptor = descriptor(
            resources = listOf(resource("projects"), resource("imports")),
            layouts = listOf(layout("projects", listProjects.id)),
            forms = listOf(importForm),
            actions = listOf(listProjects, importData),
        )

        assertEquals(
            listOf(importForm.id),
            descriptor.planDynamicNavigation().rootFormActions.map(DynamicNavigationFormAction::formId),
        )
        assertTrue(
            descriptor.copy(forms = listOf(importForm.copy(provenance = emptyList())))
                .planDynamicNavigation().rootFormActions.isEmpty(),
        )
        val scopedImport = importData.copy(
            binding = importData.binding.copy(
                path = "/imports/{projectId}",
                pathParameters = listOf(
                    HttpParameter(
                        "projectId", required = true, schema = buildJsonObject {},
                        source = ParameterSource.resourceField,
                    ),
                ),
            ),
        )
        assertTrue(
            descriptor.copy(actions = listOf(listProjects, scopedImport))
                .planDynamicNavigation().rootFormActions.isEmpty(),
        )
    }

    @Test
    fun `root forms with the same label remain distinct across resources`() {
        val evidence = listOf(
            Provenance(
                kind = ProvenanceKind.verifiedAppPackage,
                source = "signed app package",
                detail = "Verified root writes",
            ),
        )
        val createAccount = action(
            "create-account", "accounts", ActionIntent.create, method = HttpMethod.POST,
        ).copy(label = "Create", provenance = evidence)
        val createCategory = action(
            "create-category", "categories", ActionIntent.create, method = HttpMethod.POST,
        ).copy(label = "Create", provenance = evidence)
        val descriptor = descriptor(
            resources = listOf(resource("accounts"), resource("categories")),
            forms = listOf(
                form("create-account.form", "Create", "accounts", createAccount.id).copy(provenance = evidence),
                form("create-category.form", "Create", "categories", createCategory.id).copy(provenance = evidence),
            ),
            actions = listOf(createAccount, createCategory),
        )

        assertEquals(
            listOf("create-account.form", "create-category.form"),
            descriptor.planDynamicNavigation().rootFormActions.map(DynamicNavigationFormAction::formId),
        )
    }

    private fun descriptor(
        resources: List<DynamicResource>,
        layouts: List<DynamicLayout> = emptyList(),
        forms: List<DynamicForm>,
        actions: List<DynamicAction>,
    ) = DynamicAppDescriptor(
        descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
        app = AppIdentity("workspace", "Workspace", "test"),
        endpointPolicy = EndpointPolicy("https://cloud.example.test"),
        resources = resources,
        layouts = layouts,
        links = emptyList(),
        forms = forms,
        actions = actions,
    )

    private fun resource(id: String) = DynamicResource(
        id = id,
        label = id.replaceFirstChar(Char::uppercase),
        collection = true,
        confidence = Confidence.high,
    )

    private fun layout(resourceId: String, actionId: String) = DynamicLayout(
        id = "$resourceId.list",
        title = resourceId.replaceFirstChar(Char::uppercase),
        resourceId = resourceId,
        kind = LayoutKind.list,
        sourceActionId = actionId,
        confidence = Confidence.high,
    )

    private fun form(id: String, title: String, resourceId: String, actionId: String) = DynamicForm(
        id = id,
        title = title,
        resourceId = resourceId,
        actionId = actionId,
        confidence = Confidence.high,
    )

    private fun action(
        id: String,
        resourceId: String,
        intent: ActionIntent,
        method: HttpMethod = HttpMethod.GET,
    ) = DynamicAction(
        id = id,
        label = id,
        resourceId = resourceId,
        intent = intent,
        risk = if (method == HttpMethod.GET) ActionRisk.readOnly else ActionRisk.mutating,
        requiresConfirmation = method != HttpMethod.GET,
        binding = DynamicHttpBinding(method = method, path = "/$resourceId"),
        confidence = Confidence.high,
    )
}
