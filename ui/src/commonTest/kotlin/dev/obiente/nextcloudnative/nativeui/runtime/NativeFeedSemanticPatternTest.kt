package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.AdvertisedOpenApi
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptorCompiler
import dev.obiente.nextcloudnative.nativeui.model.DynamicDiscoveryInput
import dev.obiente.nextcloudnative.nativeui.model.EndpointPolicy
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.OpenApiTrust
import dev.obiente.nextcloudnative.nativeui.model.feedReaderSourceViews
import dev.obiente.nextcloudnative.nativeui.model.planDynamicNavigation
import dev.obiente.nextcloudnative.nativeui.model.toNativeAppSchema
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The feed reader and its read and star changes come from the contract's shape, not its app id. */
class NativeFeedSemanticPatternTest {
    @Test
    fun `the same feed-shaped contract gets the feed reader under any app id and version`() {
        val outcomes = listOf("news" to "25.3.1", "feedreader" to "0.0.1").map { (appId, version) ->
            val descriptor = compile(appId, version, feedOpenApi(appId))
            val schema = descriptor.toNativeAppSchema()
            val itemsView = assertNotNull(schema.views.firstOrNull { it.resourceId == "items" })
            assertEquals(NativeComponent.feedReader, itemsView.component, "$appId ${schema.views.map { it.id to it.component }}")
            assertEquals("items", descriptor.planDynamicNavigation().rootDestinations.first().resourceId)
            assertEquals(listOf("feeds"), schema.feedReaderSourceViews(itemsView).map { (view, _) -> view.resourceId })

            val plan = assertNotNull(
                nativeFeedReaderPlan(
                    schema = schema,
                    view = itemsView,
                    resource = assertNotNull(schema.resource("items")),
                    records = listOf(article("11", unread = true, starred = false), article("12", unread = false, starred = true)),
                    context = NativeDatasetContext(relatedRecords = mapOf("feeds" to listOf(feed("3", "Example Feed")))),
                ),
            )
            assertEquals(listOf(NativeFeedFilter.Unread, NativeFeedFilter.Starred, NativeFeedFilter.All), plan.filters)
            assertEquals(listOf("11"), plan.visible(NativeFeedFilter.Unread, null).map { it.record.id })
            assertEquals("Example Feed", plan.feedTitle(plan.articles.first()))
            plan.articles.map { article ->
                actions(schema, article).all.map { change ->
                    Triple(change.kind, change.action.binding.path.removePrefix("/apps/$appId"), change.request().values)
                }
            }
        }
        assertEquals(outcomes.first(), outcomes.last())
        assertEquals(
            listOf(
                listOf(NativeFeedItemActionKind.MarkRead, NativeFeedItemActionKind.Star),
                listOf(NativeFeedItemActionKind.MarkUnread, NativeFeedItemActionKind.Unstar),
            ),
            outcomes.first().map { changes -> changes.map { it.first } },
        )
        assertEquals("/api/items/{itemId}/read", outcomes.first().first().first().second)
    }

    @Test
    fun `optimistic state overrides the visible filter until the server answers`() {
        val schema = compile("feedreader", "0.0.1", feedOpenApi("feedreader")).toNativeAppSchema()
        val view = schema.views.first { it.resourceId == "items" }
        val plan = assertNotNull(
            nativeFeedReaderPlan(schema, view, assertNotNull(schema.resource("items")), listOf(article("11", true, false)), NativeDatasetContext()),
        )
        val key = plan.articles.single().key
        assertTrue(plan.visible(NativeFeedFilter.Unread, null, mapOf(key to NativeFeedArticleState(unread = false))).isEmpty())
    }

    @Test
    fun `articles without authoritative load evidence stay read-only`() {
        val schema = compile("feedreader", "0.0.1", feedOpenApi("feedreader")).toNativeAppSchema()
        val resource = assertNotNull(schema.resource("items"))
        val observed = assertNotNull(nativeFeedArticle(resource, article("11", true, false).copy(actionSafeIdentity = false)))
        assertTrue(nativeFeedItemActions(schema, observed).all.isEmpty())
    }

    @Test
    fun `a contract without state routes gives a read-only feed reader`() {
        val schema = compile("feedreader", "0.0.1", feedOpenApi("feedreader", includeStateRoutes = false)).toNativeAppSchema()
        val resource = assertNotNull(schema.resource("items"))
        val article = assertNotNull(nativeFeedArticle(resource, article("11", true, false)))
        assertTrue(actions(schema, article).all.isEmpty())
    }

    @Test
    fun `a bookmark list with titles and links is not a feed reader`() {
        val schema = compile("links", "1.0.0", BOOKMARKS_OPEN_API).toNativeAppSchema()
        val view = assertNotNull(schema.views.firstOrNull { it.resourceId == "bookmarks" })
        assertTrue(view.component != NativeComponent.feedReader)
        val records = listOf(NativeRecord("1", mapOf("id" to "1", "title" to "Docs", "url" to "https://example.test/docs")))
        assertNull(nativeFeedReaderPlan(schema, view, assertNotNull(schema.resource("bookmarks")), records, NativeDatasetContext()))
    }

