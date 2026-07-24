package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfficeDocumentRequestTest {
    @Test
    fun parsesObservedDirectEditingShapeIncludingSingularCreatorMimeType() {
        val capabilities = parseDesktopDocumentEditingCapabilities(
            DIRECT_EDITING_FIXTURE,
            supportsFileId = true,
        )
        val editor = capabilities.editors.getValue("richdocuments")
        val creator = capabilities.creators.getValue("document")

        assertTrue(editor.secure)
        assertEquals(setOf(DOCX_MIME), editor.mimeTypes)
        assertEquals(setOf("application/msword"), editor.optionalMimeTypes)
        assertEquals(DOCX_MIME, creator.mimeType)
        assertTrue(creator.templates)
        assertTrue(capabilities.supportsFileId)
        assertTrue(
            parseDesktopDirectEditingSupportsFileId(
                """{"ocs":{"data":{"capabilities":{"files":{"directEditing":{"supportsFileId":true}}}}}}""",
            ),
        )
    }

    @Test
    fun conditionalCapabilityRequestUsesOnlyTheValidatorHeader() {
        assertEquals(emptyMap(), documentEditingConditionalHeaders(null))
        assertEquals(
            mapOf("If-None-Match" to "\"cap-v1\""),
            documentEditingConditionalHeaders("\"cap-v1\""),
        )
    }

    @Test
    fun parsesBothLegacyAndCoreTemplateInventoriesWithoutPreviewUrls() {
        val legacy = parseDesktopDocumentTemplates(
            """
                {"ocs":{"data":[
                  {"id":7,"name":"Project brief","extension":"odt","type":"document"}
                ]}}
            """.trimIndent(),
            "document",
        )
        val core = parseDesktopDocumentTemplates(
            """
                {"ocs":{"data":{"templates":{
                  "9":{"title":"Blank report","extension":".odt","preview":"https://example.test/private"}
                }}}}
            """.trimIndent(),
            "document",
        )

        assertEquals("7", legacy.single().id)
        assertEquals("Project brief", legacy.single().displayName)
        assertEquals("9", core.single().id)
        assertEquals("odt", core.single().extension)
        assertEquals(
            "/ocs/v2.php/apps/files/api/v1/directEditing/templates/richdocuments/document?format=json",
            documentTemplatesRelativePath("richdocuments", "document"),
        )
        assertEquals(
            "/ocs/v2.php/apps/richdocuments/api/v1/templates/document?format=json",
            legacyRichdocumentsTemplatesRelativePath("document"),
        )
        assertFailsWith<IllegalArgumentException> {
            documentTemplatesRelativePath("richdocuments", "../document")
        }
    }

    @Test
    fun formEncodesPathButNeverIncludesTheReviewEtag() {
        val form = directEditingOpenForm(
            NextcloudDocumentEditSessionRequest(
                path = "Shared documents",
                fileId = 42,
                editorId = "richdocuments",
                expectedEtag = "\"private-version\"",
            ),
        )

        assertTrue("path=Shared+documents" in form)
        assertTrue("editorId=richdocuments" in form)
        assertTrue("fileId=42" in form)
        assertFalse("private-version" in form)
    }

    @Test
    fun directEditingFormTrustsWhiteboardButNotDrawio() {
        val whiteboard = directEditingOpenForm(
            NextcloudDocumentEditSessionRequest(
                path = "Boards",
                fileId = 42,
                editorId = WHITEBOARD_DIRECT_EDITOR_ID,
                expectedEtag = "v1",
            ),
        )

        assertTrue("editorId=whiteboard" in whiteboard)
        assertFailsWith<IllegalArgumentException> {
            directEditingOpenForm(
                NextcloudDocumentEditSessionRequest(
                    path = "Diagrams",
                    fileId = 42,
                    editorId = DRAWIO_APP_ID,
                    expectedEtag = "v1",
                ),
            )
        }
    }

    @Test
    fun acceptsOnlySameOriginTokenRouteWithoutQueryOrFragment() {
        assertEquals(
            "https://cloud.example/index.php/apps/files/directEditing/token-123",
            validatedDirectEditingHandoffUrl(
                "https://cloud.example",
                "/index.php/apps/files/directEditing/token-123",
            ),
        )
        assertEquals(
            "https://cloud.example/nextcloud/index.php/apps/files/directEditing/token-123",
            validatedDirectEditingHandoffUrl(
                "https://cloud.example/nextcloud",
                "https://cloud.example/nextcloud/index.php/apps/files/directEditing/token-123",
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            validatedDirectEditingHandoffUrl(
                "https://cloud.example",
                "https://attacker.example/index.php/apps/files/directEditing/token-123",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validatedDirectEditingHandoffUrl(
                "https://cloud.example",
                "/index.php/apps/richdocuments/direct/token-123",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validatedDirectEditingHandoffUrl(
                "https://cloud.example",
                "/index.php/apps/files/directEditing/token-123?access_token=leak",
            )
        }
    }

    private companion object {
        const val DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        val DIRECT_EDITING_FIXTURE = """
            {
              "ocs": {
                "meta": {"status": "ok", "statuscode": 200},
                "data": {
                  "editors": {
                    "richdocuments": {
                      "id": "richdocuments",
                      "name": "Nextcloud Office",
                      "mimetypes": ["$DOCX_MIME"],
                      "optionalMimetypes": ["application/msword"],
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
                      "mimetype": "$DOCX_MIME"
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }
}
