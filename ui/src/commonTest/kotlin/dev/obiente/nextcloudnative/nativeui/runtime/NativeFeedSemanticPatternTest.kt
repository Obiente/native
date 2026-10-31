package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.AdvertisedOpenApi
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
                nativeFeedItemActions(schema, article).all.map { change ->
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
    fun `articles without contract-declared identity stay read-only`() {
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
        assertTrue(nativeFeedItemActions(schema, article).all.isEmpty())
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

    private fun article(id: String, unread: Boolean, starred: Boolean) = NativeRecord(
        id = id,
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
        fun feedOpenApi(appId: String, includeStateRoutes: Boolean = true): String {
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
            val states = if (includeStateRoutes) listOf("read", "unread", "star", "unstar").joinToString("") { stateRoute(it) } else ""
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
