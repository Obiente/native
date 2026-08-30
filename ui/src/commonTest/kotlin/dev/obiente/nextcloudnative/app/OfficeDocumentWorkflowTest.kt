package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OfficeDocumentWorkflowTest {
    @Test
    fun advertisedPdfAndOtherTypesHaveSeparatePreviewAndEditActions() {
        listOf("application/pdf", "application/x-design", "text/plain").forEach { mime ->
            val file = officeFile(path = "Documents/example", mimeType = mime)
            val capabilities = officeCapabilities().copy(
                editors = officeCapabilities().editors.mapValues { (_, editor) ->
                    editor.copy(mimeTypes = setOf(mime))
                },
            )
            assertEquals(1, planOfficeEditorChoices(file, capabilities).size)
            val actions = planFilesScreenActions(
                file, FileActionSupport(documentEditing = capabilities, platformViewer = true),
            ).actions
            assertTrue(actions.single { it.action == FileMenuAction.Preview }.enabled)
            assertTrue(actions.single { it.action == FileMenuAction.EditWith }.enabled)
        }
    }

    @Test
    fun pdfCanDiscoverEditorsWithoutEnablingAnUnverifiedWrite() {
        val pdf = officeFile(path = "Documents/example.pdf", mimeType = "application/pdf")
        val action = planFilesScreenActions(
            pdf, FileActionSupport(discoverDocumentEditing = true),
        ).actions.single { it.action == FileMenuAction.EditWith }
        assertTrue(action.enabled)
        assertEquals("Choose Office editor...", action.label)
        assertTrue(planOfficeEditorChoices(pdf, officeCapabilities()).isEmpty())
        assertTrue(planOfficeEditorChoices(pdf.copy(permissions = "R"), officeCapabilities()).isEmpty())
    }

    @Test
    fun plansSecureVersionedWritableOfficeDocumentWithoutCreatingAToken() {
        val plan = planOfficeEditSession(
            file = officeFile(),
            capabilities = officeCapabilities(),
        )

        val ready = assertIs<OfficeEditSessionPlan.Ready>(plan)
        assertEquals("Documents", ready.request.path)
        assertEquals(42, ready.request.fileId)
        assertEquals("v4", ready.request.expectedEtag)
        assertEquals("richdocuments", ready.request.editorId)
    }

    @Test
    fun `offers every secure editor that advertises the exact mime type`() {
        val capabilities = officeCapabilities().copy(
            editors = officeCapabilities().editors + (
                "onlyoffice" to NextcloudDocumentEditorCapability(
                    id = "onlyoffice",
                    displayName = "ONLYOFFICE",
                    mimeTypes = setOf(DOCX_MIME),
                    optionalMimeTypes = emptySet(),
                    secure = true,
                )
                ),
        )

        val choices = planOfficeEditorChoices(officeFile(), capabilities)

        assertEquals(listOf("Nextcloud Office", "ONLYOFFICE"), choices.map(OfficeEditorChoice::displayName))
        assertEquals(setOf("richdocuments", "onlyoffice"), choices.map(OfficeEditorChoice::editorId).toSet())
    }

    @Test
    fun `does not offer insecure or mime incompatible editors`() {
        val capabilities = officeCapabilities().copy(
            editors = mapOf(
                "insecure" to NextcloudDocumentEditorCapability(
                    id = "insecure",
                    displayName = "Insecure editor",
                    mimeTypes = setOf(DOCX_MIME),
                    optionalMimeTypes = emptySet(),
                    secure = false,
                ),
                "sheets" to NextcloudDocumentEditorCapability(
                    id = "sheets",
                    displayName = "Sheets only",
                    mimeTypes = setOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                    optionalMimeTypes = emptySet(),
                    secure = true,
                ),
            ),
        )

        assertTrue(planOfficeEditorChoices(officeFile(), capabilities).isEmpty())
    }

    @Test
    fun blocksEditingUntilDavWritePermissionIsProven() {
        assertEquals(
            OfficeEditBlockedReason.MissingPermissions,
            assertIs<OfficeEditSessionPlan.Blocked>(
                planOfficeEditSession(officeFile(permissions = null), officeCapabilities()),
            ).reason,
        )
        assertEquals(
            OfficeEditBlockedReason.ReadOnly,
            assertIs<OfficeEditSessionPlan.Blocked>(
                planOfficeEditSession(officeFile(permissions = "RG"), officeCapabilities()),
            ).reason,
        )
    }

    @Test
    fun blocksTokenCreationWhenThePlatformCannotSecureAPlainHttpHandoff() {
        assertEquals(
            OfficeEditBlockedReason.InsecureAccountOrigin,
            assertIs<OfficeEditSessionPlan.Blocked>(
                planOfficeEditSession(
                    officeFile(),
                    officeCapabilities(),
                    accountOriginSecure = false,
                ),
            ).reason,
        )
    }

    @Test
    fun blocksUnadvertisedMimeAndUnsafePaths() {
        assertEquals(
            OfficeEditBlockedReason.UnsupportedMimeType,
            assertIs<OfficeEditSessionPlan.Blocked>(
                planOfficeEditSession(
                    officeFile(mimeType = "application/msword"),
                    officeCapabilities(),
                ),
            ).reason,
        )
        assertEquals(
            OfficeEditBlockedReason.UnsafePath,
            assertIs<OfficeEditSessionPlan.Blocked>(
                planOfficeEditSession(
                    officeFile(path = "Documents/../proposal.docx"),
                    officeCapabilities(),
                ),
            ).reason,
        )
    }

    @Test
    fun sessionDiagnosticsNeverContainTheTokenUrl() {
        val session = NextcloudDocumentEditSession(
            "https://cloud.example/index.php/apps/files/directEditing/private-token",
        )

        assertEquals("NextcloudDocumentEditSession(url=<redacted>)", session.toString())
        assertTrue("private-token" !in session.toString())
    }

    @Test
    fun capabilityCacheIsScopedPerAccountAndKeepsValidator() {
        val cache = NextcloudDocumentEditingCapabilitiesCache()
        val first = NextcloudSession("https://cloud.example", "ada", "secret")
        val second = NextcloudSession("https://cloud.example", "grace", "secret")

        cache.store(first, officeCapabilities(), "\"cap-v1\"")

        assertEquals("\"cap-v1\"", cache.get(first)?.etag)
        assertEquals(null, cache.get(second))
        assertEquals("\"cap-v1\"", cache.get(first.copy(appPassword = "rotated"))?.etag)
        assertEquals("\"cap-v1\"", cache.get(first.copy(serverUrl = "https://cloud.example/"))?.etag)
        assertEquals(null, cache.get(first.copy(serverUrl = "https://other.example")))
    }

    @Test
    fun capabilityCacheDoesNotConflateCaseSensitiveServerInstallationPaths() {
        val cache = NextcloudDocumentEditingCapabilitiesCache()
        val session = NextcloudSession("https://cloud.example/Cloud", "ada", "secret")
        cache.store(session, officeCapabilities(), "\"cap-v1\"")
        assertEquals(null, cache.get(session.copy(serverUrl = "https://cloud.example/cloud")))
    }

    private fun officeCapabilities() = NextcloudDocumentEditingCapabilities(
        editors = mapOf(
            "richdocuments" to NextcloudDocumentEditorCapability(
                id = "richdocuments",
                displayName = "Nextcloud Office",
                mimeTypes = setOf(DOCX_MIME),
                optionalMimeTypes = emptySet(),
                secure = true,
            ),
        ),
        creators = emptyMap(),
        supportsFileId = true,
    )

    private fun officeFile(
        path: String = "Documents/proposal.docx",
        mimeType: String = DOCX_MIME,
        permissions: String? = "RGDNVW",
    ) = NextcloudFile(
        path = path,
        name = "proposal.docx",
        isDirectory = false,
        mimeType = mimeType,
        size = 4096,
        lastModified = null,
        fileId = 42,
        hasPreview = true,
        etag = "v4",
        permissions = permissions,
    )

    private companion object {
        const val DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
