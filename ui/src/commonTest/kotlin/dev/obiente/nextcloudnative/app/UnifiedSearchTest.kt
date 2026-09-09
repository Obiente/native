package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class UnifiedSearchTest {
    @Test
    fun `provider discovery stays generic and includes mail`() {
        val providers = parseUnifiedSearchProviders(
            response(
                """
                {
                  "ocs": {
                    "meta": {"status": "ok", "statuscode": 100, "message": "OK"},
                    "data": [
                      {
                        "id": "mail",
                        "appId": "mail",
                        "name": "Mail",
                        "icon": "/apps/mail/img/mail.svg",
                        "order": 12,
                        "isExternalProvider": false,
                        "triggers": ["mail", "message"],
                        "filters": {"term": "string", "since": "datetime"},
                        "inAppSearch": true
                      },
                      {
                        "id": "external-knowledge",
                        "appId": "knowledge",
                        "name": "External knowledge",
                        "icon": "",
                        "order": 80,
                        "isExternalProvider": true,
                        "triggers": [],
                        "filters": {"term": "string", "custom": "boolean"},
                        "inAppSearch": false
                      }
                    ]
                  }
                }
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("mail", "external-knowledge"), providers.map(UnifiedSearchProvider::id))
        val mail = providers.first()
        assertEquals("mail", mail.appId)
        assertTrue(mail.hasInAppSearch)
        assertEquals(UnifiedSearchFilterKind.DateTime, mail.filters.first { it.name == "since" }.kind)
        assertTrue(providers.last().isExternal)
        assertEquals(UnifiedSearchFilterKind.Boolean, providers.last().filters.last().kind)
    }

    @Test
    fun `provider request safely encodes provider id and preserves declared filters`() {
        val provider = provider(id = "mail/messages", filters = listOf("term", "since"))
        val request = unifiedSearchProviderRequest(
            provider,
            UnifiedSearchRequest(
                term = "release notes",
                from = "/apps/mail/",
                limit = 15,
                sortOrder = 1,
                filters = mapOf("since" to "2026-07-01T00:00:00Z"),
            ),
            UnifiedSearchCursor("next/42"),
        )

        assertEquals("/ocs/v2.php/search/providers/mail%2Fmessages/search", request.relativePath)
        assertEquals("release notes", request.queryParameters["term"])
        assertEquals("next/42", request.queryParameters["cursor"])
        assertEquals("2026-07-01T00:00:00Z", request.queryParameters["since"])
        assertTrue(request.ocsApiRequest)
    }

    @Test
    fun `request rejects undeclared and reserved filters`() {
        val provider = provider(filters = listOf("term", "since"))
        assertFailsWith<IllegalArgumentException> {
            unifiedSearchProviderRequest(provider, UnifiedSearchRequest("x", filters = mapOf("mime" to "image/jpeg")))
        }
        assertFailsWith<IllegalArgumentException> {
            unifiedSearchProviderRequest(provider, UnifiedSearchRequest("x", filters = mapOf("limit" to "999")))
        }
        assertFailsWith<IllegalArgumentException> { UnifiedSearchRequest("x", limit = 26) }
    }

    @Test
    fun `blank control and oversized search inputs stay local`() {
        assertFailsWith<IllegalArgumentException> { UnifiedSearchRequest("   ") }
        assertFailsWith<IllegalArgumentException> { UnifiedSearchRequest("line\nbreak") }
        assertFailsWith<IllegalArgumentException> { UnifiedSearchRequest("x".repeat(513)) }
        assertFailsWith<IllegalArgumentException> {
            UnifiedSearchRequest("x", filters = mapOf("custom" to "y".repeat(2_049)))
        }
        assertFailsWith<IllegalArgumentException> { unifiedSearchProvidersRequest("context\u0000") }
    }

    @Test
    fun `result parser keeps generic attributes and cursor`() {
        val page = parseUnifiedSearchPage(
            response(
                """
                {
                  "ocs": {
                    "meta": {"status": "ok", "statuscode": 200},
                    "data": {
                      "name": "Messages",
                      "isPaginated": true,
                      "entries": [
                        {
                          "thumbnailUrl": "/avatar/alice/64",
                          "title": "Project update",
                          "subline": "Alice · Inbox",
                          "resourceUrl": "/apps/mail/message/42",
                          "icon": "icon-mail",
                          "rounded": true,
                          "attributes": {"type": "mailMessage", "accountId": "7", "messageId": "42"}
                        }
                      ],
                      "cursor": 25
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        assertEquals("Messages", page.name)
        assertEquals(UnifiedSearchCursor("25"), page.nextCursor)
        assertEquals("mailMessage", page.entries.single().attributes["type"])
        assertTrue(page.entries.single().roundedThumbnail)
    }

    @Test
    fun `empty php attribute array is accepted`() {
        val page = parseUnifiedSearchPage(
            response(
                """{"ocs":{"meta":{"status":"ok","statuscode":100},"data":{"name":"Files","isPaginated":false,"entries":[{"thumbnailUrl":"","title":"a.txt","subline":"/a.txt","resourceUrl":"/apps/files/a","icon":"","rounded":false,"attributes":[]}],"cursor":null}}}""",
            ),
        )
        assertTrue(page.entries.single().attributes.isEmpty())
        assertNull(page.nextCursor)
    }

    @Test
    fun `response collections and remote display text are bounded`() {
        val entries = (1..40).joinToString(",") { index ->
            """
            {
              "title":"Item $index${if (index == 1) "\\nunsafe" else ""}",
              "subline":"",
              "resourceUrl":"/apps/files/item/$index",
              "attributes":{"safe":"value","nested":{"private":"ignored"}}
            }
            """.trimIndent()
        }
        val page = parseUnifiedSearchPage(
            response(
                """{"ocs":{"meta":{"status":"ok","statuscode":100},"data":{"name":"Files","isPaginated":true,"entries":[$entries],"cursor":"25"}}}""",
            ),
        )

        assertEquals(MAX_UNIFIED_SEARCH_PAGE_SIZE, page.entries.size)
        assertEquals("Item 1 unsafe", page.entries.first().title)
        assertEquals(mapOf("safe" to "value"), page.entries.first().attributes)
    }

    @Test
    fun `empty paginated page never requests its advertised cursor`() {
        val group = firstUnifiedSearchGroup(
            provider(),
            UnifiedSearchPage(
                name = "Files",
                entries = emptyList(),
                isPaginated = true,
                nextCursor = UnifiedSearchCursor("20"),
            ),
        )

        assertFalse(group.canLoadMore)
        assertNull(group.nextCursor)
        assertEquals(UnifiedSearchPaginationStopReason.EmptyPage, group.stopReason)
    }

    @Test
    fun `pagination deduplicates entries and stops repeated cursors`() {
        val provider = provider()
        val firstEntry = entry("One", "/one")
        val first = firstUnifiedSearchGroup(
            provider,
            UnifiedSearchPage("Files", listOf(firstEntry), isPaginated = true, nextCursor = UnifiedSearchCursor("20")),
        )
        assertTrue(first.canLoadMore)

        val merged = mergeUnifiedSearchPage(
            first,
            UnifiedSearchPage(
                "Files",
                listOf(firstEntry, entry("Two", "/two")),
                isPaginated = true,
                nextCursor = UnifiedSearchCursor("20"),
            ),
            UnifiedSearchCursor("20"),
        )

        assertEquals(listOf("One", "Two"), merged.entries.map(UnifiedSearchEntry::title))
        assertFalse(merged.canLoadMore)
        assertEquals(UnifiedSearchPaginationStopReason.RepeatedCursor, merged.stopReason)
    }

    @Test
    fun `global search includes mail without adapter and excludes external providers by default`() {
        runBlocking {
            val mail = provider(id = "mail", appId = "mail")
            val files = provider(id = "files", appId = "files")
            val external = provider(id = "web", appId = "web", external = true)
            val client = NextcloudUnifiedSearchClient { request ->
                val id = request.relativePath.substringAfter("/providers/").substringBefore("/search")
                response(
                    """{"ocs":{"meta":{"status":"ok","statuscode":100},"data":{"name":"$id","isPaginated":false,"entries":[{"thumbnailUrl":"","title":"$id result","subline":"","resourceUrl":"/apps/$id/item/1","icon":"","rounded":false,"attributes":[]}],"cursor":null}}}""",
                )
            }
            val partial = mutableListOf<String>()
            val outcomes = client.searchAll(listOf(mail, files, external), UnifiedSearchRequest("hello")) { outcome ->
                partial += outcome.provider.id
            }

            assertEquals(setOf("mail", "files"), outcomes.map { it.provider.id }.toSet())
            assertEquals(setOf("mail", "files"), partial.toSet())
            assertIs<UnifiedSearchProviderOutcome.Results>(outcomes.first { it.provider.id == "mail" })
        }
    }

    @Test
    fun `provider failures do not discard other app results`() {
        runBlocking {
            val good = provider(id = "files")
            val bad = provider(id = "mail")
            val client = NextcloudUnifiedSearchClient { request ->
                if ("/mail/" in request.relativePath) response("broken", status = 500)
                else response(
                    """{"ocs":{"meta":{"status":"ok","statuscode":100},"data":{"name":"Files","isPaginated":false,"entries":[],"cursor":null}}}""",
                )
            }

            val outcomes = client.searchAll(listOf(good, bad), UnifiedSearchRequest("x"))

            assertIs<UnifiedSearchProviderOutcome.Results>(outcomes.first { it.provider.id == "files" })
            assertIs<UnifiedSearchProviderOutcome.Failure>(outcomes.first { it.provider.id == "mail" })
        }
    }

    @Test
    fun `visible results stay bound to the submitted query`() {
        val files = provider()
        val oldGroup = firstUnifiedSearchGroup(
            files,
            UnifiedSearchPage(
                name = "Files",
                entries = listOf(entry("Old result", "/old")),
                isPaginated = false,
                nextCursor = null,
            ),
        )
        val oldResults = UnifiedSearchVisibleResults(
            query = "old",
            groups = mapOf(files.id to oldGroup),
            failures = mapOf("mail" to "Old failure"),
        )

        assertTrue(oldResults.forQuery("new").groups.isEmpty())
        assertTrue(oldResults.forQuery("new").failures.isEmpty())
        assertEquals(oldResults, oldResults.forQuery("old"))
        assertEquals(
            oldResults,
            oldResults.updateForQuery("new") { copy(groups = emptyMap()) },
        )
    }

    @Test
    fun `ocs failure message is surfaced`() {
        val failure = assertFailsWith<UnifiedSearchException> {
            parseUnifiedSearchProviders(
                response("""{"ocs":{"meta":{"status":"failure","statuscode":998,"message":"Search disabled"},"data":[]}}"""),
            )
        }
        assertEquals("Search disabled", failure.message)
    }

    @Test
    fun `remote failure text is normalized and bounded before display`() {
        val failure = assertFailsWith<UnifiedSearchException> {
            parseUnifiedSearchProviders(
                response(
                    """{"ocs":{"meta":{"status":"failure","statuscode":998,"message":"${"x".repeat(400)}\nsecond"},"data":[]}}""",
                ),
            )
        }

        assertEquals(320, requireNotNull(failure.message).length)
        assertFalse(requireNotNull(failure.message).contains('\n'))
    }

    private fun provider(
        id: String = "files",
        appId: String = id,
        filters: List<String> = listOf("term"),
        external: Boolean = false,
    ): UnifiedSearchProvider = UnifiedSearchProvider(
        id = id,
        appId = appId,
        name = id.replaceFirstChar(Char::uppercase),
        iconUrl = null,
        order = 10,
        isExternal = external,
        triggers = emptyList(),
        filters = filters.map { UnifiedSearchFilterDefinition(it, "string") },
        hasInAppSearch = false,
    )

    private fun entry(title: String, resourceUrl: String): UnifiedSearchEntry = UnifiedSearchEntry(
        thumbnailUrl = null,
        title = title,
        subline = null,
        resourceUrl = resourceUrl,
        icon = null,
        roundedThumbnail = false,
        attributes = emptyMap(),
    )

    private fun response(body: String, status: Int = 200): NextcloudApiResponse = NextcloudApiResponse(
        status = status,
        body = body.encodeToByteArray(),
        contentType = "application/json",
        etag = null,
    )
}
