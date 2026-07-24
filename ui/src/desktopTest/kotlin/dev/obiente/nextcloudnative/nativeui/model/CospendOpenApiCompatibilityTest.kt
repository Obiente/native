package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CospendOpenApiCompatibilityTest {
    @Test
    fun ranksCanonicalPrivateResourceOperationsAboveUtilityAndAlternateScopes() {
        val descriptor = compileFixture(LAYOUT_RANKING_FIXTURE_PATH)

        assertEquals(
            listOf(
                "projects.detail|api-get-project-info",
                "projects.list|api-get-local-projects",
            ),
            descriptor.layouts.map { "${it.id}|${it.sourceActionId}" },
        )
        assertEquals(
            listOf(
                "api-get-federated-projects",
                "api-get-local-projects",
                "api-get-project-info",
                "api-import-csv-project",
                "api-ping",
                "public-api-public-get-project-info",
            ),
            descriptor.actions.map(DynamicAction::id),
        )
        assertTrue(descriptor.layouts.none { layout ->
            layout.sourceActionId in setOf(
                "api-get-federated-projects",
                "api-import-csv-project",
                "api-ping",
                "public-api-public-get-project-info",
            )
        })
        assertTrue(descriptor.validationErrors().isEmpty())
    }

    @Test
    fun compilesAuthenticatedPrivateProjectAndBillSurfacesWithoutAnAdapter() {
        val descriptor = compileFixture(PRIVATE_FIXTURE_PATH)

        assertEquals(
            listOf(
                "bills.detail|detail|api-get-bill",
                "bills.list|list|api-get-bills",
                "projects.detail|detail|api-get-project-info",
                "projects.list|list|api-get-local-projects",
            ),
            descriptor.layouts.map { "${it.id}|${it.kind}|${it.sourceActionId}" },
        )
        assertEquals(
            listOf(
                "api-create-bill|create|POST|/ocs/v2.php/apps/cospend/api/v1/projects/{projectId}/bills",
                "api-create-project|create|POST|/ocs/v2.php/apps/cospend/api/v1/projects",
                "api-edit-bill|update|PUT|/ocs/v2.php/apps/cospend/api/v1/projects/{projectId}/bills/{billId}",
                "api-edit-project|update|PUT|/ocs/v2.php/apps/cospend/api/v1/projects/{projectId}",
                "api-get-bill|read|GET|/ocs/v2.php/apps/cospend/api/v1/projects/{projectId}/bills/{billId}",
                "api-get-bills|list|GET|/ocs/v2.php/apps/cospend/api/v1/projects/{id}/bills",
                "api-get-local-projects|list|GET|/ocs/v2.php/apps/cospend/api/v1/projects",
                "api-get-project-info|read|GET|/ocs/v2.php/apps/cospend/api/v1/projects/{projectId}",
            ),
            descriptor.actions.map {
                "${it.id}|${it.intent}|${it.binding.method}|${it.binding.path}"
            },
        )
        assertEquals(
            listOf(
                "api-create-bill.form",
                "api-create-project.form",
                "api-edit-bill.form",
                "api-edit-project.form",
            ),
            descriptor.forms.map(DynamicForm::id),
        )

        val projects = descriptor.resources.single { it.id == "projects" }
        assertTrue(projects.collection)
        assertTrue(
            setOf("id", "name", "currencyname", "members", "myaccesslevel")
                .all { expected -> projects.fields.any { it.id == expected } },
        )
        val bills = descriptor.resources.single { it.id == "bills" }
        assertTrue(bills.collection)
        assertTrue(
            setOf("id", "amount", "what", "date", "payer_id")
                .all { expected -> bills.fields.any { it.id == expected } },
        )
        assertEquals(FieldKind.date, bills.fields.single { it.id == "date" }.kind)

        descriptor.actions.forEach { action ->
            assertTrue("{apiVersion}" !in action.binding.path)
            assertTrue("/api/v1/" in action.binding.path || action.binding.path.endsWith("/api/v1/projects"))
            assertEquals(listOf(AuthKind.basic), action.binding.auth.map(AuthRequirement::kind))
            assertTrue(action.binding.ocs?.apiRequestHeader == true)
        }
        assertEquals(
            setOf("projectId"),
            descriptor.actions.single { it.id == "api-get-project-info" }
                .binding.pathParameters.map(HttpParameter::name).toSet(),
        )
        assertEquals(
            setOf("projectId", "billId"),
            descriptor.actions.single { it.id == "api-get-bill" }
                .binding.pathParameters.map(HttpParameter::name).toSet(),
        )

        val createProject = descriptor.forms.single { it.id == "api-create-project.form" }
        assertEquals(listOf("id", "name"), createProject.fields.map(FormField::fieldId))
        assertTrue(createProject.fields.all(FormField::required))
        val createBill = descriptor.forms.single { it.id == "api-create-bill.form" }
        assertTrue(setOf("amount", "date", "payer", "payedFor", "what").all { field ->
            createBill.fields.any { it.fieldId == field }
        })

        assertTrue(descriptor.validationErrors().isEmpty())

        val nativeSchema = descriptor.toNativeAppSchema()
        assertEquals(
            NativeComponent.dashboard,
            nativeSchema.views.single { it.id == "projects.list" }.component,
        )
        assertEquals(
            NativeComponent.dashboard,
            nativeSchema.views.single { it.id == "bills.list" }.component,
        )
        assertEquals(NativeComponent.detail, nativeSchema.views.single { it.id == "projects.detail" }.component)
    }

    private fun compileFixture(path: String): DynamicAppDescriptor {
        val document = javaClass.getResourceAsStream(path).use { stream ->
            requireNotNull(stream) { "Missing Cospend OpenAPI fixture" }
            Json.parseToJsonElement(stream.bufferedReader().readText())
        }
        return DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("cospend", "Cospend", "current-main"),
                endpointPolicy = EndpointPolicy(
                    serverOrigin = "https://cloud.example.test",
                    approvedApiPrefixes = listOf("/ocs/v2.php/apps/cospend/api"),
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    documentUrl = "/apps/cospend/openapi.json",
                    document = document,
                ),
            ),
        )
    }

    private companion object {
        const val PRIVATE_FIXTURE_PATH = "/fixtures/cospend-openapi-private-excerpt.json"
        const val LAYOUT_RANKING_FIXTURE_PATH = "/fixtures/cospend-openapi-layout-ranking-excerpt.json"
    }
}