    @Test
    fun `article links other than http and https are dropped`() {
        val resource = compile("feedreader", "0.0.1", feedOpenApi("feedreader")).toNativeAppSchema().resource("items")
        val record = article("11", true, false).let { it.copy(values = it.values + ("url" to "javascript:alert(1)")) }
        assertNull(assertNotNull(nativeFeedArticle(assertNotNull(resource), record)).link)
    }

    @Test
    fun `a route without an article parameter never marks an article read`() {
        val schema = compile("feedreader", "0.0.1", feedOpenApi("feedreader", includeStateRoutes = false, includeBulkRoutes = true)).toNativeAppSchema()
        val resource = assertNotNull(schema.resource("items"))
        val unread = assertNotNull(nativeFeedArticle(resource, article("11", unread = true, starred = false)))
        assertNull(actions(schema, unread).readState, "opening or marking one article must not mark all read")
    }

    @Test
    fun `the per-item route wins over bulk and feed-wide routes`() {
        val schema = compile("feedreader", "0.0.1", feedOpenApi("feedreader", includeBulkRoutes = true)).toNativeAppSchema()
        val resource = assertNotNull(schema.resource("items"))
        val unread = assertNotNull(nativeFeedArticle(resource, article("11", unread = true, starred = false)))
        val change = assertNotNull(actions(schema, unread).readState)
        assertEquals("/apps/feedreader/api/items/{itemId}/read", change.action.binding.path)
        assertEquals(NativeFeedItemActionKind.MarkRead, change.kind)
    }

    @Test
    fun `a contract-described bulk read route is never chosen for one article`() {
        val schema = compile("feedreader", "0.0.1", feedOpenApi("feedreader")).toNativeAppSchema()
        val perItem = schema.actions.single { it.binding.path.endsWith("/items/{itemId}/read") }
        // The same verified write without the article parameter marks every article read.
        val bulk = perItem.copy(
            id = "items-read-all",
            resourceId = "items",
            binding = perItem.binding.copy(
                path = "/apps/feedreader/api/items/read",
                operationId = "items-read-all",
                pathParameterNames = emptyList(),
                requiredPathParameterNames = emptyList(),
            ),
        )
        val resource = assertNotNull(schema.resource("items"))
        val unread = assertNotNull(nativeFeedArticle(resource, article("11", unread = true, starred = false)))

        val both = schema.copy(actions = schema.actions + bulk)
        assertEquals(perItem.id, actions(both, unread).readState?.action?.id)

        val bulkOnly = schema.copy(actions = schema.actions.filterNot { it.id == perItem.id } + bulk)
        assertNull(actions(bulkOnly, unread).readState)
    }

    @Test
    fun `only a required path parameter that names the article binds a change`() {
        fun binding(path: String, parameter: String) = ApiBinding(
            method = HttpMethod.POST,
            path = path,
            operationId = "change",
            pathParameterNames = listOf(parameter),
            requiredPathParameterNames = listOf(parameter),
        )
        val lists = listOf("/apps/synthetic/api/items")
        assertTrue(binding("/apps/synthetic/api/items/{itemId}/read", "itemId").bindsArticleIdentity("items", lists))
        assertTrue(binding("/apps/synthetic/api/items/{id}/read", "id").bindsArticleIdentity("items", lists))
        assertFalse(binding("/apps/synthetic/api/feeds/{feedId}/read", "feedId").bindsArticleIdentity("items", lists))
        assertFalse(ApiBinding(method = HttpMethod.POST, path = "/apps/synthetic/api/items/read", operationId = "all").bindsArticleIdentity("items", lists))
    }

    @Test
    fun `an epoch publication time is shown only when the contract declares its unit`() {
        fun resource(format: String?) = ResourceSpec(
            id = "items",
            name = "Items",
            confidence = Confidence.high,
            fields = listOf(FieldSpec("pubDate", "Published", FieldKind.integer, required = false, readOnly = true, format = format)),
        )
        val record = article("11", unread = true, starred = false).let { it.copy(values = it.values + ("pubDate" to "1700000000")) }
        assertEquals("2023-11-14 22:13 UTC", nativeFeedArticle(resource("unix-seconds"), record)?.publishedLabel)
        assertNull(nativeFeedArticle(resource(null), record)?.publishedLabel, "an integer of unknown unit is not guessed")
        assertNull(nativeFeedArticle(resource("int64"), record)?.publishedLabel)
        val iso = record.copy(values = record.values - "pubDate" + ("published" to "2026-01-02T03:04:05Z"))
        assertEquals("2026-01-02 03:04", nativeFeedArticle(resource(null), iso)?.publishedLabel)
    }

    private fun actions(schema: NativeAppSchema, article: NativeFeedArticle) = nativeFeedItemActions(schema, article,
        NativeRuleAuthority(NativeScreenState.Ready.authoritative(listOf(article.record)),
            schema.actions.single { it.intent == dev.obiente.nextcloudnative.nativeui.model.ActionIntent.list && it.resourceId == "items" }.id))

