package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OfficeDocumentWorkflowTest {
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
