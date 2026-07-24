package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FileIntegrationPlanningTest {
    @Test
    fun plansWhiteboardThroughExactMimeAndCoreDirectEditing() {
        val plan = planIntegratedFileHandoff(
            file = file("Boards/plan.excalidraw", WHITEBOARD_MIME_TYPE),
            inventory = inventory(WHITEBOARD_APP_ID),
            capabilities = capabilities(
                WHITEBOARD_DIRECT_EDITOR_ID,
                WHITEBOARD_MIME_TYPE,
                secure = false,
            ),
        )

        val ready = assertIs<IntegratedFileHandoffPlan.Ready>(plan)
        assertEquals(IntegratedFileKind.Whiteboard, ready.kind)
        val handoff = assertIs<IntegratedFileHandoff.CoreDirectEditing>(ready.handoff)
        assertEquals("Boards", handoff.request.path)
        assertEquals(WHITEBOARD_DIRECT_EDITOR_ID, handoff.request.editorId)
        assertEquals(42, handoff.request.fileId)
    }

    @Test
    fun extensionAloneNeverEnablesWhiteboard() {
        val plan = planIntegratedFileHandoff(
            file = file("Boards/plan.excalidraw", "application/octet-stream"),
            inventory = inventory(WHITEBOARD_APP_ID),
            capabilities = capabilities(WHITEBOARD_DIRECT_EDITOR_ID, WHITEBOARD_MIME_TYPE),
        )

        assertEquals(
            IntegratedFileBlockedReason.MimeNotRegistered,
            assertIs<IntegratedFileHandoffPlan.Blocked>(plan).reason,
        )
    }

    @Test
    fun plansDrawioAsFixedSameOriginFileIdPage() {
        val plan = planIntegratedFileHandoff(
            file = file("Diagrams/system.drawio", DRAWIO_MIME_TYPE),
            inventory = inventory(DRAWIO_APP_ID),
            capabilities = NextcloudDocumentEditingCapabilities.Unavailable,
        )

        val ready = assertIs<IntegratedFileHandoffPlan.Ready>(plan)
        val handoff = assertIs<IntegratedFileHandoff.SameOriginPage>(ready.handoff)
        assertEquals(NextcloudApiMethod.GET, handoff.request.method)
        assertEquals(DRAWIO_EDITOR_PATH, handoff.request.relativePath)
        assertEquals(mapOf("fileId" to "42"), handoff.request.queryParameters)
        assertEquals(null, handoff.request.body)
    }

    @Test
    fun blocksDrawioWhenAppOrWritePermissionIsNotProven() {
        val missing = planIntegratedFileHandoff(
            file = file("Diagrams/system.drawio", DRAWIO_MIME_TYPE),
            inventory = inventory(),
            capabilities = NextcloudDocumentEditingCapabilities.Unavailable,
        )
        val readOnly = planIntegratedFileHandoff(
            file = file("Diagrams/system.drawio", DRAWIO_MIME_TYPE, permissions = "RG"),
            inventory = inventory(DRAWIO_APP_ID),
            capabilities = NextcloudDocumentEditingCapabilities.Unavailable,
        )

        assertEquals(
            IntegratedFileBlockedReason.MissingApp,
            assertIs<IntegratedFileHandoffPlan.Blocked>(missing).reason,
        )
        assertEquals(
            IntegratedFileBlockedReason.ReadOnly,
            assertIs<IntegratedFileHandoffPlan.Blocked>(readOnly).reason,
        )
    }

    @Test
    fun officeNavigationUsesRichdocumentsCapabilityWithoutRewritingCanonicalIdentity() {
        val office = NextcloudAppEntry("office", "Office", "/index.php/apps/office")
        val plan = planIntegrationAppExperience(
            app = office,
            inventory = inventory("office", OFFICE_CAPABILITY_APP_ID),
            capabilities = capabilities(OFFICE_CAPABILITY_APP_ID, DOCX_MIME),
        )

        assertEquals(
            IntegrationAppExperience.Ready(OFFICE_CAPABILITY_APP_ID),
            plan,
        )
        assertEquals("office", office.canonicalAppStoreId())
    }

    @Test
    fun githubRemainsAuthenticationRequiredUntilAUserSafeAuthSignalExists() {
        assertEquals(
            IntegrationAppExperience.AuthenticationRequired(GITHUB_INTEGRATION_APP_ID),
            planIntegrationAppExperience(
                NextcloudAppEntry(GITHUB_INTEGRATION_APP_ID, "GitHub", null),
                inventory(GITHUB_INTEGRATION_APP_ID),
                NextcloudDocumentEditingCapabilities.Unavailable,
            ),
        )
        assertIs<IntegrationAppExperience.Unsupported>(
            planIntegrationAppExperience(
                NextcloudAppEntry(GITHUB_NAVIGATION_ALIAS, "GitHub", null),
                inventory(),
                NextcloudDocumentEditingCapabilities.Unavailable,
            ),
        )
    }

    private fun inventory(vararg appIds: String) =
        NextcloudIntegrationInventory(appIds.toSet())

    private fun capabilities(
        editorId: String,
        mimeType: String,
        secure: Boolean = true,
    ) = NextcloudDocumentEditingCapabilities(
        editors = mapOf(
            editorId to NextcloudDocumentEditorCapability(
                id = editorId,
                displayName = editorId,
                mimeTypes = setOf(mimeType),
                optionalMimeTypes = emptySet(),
                secure = secure,
            ),
        ),
        creators = emptyMap(),
        supportsFileId = true,
    )

    private fun file(
        path: String,
        mimeType: String,
        permissions: String? = "RGDNVW",
    ) = NextcloudFile(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = false,
        mimeType = mimeType,
        size = 100,
        lastModified = null,
        fileId = 42,
        hasPreview = true,
        etag = "version-1",
        permissions = permissions,
    )

    private companion object {
        const val DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