    private fun article(id: String, unread: Boolean, starred: Boolean) = NativeRecord(
        id = id,
        wireValues = mapOf("id" to kotlinx.serialization.json.JsonPrimitive(id.toInt()), "unread" to kotlinx.serialization.json.JsonPrimitive(unread), "starred" to kotlinx.serialization.json.JsonPrimitive(starred)),
        values = mapOf(
            "id" to id,
            "feedId" to "3",
            "title" to "Article $id",
            "url" to "https://example.test/articles/$id",
            "author" to "Synthetic Author",
            "body" to "<p>Body $id</p>",
            "guid" to "guid-$id",
            "unread" to unread.toString(),
            "starred" to starred.toString(),
        ),
    )

    private fun feed(id: String, title: String) = NativeRecord(id, mapOf("id" to id, "title" to title, "unreadCount" to "1"))

    private fun compile(appId: String, version: String, document: String): DynamicAppDescriptor =
        DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity(appId, appId.replaceFirstChar(Char::uppercase), version),
                endpointPolicy = EndpointPolicy(
                    serverOrigin = "https://cloud.example.test",
                    approvedApiPrefixes = listOf("/apps/$appId"),
                ),
                advertisedOpenApi = AdvertisedOpenApi(
                    documentUrl = "/apps/$appId/openapi.json",
                    document = Json.parseToJsonElement(document),
                    trust = OpenApiTrust.sameOriginAdvertisement,
                ),
            ),
        )

    private companion object {
        fun feedOpenApi(appId: String, includeStateRoutes: Boolean = true, includeBulkRoutes: Boolean = false): String {
            fun stateRoute(verb: String) = """
                ,
                "/apps/$appId/api/items/{itemId}/$verb": {
                  "post": {
                    "operationId": "items-$verb",
                    "parameters": [ { "name": "itemId", "in": "path", "required": true, "schema": { "type": "integer" } } ],
                    "responses": { "200": { "description": "OK" } }
                  }
                }
            """.trimIndent()
            val perItem = if (includeStateRoutes) listOf("read", "unread", "star", "unstar").joinToString("") { stateRoute(it) } else ""
            val bulk = if (includeBulkRoutes) BULK_ROUTES.replace("APP", appId) else ""
            val states = perItem + bulk
            return """
            {
              "openapi": "3.0.3",
              "paths": {
                "/apps/$appId/api/feeds": {
                  "get": {
                    "operationId": "feeds-list",
                    "responses": { "200": { "description": "OK", "content": { "application/json": { "schema": {
                      "type": "array", "items": { "type": "object", "required": ["id"], "properties": {
                        "id": { "type": "integer" }, "title": { "type": "string" }, "url": { "type": "string" },
                        "unreadCount": { "type": "integer" }, "folderId": { "type": "integer" }
                      } }
                    } } } } }
                  },
                  "post": {
                    "operationId": "feeds-create",
                    "requestBody": { "content": { "application/json": { "schema": {
                      "type": "object", "required": ["url"], "properties": { "url": { "type": "string" } }
                    } } } },
                    "responses": { "200": { "description": "OK" } }
                  }
                },
                "/apps/$appId/api/items": {
                  "get": {
                    "operationId": "items-list",
                    "responses": { "200": { "description": "OK", "content": { "application/json": { "schema": {
                      "type": "array", "items": { "type": "object", "required": ["id"], "properties": {
                        "id": { "type": "integer" }, "feedId": { "type": "integer" }, "title": { "type": "string" },
                        "url": { "type": "string" }, "author": { "type": "string" }, "body": { "type": "string" },
                        "guid": { "type": "string" }, "unread": { "type": "boolean" }, "starred": { "type": "boolean" }
                      } }
                    } } } } }
                  }
                }$states
              }
            }
            """.trimIndent()
        }

        /** Routes that change many articles at once: every article, or every article of one feed. */
        val BULK_ROUTES = """
            ,
            "/apps/APP/api/items/read": {
              "post": { "operationId": "items-read-all", "responses": { "200": { "description": "OK" } } }
            },
            "/apps/APP/api/feeds/{feedId}/read": {
              "post": {
                "operationId": "feeds-read",
                "parameters": [ { "name": "feedId", "in": "path", "required": true, "schema": { "type": "integer" } } ],
                "responses": { "200": { "description": "OK" } }
              }
            }
        """.trimIndent()

        val BOOKMARKS_OPEN_API = """
            {
              "openapi": "3.0.3",
              "paths": {
                "/apps/links/api/bookmarks": {
                  "get": {
                    "operationId": "bookmarks-list",
                    "responses": { "200": { "description": "OK", "content": { "application/json": { "schema": {
                      "type": "array", "items": { "type": "object", "required": ["id"], "properties": {
                        "id": { "type": "integer" }, "title": { "type": "string" }, "url": { "type": "string" }
                      } }
                    } } } } }
                  }
                }
              }
            }
        """.trimIndent()
    }
}
