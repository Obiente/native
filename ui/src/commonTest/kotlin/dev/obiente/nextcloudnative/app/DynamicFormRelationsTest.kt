package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ResourceRelationshipSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.NativeCollectionBatchRelationLoadResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeDatasetContext
import dev.obiente.nextcloudnative.nativeui.runtime.withCollectionBatchRelationRecords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DynamicFormRelationsTest {
    @Test
    fun `form relationships preload one fully bound active collection read`() {
        val form = view("entries.create", "entries", NativeComponent.form, "entry-create")
        val schema = schema(
            form = form,
            relationship = ResourceRelationshipSpec(
                "categories",
                "entries",
                "id",
                "categoryId",
                Confidence.verified,
            ),
            reads = listOf(
                action(
                    "category-index",
                    "categories",
                    "/api/workspaces/{workspaceId}/categories",
                    requiredPathNames = listOf("workspaceId"),
                ),
                action(
                    "category-trash",
                    "categories",
                    "/api/workspaces/{workspaceId}/categories/trash",
                    requiredPathNames = listOf("workspaceId"),
                ),
            ),
        )

        assertEquals(
            listOf(DynamicFormRelationLoadPlan("categories", "category-index")),
            dynamicFormRelationLoadPlans(schema, form, mapOf("workspaceId" to "7")),
        )
    }

    @Test
    fun `collection batch relationships get an exact verified load request independent of form view`() {
        val form = view("entries.create", "entries", NativeComponent.form, "entry-create")
        val schema = schema(
            form = form,
            relationship = ResourceRelationshipSpec(
                "categories",
                "entries",
                "id",
                "categoryId",
                Confidence.verified,
            ),
            reads = listOf(
                action(
                    "category-index",
                    "categories",
                    "/api/workspaces/{workspaceId}/categories",
                    requiredPathNames = listOf("workspaceId"),
                ),
            ),
        )

        val request = dynamicCollectionBatchRelationLoadRequests(
            schema = schema,
            childResourceId = "entries",
            relatedFieldIds = setOf("categoryId"),
            availableValues = mapOf("workspaceId" to "workspace-7"),
        ).single()

        assertEquals("categories", request.plan.resourceId)
        assertEquals("category-index", request.plan.actionId)
        assertEquals(mapOf("workspaceId" to "workspace-7"), request.cacheKey.bindingValues)
        assertTrue(
            dynamicCollectionBatchRelationLoadRequests(
                schema = schema,
                childResourceId = "entries",
                relatedFieldIds = setOf("unrelatedId"),
                availableValues = mapOf("workspaceId" to "workspace-7"),
            ).isEmpty(),
        )
        assertTrue(
            dynamicCollectionBatchRelationLoadRequests(
                schema = schema,
                childResourceId = "entries",
                relatedFieldIds = setOf("categoryId"),
                availableValues = emptyMap(),
            ).isEmpty(),
        )
    }

    @Test
    fun `collection batch relation scope replaces ambient records and enforces its memory bound`() {
        val stale = NativeRecord("stale", mapOf("id" to "stale", "name" to "Wrong house"))
        val fresh = NativeRecord("fresh", mapOf("id" to "fresh", "name" to "Current house"))
        val context = NativeDatasetContext(
            bindingValues = mapOf("houseId" to "house-7"),
            relatedRecords = mapOf(
                "categories" to listOf(stale),
                "unrelated" to listOf(stale),
            ),
            relatedRecordPaging = mapOf(
                "categories" to dev.obiente.nextcloudnative.nativeui.runtime.NativeRelatedRecordPaging(),
            ),
        )

        val scoped = context.withCollectionBatchRelationRecords(
            mapOf("categories" to listOf(fresh)),
        )

        assertEquals(mapOf("categories" to listOf(fresh)), scoped.relatedRecords)
        assertTrue(scoped.relatedRecordPaging.isEmpty())
        assertEquals(context.bindingValues, scoped.bindingValues)
        assertFailsWith<IllegalArgumentException> {
            NativeCollectionBatchRelationLoadResult(
                recordsByResourceId = mapOf(
                    "categories" to (1..501).map { index ->
                        NativeRecord("category-$index", mapOf("id" to "category-$index"))
                    },
                ),
            )
        }
    }

    @Test
    fun `generic relation route identity prefers its uniquely qualified retained parent`() {
        val form = view("entries.create", "entries", NativeComponent.form, "entry-create")
        val schema = schema(
            form = form,
            relationship = ResourceRelationshipSpec(
                "categories",
                "entries",
                "id",
                "categoryId",
                Confidence.verified,
            ),
            reads = listOf(
                action(
                    "category-index",
                    "categories",
                    "/api/houses/{id}/categories",
                    requiredPathNames = listOf("id"),
                ),
            ),
        )

        val request = dynamicFormRelationLoadRequests(
            schema = schema,
            formView = form,
            availableValues = mapOf(
                "id" to "selected-list-7",
                "houseId" to "retained-house-3",
            ),
        ).single()

        assertEquals(mapOf("id" to "retained-house-3"), request.cacheKey.bindingValues)
        assertEquals(
            mapOf(
                "id" to "retained-house-3",
                "houseId" to "retained-house-3",
                "page" to "2",
            ),
            dynamicFormRelationRuntimeValues(
                request = request,
                availableValues = mapOf(
                    "id" to "selected-list-7",
                    "houseId" to "retained-house-3",
                ),
                additionalValues = mapOf("page" to "2"),
            ),
        )
    }

    @Test
    fun `ambiguous qualified aliases never fall back to an unrelated direct identity`() {
        val form = view("entries.create", "entries", NativeComponent.form, "entry-create")
        val schema = schema(
            form = form,
            relationship = ResourceRelationshipSpec(
                "categories",
                "entries",
                "id",
                "categoryId",
                Confidence.verified,
            ),
            reads = listOf(
                action(
                    "category-index",
                    "categories",
                    "/api/houses/{id}/categories",
                    requiredPathNames = listOf("id"),
                ),
            ),
        )

        assertTrue(
            dynamicFormRelationLoadRequests(
                schema = schema,
                formView = form,
                availableValues = mapOf(
                    "id" to "selected-list-7",
                    "houseId" to "retained-house-3",
                    "housesId" to "conflicting-house-4",
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun `oversized relation response keeps its later choices within the strict scope bound`() {
        val form = view("entries.create", "entries", NativeComponent.form, "entry-create")
        val schema = schema(
            form = form,
            relationship = ResourceRelationshipSpec(
                "categories",
                "entries",
                "id",
                "categoryId",
                Confidence.verified,
            ),
            reads = listOf(
                action(
                    "category-index",
                    "categories",
                    "/api/categories",
                    requiredPathNames = emptyList(),
                ),
            ),
        )
        val request = dynamicFormRelationLoadRequests(schema, form, emptyMap()).single()
        val records = (1..700).map { index ->
            NativeRecord(
                id = "category-$index",
                values = mapOf("id" to "category-$index", "name" to "Category $index"),
            )
        }

        val state = DynamicFormRelationCacheState().loadSucceeded(request, records)
        val cached = state.relatedRecords(listOf(request))
            .getValue("categories")

        assertEquals(MAX_DYNAMIC_FORM_RELATION_RECORDS, cached.size)
        assertTrue(cached.none { record -> record.id == "category-200" })
        assertTrue(cached.any { record -> record.id == "category-201" })
        assertTrue(cached.any { record -> record.id == "category-500" })
        assertTrue(cached.any { record -> record.id == "category-501" })
        assertTrue(cached.any { record -> record.id == "category-700" })
        assertEquals(200, state.discardedRecordCount(request))
    }

    @Test
    fun `relation pagination progressively preserves later pages and declared continuation`() {
        val form = view("entries.create", "entries", NativeComponent.form, "entry-create")
        val schema = schema(
            form = form,
            relationship = ResourceRelationshipSpec(
                "categories",
                "entries",
                "id",
                "categoryId",
                Confidence.verified,
            ),
            reads = listOf(
                action(
                    "category-index",
                    "categories",
                    "/api/categories",
                    requiredPathNames = emptyList(),
                ),
            ),
        )
        val request = dynamicFormRelationLoadRequests(schema, form, emptyMap()).single()
        val pagination = DynamicPaginationSpec(
                parameterName = "page",
                mode = DynamicPaginationMode.PageNumber,
                expectedPageSize = 50,
        )

        val firstPage = DynamicFormRelationCacheState().loadSucceeded(
            request = request,
            records = records(1..50),
            pagination = pagination,
        )
        assertEquals("2", firstPage.continuation(request)?.nextRequestValue)

        val secondPage = firstPage.appendPageSucceeded(request, records(46..95))
        val secondPageRecords = secondPage.relatedRecords(listOf(request)).getValue("categories")
        assertEquals(95, secondPageRecords.size)
        assertEquals(secondPageRecords.size, secondPageRecords.map(NativeRecord::id).distinct().size)
        assertTrue(secondPageRecords.any { record -> record.id == "category-75" })
        assertEquals("3", secondPage.continuation(request)?.nextRequestValue)

        val finalPage = secondPage.appendPageSucceeded(request, records(96..107))
        assertEquals(107, finalPage.relatedRecords(listOf(request)).getValue("categories").size)
        assertEquals(null, finalPage.continuation(request))
    }

    @Test
    fun `relation pagination remains reachable beyond the bounded cache window`() {
        val form = view("entries.create", "entries", NativeComponent.form, "entry-create")
        val schema = schema(
            form = form,
            relationship = ResourceRelationshipSpec(
                "categories",
                "entries",
                "id",
                "categoryId",
                Confidence.verified,
            ),
            reads = listOf(
                action(
                    "category-index",
                    "categories",
                    "/api/categories",
                    requiredPathNames = emptyList(),
                ),
            ),
        )
        val request = dynamicFormRelationLoadRequests(schema, form, emptyMap()).single()
        val pagination = DynamicPaginationSpec(
            parameterName = "page",
            mode = DynamicPaginationMode.PageNumber,
            expectedPageSize = 50,
        )
        val firstPage = DynamicFormRelationCacheState().loadSucceeded(
            request = request,
            records = records(1..50),
            pagination = pagination,
        )
        val beyondFormerLimit = (2..11).fold(firstPage) { state, pageNumber ->
            val first = ((pageNumber - 1) * 50) + 1
            state.appendPageSucceeded(request, records(first..(first + 49)))
        }
        val cached = beyondFormerLimit.relatedRecords(listOf(request)).getValue("categories")

        assertEquals(MAX_DYNAMIC_FORM_RELATION_RECORDS, cached.size)
        assertTrue(cached.none { record -> record.id == "category-50" })
        assertTrue(cached.any { record -> record.id == "category-51" })
        assertTrue(cached.any { record -> record.id == "category-501" })
        assertTrue(cached.any { record -> record.id == "category-550" })
        assertEquals("12", beyondFormerLimit.continuation(request)?.nextRequestValue)
        assertEquals(50, beyondFormerLimit.discardedRecordCount(request))

        val restarted = beyondFormerLimit.loadSucceeded(
            request = request,
            records = records(1..50),
            pagination = pagination,
        )
        assertTrue(
            restarted.relatedRecords(listOf(request))
                .getValue("categories")
                .any { record -> record.id == "category-1" },
        )
        assertEquals(0, restarted.discardedRecordCount(request))
        assertEquals("2", restarted.continuation(request)?.nextRequestValue)
    }

    @Test
    fun `unbound or unrelated reads do not become form lookup dependencies`() {
        val form = view("entries.create", "entries", NativeComponent.form, "entry-create")
        val schema = schema(
            form = form,
            relationship = ResourceRelationshipSpec(
                "categories",
                "entries",
                "id",
                "categoryId",
                Confidence.high,
            ),
            reads = listOf(
                action(
                    "category-index",
                    "categories",
                    "/api/workspaces/{workspaceId}/categories",
                    requiredPathNames = listOf("workspaceId"),
                ),
            ),
        )

        assertEquals(emptyList(), dynamicFormRelationLoadPlans(schema, form, emptyMap()))
        assertEquals(
            emptyList(),
            dynamicFormRelationLoadPlans(
                schema.copy(
                    actions = schema.actions.map { action ->
                        if (action.id == "entry-create") {
                            action.copy(binding = action.binding.copy(bodyFieldNames = listOf("name")))
                        } else {
                            action
                        }
                    },
                ),
                form,
                mapOf("workspaceId" to "7"),
            ),
        )
    }

    @Test
    fun `relation cache identity follows exact declared hierarchy bindings`() {
        val form = view("entries.create", "entries", NativeComponent.form, "entry-create")
        val schema = schema(
            form = form,
            relationship = ResourceRelationshipSpec(
                "categories",
                "entries",
                "id",
                "categoryId",
                Confidence.verified,
            ),
            reads = listOf(
                action(
                    "category-index",
                    "categories",
                    "/api/workspaces/{workspaceId}/categories",
                    requiredPathNames = listOf("workspaceId"),
                ),
            ),
        )
        val firstRequest = dynamicFormRelationLoadRequests(
            schema,
            form,
            mapOf("workspaceId" to "workspace-7", "unrelated" to "ignored"),
        ).single()
        val sameScope = dynamicFormRelationLoadRequests(
            schema,
            form,
            mapOf("workspaceId" to "workspace-7"),
        ).single()
        val nextParent = dynamicFormRelationLoadRequests(
            schema,
            form,
            mapOf("workspaceId" to "workspace-8"),
        ).single()
        val records = listOf(NativeRecord("category-1", mapOf("name" to "First")))
        val cached = DynamicFormRelationCacheState().loadSucceeded(firstRequest, records)
        val staleGenericRecords = mapOf(
            "categories" to listOf(NativeRecord("stale", mapOf("name" to "Wrong parent"))),
            "unrelated" to listOf(NativeRecord("other", mapOf("name" to "Keep me"))),
        )

        assertEquals(firstRequest.cacheKey, sameScope.cacheKey)
        assertNotEquals(firstRequest.cacheKey, nextParent.cacheKey)
        assertEquals(mapOf("categories" to records), cached.relatedRecords(listOf(sameScope)))
        assertTrue(cached.relatedRecords(listOf(nextParent)).isEmpty())
        assertEquals(listOf(nextParent), cached.pendingRequests(listOf(nextParent)))
        assertEquals(
            mapOf("unrelated" to staleGenericRecords.getValue("unrelated")),
            cached.datasetRelatedRecords(staleGenericRecords, listOf(nextParent)),
        )
        assertEquals(
            mapOf(
                "unrelated" to staleGenericRecords.getValue("unrelated"),
                "categories" to records,
            ),
            cached.datasetRelatedRecords(staleGenericRecords, listOf(sameScope)),
        )
    }

    @Test
    fun `relation failures stay visible until targeted retry and cache state remains bounded`() {
        val form = view("entries.create", "entries", NativeComponent.form, "entry-create")
        val schema = schema(
            form = form,
            relationship = ResourceRelationshipSpec(
                "categories",
                "entries",
                "id",
                "categoryId",
                Confidence.verified,
            ),
            reads = listOf(
                action(
                    "category-index",
                    "categories",
                    "/api/workspaces/{workspaceId}/categories",
                    requiredPathNames = listOf("workspaceId"),
                ),
            ),
        )
        val request = dynamicFormRelationLoadRequests(
            schema,
            form,
            mapOf("workspaceId" to "workspace-7"),
        ).single()
        val failed = DynamicFormRelationCacheState().loadFailed(request)
        val staleGenericRecords = mapOf(
            "categories" to listOf(NativeRecord("stale", mapOf("name" to "Wrong parent"))),
        )

        assertEquals(listOf(request), failed.failedRequests(listOf(request)))
        assertTrue(failed.pendingRequests(listOf(request)).isEmpty())
        assertTrue(failed.datasetRelatedRecords(staleGenericRecords, listOf(request)).isEmpty())

        val retrying = failed.retry(listOf(request))

        assertTrue(retrying.failedRequests(listOf(request)).isEmpty())
        assertEquals(listOf(request), retrying.pendingRequests(listOf(request)))

        val bounded = (1..24).fold(DynamicFormRelationCacheState()) { state, index ->
            val scoped = request.copy(
                cacheKey = request.cacheKey.copy(
                    bindingValues = mapOf("workspaceId" to "workspace-$index"),
                ),
            )
            state.loadSucceeded(
                scoped,
                listOf(NativeRecord("category-$index", mapOf("name" to "Category $index"))),
            ).loadFailed(
                scoped.copy(
                    cacheKey = scoped.cacheKey.copy(
                        bindingValues = mapOf("workspaceId" to "failed-$index"),
                    ),
                ),
            )
        }

        assertEquals(16, bounded.recordsByKey.size)
        assertEquals(16, bounded.failedKeys.size)
    }

    private fun schema(
        form: ViewSpec,
        relationship: ResourceRelationshipSpec,
        reads: List<ActionSpec>,
    ): NativeAppSchema {
        val create = ActionSpec(
            id = "entry-create",
            label = "Create entry",
            resourceId = "entries",
            binding = ApiBinding(
                method = HttpMethod.POST,
                path = "/api/workspaces/{workspaceId}/entries",
                operationId = "entry-create",
                requiredPathParameterNames = listOf("workspaceId"),
                bodyFieldNames = listOf("name", "categoryId"),
            ),
            intent = ActionIntent.create,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            confidence = Confidence.verified,
        )
        return NativeAppSchema(
            schemaVersion = "test",
            app = AppIdentity("example", "Example", "1"),
            confidence = Confidence.verified,
            views = listOf(form) + reads.map { action ->
                view("${action.id}.list", action.resourceId, NativeComponent.collectionList, action.id)
            },
            actions = listOf(create) + reads,
            relationships = listOf(relationship),
        )
    }

    private fun action(
        id: String,
        resourceId: String,
        path: String,
        requiredPathNames: List<String>,
    ) = ActionSpec(
        id = id,
        label = id,
        resourceId = resourceId,
        binding = ApiBinding(
            method = HttpMethod.GET,
            path = path,
            operationId = id,
            requiredPathParameterNames = requiredPathNames,
        ),
        intent = ActionIntent.list,
        risk = ActionRisk.readOnly,
        requiresConfirmation = false,
        confidence = Confidence.verified,
    )

    private fun view(
        id: String,
        resourceId: String,
        component: NativeComponent,
        sourceActionId: String,
    ) = ViewSpec(
        id = id,
        title = id,
        resourceId = resourceId,
        component = component,
        sourceActionId = sourceActionId,
        confidence = Confidence.verified,
    )

    private fun records(range: IntRange): List<NativeRecord> = range.map { index ->
        NativeRecord(
            id = "category-$index",
            values = mapOf("id" to "category-$index", "name" to "Category $index"),
        )
    }
}
