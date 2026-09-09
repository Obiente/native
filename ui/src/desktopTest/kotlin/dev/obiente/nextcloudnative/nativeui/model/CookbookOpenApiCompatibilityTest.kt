package dev.obiente.nextcloudnative.nativeui.model

import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequest
import dev.obiente.nextcloudnative.contracts.OpenApiContractSourceKind
import dev.obiente.nextcloudnative.contracts.SignedAppStoreContractAcquirer
import dev.obiente.nextcloudnative.app.NextcloudApiResponse
import dev.obiente.nextcloudnative.app.NextcloudApiMethod
import dev.obiente.nextcloudnative.app.buildDynamicApiRequest
import dev.obiente.nextcloudnative.app.parseDynamicRecords
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.NativeStructuredValue
import dev.obiente.nextcloudnative.nativeui.runtime.actionBindingValues
import dev.obiente.nextcloudnative.nativeui.runtime.editableNativeFields
import dev.obiente.nextcloudnative.nativeui.runtime.nativeStructuredDetail
import dev.obiente.nextcloudnative.nativeui.runtime.nativeRecipePresentation
import dev.obiente.nextcloudnative.nativeui.runtime.uneditableNativeBodyFieldIds
import dev.obiente.nextcloudnative.nativeui.runtime.withEphemeralDisplayFields
import dev.obiente.nextcloudnative.nativeui.runtime.withObservedSettingsFormTypes
import dev.obiente.nextcloudnative.nativeui.runtime.withObservedSettingsInputTypes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CookbookOpenApiCompatibilityTest {
    @Test
    fun cookbookDetailFixtureBecomesGenericSectionedNativeContent() {
        val action = recipeDetailAction()
        val fields = recipeFields()
        val record = parseDynamicRecords(action, fixtureResponse(), fields.mapTo(linkedSetOf(), FieldSpec::id)).single()
        val resource = ResourceSpec("recipes", "Recipes", Confidence.high, fields)
        val detail = nativeStructuredDetail(resource, record)

        assertEquals("Baked bananas", record.values["name"])
        assertEquals(
            listOf("recipeIngredient", "recipeInstructions", "tool", "nutrition"),
            detail.sections.map { it.fieldId },
        )
        assertEquals(
            listOf("Ingredients", "Instructions", "Tools", "Nutrition"),
            detail.sections.map { it.label },
        )
        assertTrue(
            setOf("description", "recipeYield", "prepTime", "cookTime", "totalTime")
                .all { fieldId -> detail.fields.any { it.fieldId == fieldId } },
        )
        assertTrue(setOf("id", "name", "url", "dateModified").none { fieldId ->
            detail.fields.any { it.fieldId == fieldId }
        })
        assertEquals(
            listOf("Description", "Servings", "Preparation", "Cooking", "Total"),
            detail.fields.take(5).map { it.formatted.label },
        )
        assertEquals("15 min", detail.fields.single { it.fieldId == "prepTime" }.formatted.displayValue)
        val recipe = assertNotNull(nativeRecipePresentation(resource, record))
        assertEquals(
            "/index.php/apps/cookbook/recipe/123/image?size=full",
            recipe.imagePath,
        )
        assertFalse(detail.sections.single { it.fieldId == "recipeIngredient" }.ordered)
        assertTrue(detail.sections.single { it.fieldId == "recipeInstructions" }.ordered)

        val nutrition = detail.sections.single { it.fieldId == "nutrition" }.value as NativeStructuredValue.ObjectValue
        assertEquals(
            listOf("servingSize", "calories", "carbohydrateContent", "proteinContent"),
            nutrition.entries.map { it.key },
        )
    }

    @Test
    fun cookbookTaggedAllOfRecipeBodyBecomesAnActionableNativeForm() {
        val original = javaClass.getResourceAsStream(CONTRACT_FIXTURE_PATH).use { stream ->
            requireNotNull(stream) { "Missing Cookbook OpenAPI fixture" }
            Json.parseToJsonElement(stream.bufferedReader().readText()) as JsonObject
        }
        val components = original.getValue("components") as JsonObject
        val schemas = components.getValue("schemas") as JsonObject
        val recipe = schemas.getValue("Recipe") as JsonObject
        val recipeProperties = recipe.getValue("properties") as JsonObject
        val identityFields = setOf("id", "name", "keywords")
        val composedRecipe = JsonObject(
            mapOf(
                "description" to JsonPrimitive("A recipe according to schema.org"),
                "allOf" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("object"),
                                "properties" to JsonObject(recipeProperties.filterKeys(identityFields::contains)),
                                "required" to JsonArray(identityFields.map(::JsonPrimitive)),
                            ),
                        ),
                        JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("object"),
                                "properties" to JsonObject(recipeProperties.filterKeys { id -> id !in identityFields }),
                                "required" to JsonArray(
                                    listOf("description", "recipeIngredient", "recipeInstructions", "tool")
                                        .map(::JsonPrimitive),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val paths = original.getValue("paths") as JsonObject
        val recipesPath = paths.getValue("/apps/cookbook/api/v1/recipes") as JsonObject
        val createOperation = recipesPath.getValue("post") as JsonObject
        val document = JsonObject(
            original +
                ("components" to JsonObject(
                    components + ("schemas" to JsonObject(schemas + ("Recipe" to composedRecipe))),
                )) +
                ("paths" to JsonObject(
                    paths + ("/apps/cookbook/api/v1/recipes" to JsonObject(
                        recipesPath + ("post" to JsonObject(
                            createOperation + ("operationId" to JsonPrimitive("newrecipe")),
                        )),
                    )),
                )),
        )
        val descriptor = DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("cookbook", "Cookbook", "0.11.10"),
                endpointPolicy = EndpointPolicy(
                    serverOrigin = "https://cloud.example.test",
                    approvedApiPrefixes = listOf("/apps/cookbook"),
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    documentUrl = "https://raw.githubusercontent.com/nextcloud/cookbook/v0.11.10/docs/dev/api/0.1.3/openapi-cookbook.yaml",
                    document = document,
                    trust = OpenApiTrust.appStoreLinkedExactGitHubTag,
                ),
            ),
        )
        val create = descriptor.actions.single { action ->
            action.binding.method == HttpMethod.POST &&
                action.binding.path == "/apps/cookbook/api/v1/recipes"
        }
        val createFields = descriptor.forms.single { form -> form.actionId == create.id }.fields
        assertTrue(createFields.any { field -> field.fieldId == "name" && field.required })
        assertTrue(createFields.any { field -> field.fieldId == "description" && !field.required })
        assertTrue(setOf("id", "dateCreated", "dateModified", "nutrition").none { id ->
            createFields.any { field -> field.fieldId == id }
        })
        val request = buildDynamicApiRequest(descriptor, create, mapOf("name" to "Compatibility recipe"))
        assertEquals(
            JsonObject(mapOf("name" to JsonPrimitive("Compatibility recipe"))),
            Json.parseToJsonElement(requireNotNull(request.body).decodeToString()),
        )
    }

    @Test
    fun cookbookContractSelectsDeclaredItemGetAndRendersItsStructuredResponse() {
        val document = javaClass.getResourceAsStream(CONTRACT_FIXTURE_PATH).use { stream ->
            requireNotNull(stream) { "Missing Cookbook OpenAPI fixture" }
            Json.parseToJsonElement(stream.bufferedReader().readText())
        }
        val descriptor = DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("cookbook", "Cookbook", "0.11.9"),
                endpointPolicy = EndpointPolicy(
                    serverOrigin = "https://cloud.example.test",
                    approvedApiPrefixes = listOf("/apps/cookbook"),
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    documentUrl = "https://cloud.example.test/apps/cookbook/openapi.json",
                    document = document,
                ),
            ),
        )
        val resource = assertNotNull(
            descriptor.resources.singleOrNull { it.id == "recipes" },
            "resources=${descriptor.resources.map { it.id }}",
        )
        val detailAction = assertNotNull(descriptor.actions.singleOrNull { action ->
            action.binding.method == HttpMethod.GET &&
                action.binding.path == "/apps/cookbook/api/v1/recipes/{id}"
        }, "actions=${descriptor.actions.map { "${it.id}:${it.binding.method}:${it.binding.path}:${it.resourceId}" }}")
        val nativeViews = descriptor.toNativeAppSchema().views
        val detailView = assertNotNull(nativeViews.singleOrNull { view ->
            view.resourceId == resource.id && view.component == NativeComponent.detail
        }, "views=$nativeViews")

        assertEquals(detailAction.id, detailView.sourceActionId)
        val request = buildDynamicApiRequest(
            descriptor,
            detailAction,
            NativeRecord("123", mapOf("id" to "123")).actionBindingValues(),
        )
        assertEquals(NextcloudApiMethod.GET, request.method)
        assertEquals("/apps/cookbook/api/v1/recipes/123", request.relativePath)

        val records = parseDynamicRecords(
            detailAction,
            fixtureResponse(),
            resource.fields.mapTo(linkedSetOf(), DynamicField::id),
        )
        val record = assertNotNull(
            records.singleOrNull(),
            "records=$records fields=${resource.fields.map { "${it.id}:${it.kind}" }}",
        )
        val nativeResource = descriptor.toNativeAppSchema().resources.single { it.id == resource.id }
        val detail = nativeStructuredDetail(nativeResource, record)
        assertEquals(
            listOf("nutrition", "recipeIngredient", "recipeInstructions", "tool"),
            detail.sections.map { it.fieldId }.sorted(),
        )
        assertTrue(detail.sections.single { it.fieldId == "recipeInstructions" }.ordered)

        val createAction = descriptor.actions.single { action ->
            action.intent == ActionIntent.create && action.binding.path == "/apps/cookbook/api/v1/recipes"
        }
        val updateAction = descriptor.actions.single { action ->
            action.intent == ActionIntent.update &&
                action.binding.path == "/apps/cookbook/api/v1/recipes/{id}"
        }
        val deleteAction = descriptor.actions.single { action ->
            action.intent == ActionIntent.delete &&
                action.binding.path == "/apps/cookbook/api/v1/recipes/{id}"
        }
        val rootPlan = descriptor.planDynamicNavigation()
        assertEquals(
            "recipes",
            rootPlan.rootDestinations.first().resourceId,
            "The actionable recipe collection must open before taxonomy/config roots.",
        )
        assertTrue(rootPlan.rootFormActions.any { action -> action.actionId == createAction.id })

        val context = DynamicResourceRecordContext(
            resourceId = resource.id,
            recordId = "123",
            fieldValues = mapOf("id" to "123"),
        )
        val contextualPlan = descriptor.planDynamicNavigation(context)
        assertTrue(contextualPlan.contextualFormActions.any { action -> action.actionId == updateAction.id })
        assertTrue(contextualPlan.contextualFormActions.any { action -> action.actionId == deleteAction.id })

        val updateRequest = buildDynamicApiRequest(
            descriptor,
            updateAction,
            values = mapOf(
                "id" to "123",
                "name" to "Updated bananas",
                "recipeIngredient" to "3 bananas\n1 tsp cinnamon",
                "recipeInstructions" to "Peel the bananas.\nBake until golden.",
                "tool" to "Oven dish\nSmall bowl",
            ),
        )
        val deleteRequest = buildDynamicApiRequest(
            descriptor,
            deleteAction,
            values = mapOf("id" to "123"),
        )
        assertEquals(NextcloudApiMethod.PUT, updateRequest.method)
        assertEquals("/apps/cookbook/api/v1/recipes/123", updateRequest.relativePath)
        val updateBody = Json.parseToJsonElement(requireNotNull(updateRequest.body).decodeToString()) as JsonObject
        assertEquals(
            listOf("3 bananas", "1 tsp cinnamon"),
            (updateBody.getValue("recipeIngredient") as JsonArray).map { element ->
                (element as JsonPrimitive).content
            },
        )
        val nativeSchema = descriptor.toNativeAppSchema()
        val updateView = nativeSchema.views.single { view ->
            view.sourceActionId == updateAction.id && view.component == NativeComponent.form
        }
        val updateFields = editableNativeFields(
            nativeSchema.resources.single { native -> native.id == updateView.resourceId },
            nativeSchema.actions.single { native -> native.id == updateAction.id },
        )
        assertTrue(
            setOf("recipeIngredient", "recipeInstructions", "tool")
                .all { fieldId -> updateFields.any { field -> field.id == fieldId } },
        )
        assertEquals(NextcloudApiMethod.DELETE, deleteRequest.method)
        assertEquals("/apps/cookbook/api/v1/recipes/123", deleteRequest.relativePath)

        val configRead = descriptor.actions.single { action ->
            action.intent == ActionIntent.read &&
                action.binding.path == "/apps/cookbook/api/v1/config"
        }
        val configWrite = descriptor.actions.single { action ->
            action.binding.method == HttpMethod.POST &&
                action.binding.path == "/apps/cookbook/api/v1/config"
        }
        assertEquals(ActionEffect.execute, configWrite.effect)
        assertTrue(rootPlan.rootDestinations.any { destination -> destination.actionId == configRead.id })
        assertTrue(rootPlan.rootFormActions.any { action -> action.actionId == configWrite.id })
    }

    @Test
    fun cookbookConfigShapeBecomesTypedEditableSettingsWithoutServerMutation() {
        val readAction = DynamicAction(
            id = "config.read",
            label = "Config",
            resourceId = "config",
            intent = ActionIntent.read,
            risk = ActionRisk.readOnly,
            requiresConfirmation = false,
            binding = DynamicHttpBinding(HttpMethod.GET, "/apps/cookbook/api/v1/config"),
            confidence = Confidence.high,
        )
        val observedBody = Json.parseToJsonElement(
            """{"type":"object","additionalProperties":true,"x-nextcloud-native-observed-settings-body":true}""",
        )
        val writeAction = DynamicAction(
            id = "config.update",
            label = "Config",
            resourceId = "config",
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            binding = DynamicHttpBinding(
                method = HttpMethod.POST,
                path = "/apps/cookbook/api/v1/config",
                body = HttpBody("application/json", true, observedBody),
            ),
            confidence = Confidence.high,
            provenance = listOf(
                Provenance(
                    ProvenanceKind.verifiedAppPackage,
                    "signed-package",
                    "Verified Cookbook configuration route",
                ),
            ),
        )
        val descriptor = DynamicAppDescriptor(
            descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
            app = AppIdentity("cookbook", "Cookbook", "0.11.9"),
            endpointPolicy = EndpointPolicy(
                serverOrigin = "https://cloud.example.test",
                approvedApiPrefixes = listOf("/apps/cookbook"),
            ),
            resources = listOf(
                DynamicResource("config", "Config", collection = false, confidence = Confidence.high),
            ),
            forms = listOf(
                DynamicForm(
                    id = "config.form",
                    title = "Config",
                    resourceId = "config",
                    actionId = writeAction.id,
                    fields = emptyList(),
                    confidence = Confidence.high,
                ),
            ),
            actions = listOf(readAction, writeAction),
        )
        val record = parseDynamicRecords(
            readAction,
            configFixtureResponse(),
            declaredFieldIds = emptySet(),
        ).single()
        val schema = descriptor.toNativeAppSchema()
        val nativeWrite = schema.actions.single { it.id == writeAction.id }
        val observedResource = schema.resources.single()
            .withEphemeralDisplayFields(listOf(record))
            .withObservedSettingsFormTypes(nativeWrite, record)
        val typedWrite = nativeWrite.withObservedSettingsInputTypes(observedResource)

        assertEquals(
            setOf("folder", "update_interval", "print_image", "visibleInfoBlocks"),
            editableNativeFields(observedResource, typedWrite).mapTo(linkedSetOf(), FieldSpec::id),
        )
        val properties = (typedWrite.inputSchema as JsonObject)["properties"] as JsonObject
        assertEquals(JsonPrimitive("string"), (properties.getValue("folder") as JsonObject)["type"])
        assertEquals(JsonPrimitive("integer"), (properties.getValue("update_interval") as JsonObject)["type"])
        assertEquals(JsonPrimitive("boolean"), (properties.getValue("print_image") as JsonObject)["type"])

        val request = buildDynamicApiRequest(
            descriptor,
            writeAction,
            values = mapOf(
                "folder" to "Cookbook",
                "update_interval" to "900",
                "print_image" to "false",
                "visibleInfoBlocks" to
                    """{"preparation-time":true,"cooking-time":false,"tools":true}""",
            ),
            observedInputSchema = typedWrite.inputSchema,
        )
        val body = Json.parseToJsonElement(requireNotNull(request.body).decodeToString()) as JsonObject

        assertEquals(NextcloudApiMethod.POST, request.method)
        assertEquals("/apps/cookbook/api/v1/config", request.relativePath)
        assertEquals(JsonPrimitive("Cookbook"), body["folder"])
        assertEquals(JsonPrimitive(900), body["update_interval"])
        assertEquals(JsonPrimitive(false), body["print_image"])
        assertTrue(body["visibleInfoBlocks"] is JsonObject)
    }

    @Test
    fun liveOfficialCookbookContractCompilesIntoNativeResources() {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_APPSTORE_TEST") != "1") return
        val acquired = assertNotNull(
            SignedAppStoreContractAcquirer().acquire(
                ContractAcquisitionRequest("cookbook", "34.0.1", "0.11.10"),
            ),
        )
        val trust = when (acquired.sourceKind) {
            OpenApiContractSourceKind.SignedAppPackage -> OpenApiTrust.nextcloudSignedAppPackage
            OpenApiContractSourceKind.SignedCompatibleAppPackage ->
                OpenApiTrust.nextcloudSignedCompatibleAppPackage
            OpenApiContractSourceKind.AppStoreLinkedExactGitHubTag ->
                OpenApiTrust.appStoreLinkedExactGitHubTag
            OpenApiContractSourceKind.AppStoreLinkedCompatibleGitHubTag ->
                OpenApiTrust.appStoreLinkedCompatibleGitHubTag
        }
        val descriptor = DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity("cookbook", "Cookbook", "0.11.10"),
                endpointPolicy = EndpointPolicy(
                    serverOrigin = "https://cloud.example.test",
                    approvedApiPrefixes = listOf(
                        "/apps/cookbook",
                        "/ocs/v1.php/apps/cookbook",
                        "/ocs/v2.php/apps/cookbook",
                        "/index.php/apps/cookbook",
                        "/ocs/v2.php/cloud/capabilities",
                    ),
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    documentUrl = acquired.sourceUrl,
                    document = Json.parseToJsonElement(acquired.document),
                    trust = trust,
                ),
            ),
        )
        assertTrue(descriptor.actions.any { action ->
            action.binding.path == "/apps/cookbook/api/v1/recipes"
        })
        val detailAction = descriptor.actions.single { action ->
            action.binding.path == "/apps/cookbook/api/v1/recipes/{id}" &&
                action.binding.method == HttpMethod.GET
        }
        assertTrue(detailAction.binding.pathParameters.any { parameter -> parameter.name == "id" })
        val recipes = descriptor.resources.single { resource -> resource.id == detailAction.resourceId }
        assertTrue(descriptor.layouts.any { layout ->
            layout.resourceId == recipes.id && layout.kind == LayoutKind.list
        })
        assertTrue(descriptor.layouts.any { layout ->
            layout.resourceId == recipes.id && layout.kind == LayoutKind.detail
        })
        assertTrue(descriptor.validationErrors().isEmpty())

        val nativeSchema = descriptor.toNativeAppSchema()
        val updateRecipe = descriptor.actions.single { action ->
            action.binding.path == "/apps/cookbook/api/v1/recipes/{id}" &&
                action.binding.method == HttpMethod.PUT
        }
        val nativeUpdateRecipe = nativeSchema.actions.single { action -> action.id == updateRecipe.id }
        val nativeRecipeResource = nativeSchema.resources.single { resource ->
            resource.id == nativeUpdateRecipe.resourceId
        }
        val editableRecipeFields = editableNativeFields(nativeRecipeResource, nativeUpdateRecipe)
        assertTrue(
            uneditableNativeBodyFieldIds(
                action = nativeUpdateRecipe,
                editableFields = editableRecipeFields,
                autoBoundValues = mapOf("id" to "123"),
            ).isEmpty(),
            "Cookbook recipe update has declared body fields without safe native editors: " +
                uneditableNativeBodyFieldIds(
                    action = nativeUpdateRecipe,
                    editableFields = editableRecipeFields,
                    autoBoundValues = mapOf("id" to "123"),
                ).joinToString(),
        )
        val configRead = descriptor.actions.single { action ->
            action.binding.path == "/apps/cookbook/api/v1/config" &&
                action.binding.method == HttpMethod.GET
        }
        val configWrite = descriptor.actions.single { action ->
            action.binding.path == "/apps/cookbook/api/v1/config" &&
                action.binding.method == HttpMethod.POST
        }
        val configBody = assertNotNull(configWrite.binding.body)
        assertTrue(configBody.schema is JsonObject)
        assertTrue("x-nextcloud-native-observed-settings-body" !in configBody.schema)
        assertTrue(!nativeSchema.actions.single { it.id == configWrite.id }.binding.allowsObservedBodyFields)
        assertTrue(
            descriptor.forms.single { form -> form.actionId == configWrite.id }
                .fields.map { field -> field.fieldId }
                .containsAll(listOf("folder", "update_interval", "print_image", "visibleInfoBlocks")),
        )
        assertTrue(nativeSchema.views.any { view ->
            view.resourceId == configRead.resourceId &&
                view.component == NativeComponent.detail &&
                view.sourceActionId == configRead.id
        })
        assertTrue(nativeSchema.views.any { view ->
            view.resourceId == configWrite.resourceId &&
                view.component == NativeComponent.form &&
                view.sourceActionId == configWrite.id
        })
        val selectedDetailView = nativeSchema.views.single { view ->
            view.resourceId == recipes.id && view.component == NativeComponent.detail
        }
        assertEquals(detailAction.id, selectedDetailView.sourceActionId)
        val selected = NativeRecord("123", mapOf("id" to "123"))
        val detailRequest = buildDynamicApiRequest(
            descriptor,
            detailAction,
            selected.actionBindingValues(),
        )
        assertEquals(NextcloudApiMethod.GET, detailRequest.method)
        assertEquals("/apps/cookbook/api/v1/recipes/123", detailRequest.relativePath)

        val runtimeRecord = parseDynamicRecords(
            detailAction,
            fixtureResponse(),
            recipes.fields.mapTo(linkedSetOf(), DynamicField::id),
        ).single()
        val nativeResource = nativeSchema.resources.single { it.id == recipes.id }
            .withEphemeralDisplayFields(listOf(runtimeRecord))
        val structured = nativeStructuredDetail(nativeResource, runtimeRecord)
        assertTrue(structured.sections.any { it.fieldId == "recipeIngredient" && !it.ordered })
        assertTrue(structured.sections.any { it.fieldId == "recipeInstructions" && it.ordered })
        assertTrue(structured.sections.any { it.fieldId == "nutrition" })
    }

    private fun recipeDetailAction() = DynamicAction(
        id = "recipeDetails",
        label = "Get a single recipe",
        resourceId = "recipes",
        intent = ActionIntent.read,
        risk = ActionRisk.readOnly,
        requiresConfirmation = false,
        binding = DynamicHttpBinding(HttpMethod.GET, "/apps/cookbook/api/v1/recipes/{id}"),
        confidence = Confidence.high,
    )

    private fun recipeFields() = listOf(
        FieldSpec("id", "Id", FieldKind.string, required = true, readOnly = true),
        FieldSpec("name", "Name", FieldKind.string, required = true, readOnly = true),
        FieldSpec("imageUrl", "Image", FieldKind.image, required = false, readOnly = true),
        FieldSpec("imagePlaceholderUrl", "Image placeholder", FieldKind.image, required = false, readOnly = true),
        FieldSpec("description", "Description", FieldKind.longText, required = false, readOnly = true),
        FieldSpec("recipeYield", "Servings", FieldKind.integer, required = false, readOnly = true),
        FieldSpec("prepTime", "Preparation time", FieldKind.string, required = false, readOnly = true),
        FieldSpec("cookTime", "Cooking time", FieldKind.string, required = false, readOnly = true),
        FieldSpec("totalTime", "Total time", FieldKind.string, required = false, readOnly = true),
        FieldSpec("tool", "Tools", FieldKind.objectValue, required = false, readOnly = true),
        FieldSpec("recipeIngredient", "Ingredients", FieldKind.objectValue, required = false, readOnly = true),
        FieldSpec("recipeInstructions", "Instructions", FieldKind.objectValue, required = false, readOnly = true),
        FieldSpec("nutrition", "Nutrition", FieldKind.objectValue, required = false, readOnly = true),
    )

    private fun fixtureResponse(): NextcloudApiResponse {
        val body = javaClass.getResourceAsStream(FIXTURE_PATH).use { stream ->
            requireNotNull(stream) { "Missing Cookbook recipe fixture" }
            stream.readBytes()
        }
        return NextcloudApiResponse(200, body, "application/json", null)
    }

    private fun configFixtureResponse(): NextcloudApiResponse {
        val body = javaClass.getResourceAsStream(CONFIG_FIXTURE_PATH).use { stream ->
            requireNotNull(stream) { "Missing Cookbook config-shape fixture" }
            stream.readBytes()
        }
        return NextcloudApiResponse(200, body, "application/json", null)
    }

    private companion object {
        const val FIXTURE_PATH = "/fixtures/cookbook-recipe-detail.json"
        const val CONFIG_FIXTURE_PATH = "/fixtures/cookbook-config-shape.json"
        const val CONTRACT_FIXTURE_PATH = "/fixtures/cookbook-openapi-detail-excerpt.json"
    }
}
