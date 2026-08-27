package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudDocumentEditSessionRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidDocumentEditingTest {
    @Test
    fun `capability inventory keeps exact editor metadata and file id support`() {
        val capabilities = parseAndroidDocumentEditingCapabilities(
            body = """
                {
                  "ocs": {
                    "data": {
                      "editors": {
                        "richdocuments": {
                          "id": "richdocuments",
                          "name": "Nextcloud Office",
                          "mimetypes": ["application/vnd.oasis.opendocument.text"],
                          "optionalMimetypes": ["APPLICATION/VND.OPENXMLFORMATS-OFFICEDOCUMENT.PRESENTATIONML.PRESENTATION"],
                          "secure": true
                        }
                      },
                      "creators": {
                        "document": {
                          "id": "document",
                          "editor": "richdocuments",
                          "name": "New document",
                          "extension": "odt",
                          "templates": true,
                          "mimetype": "application/vnd.oasis.opendocument.text"
                        }
                      }
                    }
                  }
                }
            """.trimIndent(),
            supportsFileId = true,
        )

        val editor = capabilities.editors.getValue("richdocuments")
        assertEquals("Nextcloud Office", editor.displayName)
        assertTrue(editor.secure)
        assertTrue(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" in
                editor.optionalMimeTypes,
        )
        assertEquals("richdocuments", capabilities.creators.getValue("document").editorId)
        assertTrue(capabilities.supportsFileId)
    }

    @Test
    fun `direct editing support reads the files capability`() {
        assertTrue(
            parseAndroidDirectEditingSupportsFileId(
                """
                    {
                      "ocs": {
                        "data": {
                          "capabilities": {
                            "files": {"directEditing": {"supportsFileId": true}}
                          }
                        }
                      }
                    }
                """.trimIndent(),
            ),
        )
        assertFalse(
            parseAndroidDirectEditingSupportsFileId(
                """{"ocs":{"data":{"capabilities":{"files":{}}}}}""",
            ),
        )
    }

    @Test
    fun `open form supports root and encodes nested paths`() {
        assertEquals(
            "path=%2F&editorId=richdocuments&fileId=42",
            androidDirectEditingOpenForm(request(path = "/")),
        )
        assertEquals(
            "path=Shared+Documents%2FQuarter+1&editorId=richdocuments&fileId=42",
            androidDirectEditingOpenForm(request(path = "Shared Documents/Quarter 1")),
        )
    }

    @Test
    fun `open form rejects unsafe input and untrusted editors`() {
        assertFailsWith<IllegalArgumentException> {
            androidDirectEditingOpenForm(request(path = "Documents/../Secrets"))
        }
        assertFailsWith<IllegalArgumentException> {
            androidDirectEditingOpenForm(request(path = "Documents", editorId = "malicious"))
        }
        assertFailsWith<IllegalArgumentException> {
            androidDirectEditingOpenForm(request(path = "Documents", expectedEtag = ""))
        }
    }

    @Test
    fun `handoff accepts only a same origin core token route`() {
        assertEquals(
            "https://cloud.example.test/nextcloud/apps/files/directEditing/token-123",
            validatedAndroidDirectEditingHandoffUrl(
                "https://cloud.example.test/nextcloud",
                "/nextcloud/apps/files/directEditing/token-123",
            ),
        )
        assertEquals(
            "https://cloud.example.test/nextcloud/index.php/apps/files/directEditing/token-123",
            validatedAndroidDirectEditingHandoffUrl(
                "https://cloud.example.test/nextcloud",
                "/nextcloud/index.php/apps/files/directEditing/token-123",
            ),
        )
        listOf(
            "https://other.example.test/nextcloud/index.php/apps/files/directEditing/token-123",
            "/nextcloud/index.php/apps/files/directEditing/token-123?leak=1",
            "/nextcloud/index.php/apps/files/directEditing/token-123/extra",
            "/nextcloud/index.php/apps/files/directEditing/token%2Fextra",
            "/nextcloud/apps/richdocuments/index",
        ).forEach { candidate ->
            assertFailsWith<IllegalArgumentException>(candidate) {
                validatedAndroidDirectEditingHandoffUrl(
                    "https://cloud.example.test/nextcloud",
                    candidate,
                )
            }
        }
    }

    @Test
    fun `conditional inventory request forwards only a nonblank etag`() {
        assertEquals(emptyMap(), androidDocumentEditingConditionalHeaders(null))
        assertEquals(emptyMap(), androidDocumentEditingConditionalHeaders(""))
        assertEquals(mapOf("If-None-Match" to "\"inventory-v1\""), androidDocumentEditingConditionalHeaders("\"inventory-v1\""))
    }

    private fun request(
        path: String,
        editorId: String = "richdocuments",
        expectedEtag: String = "etag-1",
    ) = NextcloudDocumentEditSessionRequest(
        path = path,
        fileId = 42L,
        editorId = editorId,
        expectedEtag = expectedEtag,
    )
}
