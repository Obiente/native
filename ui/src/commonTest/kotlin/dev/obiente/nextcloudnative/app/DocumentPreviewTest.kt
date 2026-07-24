package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DocumentPreviewTest {
    @Test
    fun classifiesOfficeFamiliesFromCanonicalMimeTypes() {
        val word = describeDocument(file("proposal.docx", DOCX_MIME, hasPreview = true))
        val sheet = describeDocument(file("budget.ods", ODS_MIME, hasPreview = true))
        val slides = describeDocument(file("briefing.pptx", PPTX_MIME, hasPreview = true))
        val drawing = describeDocument(file("diagram.vsdx", "application/vnd.ms-visio.drawing", hasPreview = true))

        assertEquals(DocumentKind.WordProcessing, word.kind)
        assertEquals(DocumentKind.Spreadsheet, sheet.kind)
        assertEquals(DocumentKind.Presentation, slides.kind)
        assertEquals(DocumentKind.Drawing, drawing.kind)
        assertEquals(DocumentPreviewMethod.ServerRaster, word.method)
        assertTrue(word.officeEditable)
    }

    @Test
    fun usesExtensionOnlyWhenMimeTypeIsMissingOrGeneric() {
        assertEquals(
            DocumentKind.Pdf,
            describeDocument(file("manual.pdf", "application/octet-stream", hasPreview = true)).kind,
        )
        assertEquals(
            DocumentKind.Other,
            describeDocument(file("misleading.pdf", "image/jpeg", hasPreview = true)).kind,
        )
    }

    @Test
    fun routesMarkdownThroughBoundedNativeRenderer() {
        val descriptor = describeDocument(file("README.md", "text/markdown"))

        assertEquals(DocumentKind.Markdown, descriptor.kind)
        assertEquals(DocumentPreviewMethod.NativeText, descriptor.method)
        assertFalse(descriptor.officeEditable)
        assertEquals(
            NativeTextPresentation.RenderedMarkdown,
            planNativeTextPresentation(descriptor, utf8Bytes = 1024),
        )
    }

    @Test
    fun fallsBackToRawMarkdownSourceBeyondRendererLimit() {
        val descriptor = describeDocument(file("README.md", "application/octet-stream"))

        assertEquals(
            NativeTextPresentation.MarkdownSourceOnly,
            planNativeTextPresentation(
                descriptor = descriptor,
                utf8Bytes = 65,
                maxRenderedMarkdownBytes = 64,
            ),
        )
    }

    @Test
    fun plainTextNeverEntersMarkdownRenderer() {
        val descriptor = describeDocument(file("server.log", "text/plain"))

        assertEquals(
            NativeTextPresentation.LiteralText,
            planNativeTextPresentation(
                descriptor = descriptor,
                utf8Bytes = MAX_RENDERED_MARKDOWN_PREVIEW_BYTES + 1,
            ),
        )
    }

    @Test
    fun classifiesRegisteredDiagramAndWhiteboardMimeTypesWithoutMakingThemOfficeEditable() {
        val diagram = describeDocument(file("system.drawio", DRAWIO_MIME_TYPE, hasPreview = true))
        val whiteboard = describeDocument(file("plan.excalidraw", WHITEBOARD_MIME_TYPE, hasPreview = true))

        assertEquals(DocumentKind.Diagram, diagram.kind)
        assertEquals(DocumentKind.Whiteboard, whiteboard.kind)
        assertEquals(DocumentPreviewMethod.ServerRaster, diagram.method)
        assertEquals(DocumentPreviewMethod.ServerRaster, whiteboard.method)
        assertFalse(diagram.officeEditable)
        assertFalse(whiteboard.officeEditable)
    }

    @Test
    fun rejectsMalformedUtf8InsteadOfReplacingBytes() {
        assertEquals(null, decodeDocumentText(byteArrayOf(0xC3.toByte(), 0x28)))
    }

    @Test
    fun doesNotDownloadTextKnownToExceedPolicy() = runBlocking {
        val backend = FakeDocumentPreviewBackend()
        val preview = DocumentPreviewLoader(
            backend,
            DocumentPreviewPolicy(maxTextBytes = 32),
        ).load(file("large.txt", "text/plain", size = 33))

        assertEquals(0, backend.downloadCalls)
        assertEquals(
            DocumentPreviewUnavailableReason.FileTooLarge,
            assertIs<DocumentPreview.Unavailable>(preview).reason,
        )
    }

    @Test
    fun doesNotDownloadBinaryOfficeDocumentWhenServerPreviewIsUnavailable() = runBlocking {
        val backend = FakeDocumentPreviewBackend()
        val preview = DocumentPreviewLoader(backend).load(
            file("proposal.docx", DOCX_MIME, hasPreview = false),
        )

        assertEquals(0, backend.downloadCalls)
        assertEquals(0, backend.rasterCalls)
        assertEquals(
            DocumentPreviewUnavailableReason.ServerPreviewUnavailable,
            assertIs<DocumentPreview.Unavailable>(preview).reason,
        )
    }

    @Test
    fun loadsBoundedServerRasterForOfficeDocument() = runBlocking {
        val backend = FakeDocumentPreviewBackend(raster = byteArrayOf(1, 2, 3))
        val preview = DocumentPreviewLoader(backend).load(
            file("proposal.docx", DOCX_MIME, hasPreview = true),
        )

        assertEquals(1, backend.rasterCalls)
        assertTrue(assertIs<DocumentPreview.Raster>(preview).firstPageOnly)
    }

    @Test
    fun metadataSummaryShowsTypeSizePermissionAndVersionWithoutEtagValue() {
        val summary = documentMetadataSummary(
            file(
                "proposal.docx",
                DOCX_MIME,
                size = 4096,
                hasPreview = true,
                permissions = "RGDNVW",
            ),
        )

        assertTrue("Document" in summary)
        assertTrue("4 KiB" in summary)
        assertTrue("Writable" in summary)
        assertTrue("Versioned" in summary)
        assertFalse("etag" in summary)
    }

    private class FakeDocumentPreviewBackend(
        private val text: ByteArray = "hello".encodeToByteArray(),
        private val raster: ByteArray = byteArrayOf(1),
    ) : DocumentPreviewBackend {
        var downloadCalls = 0
        var rasterCalls = 0

        override suspend fun downloadTextCandidate(file: NextcloudFile, maxBytes: Long): NextcloudFileContent {
            downloadCalls += 1
            return NextcloudFileContent(text, file.mimeType, file.etag)
        }

        override suspend fun loadRasterPreview(file: NextcloudFile, width: Int, height: Int): ByteArray {
            rasterCalls += 1
            return raster
        }
    }

    private fun file(
        name: String,
        mimeType: String?,
        size: Long = 10,
        hasPreview: Boolean = false,
        permissions: String? = null,
    ) = NextcloudFile(
        path = "Documents/$name",
        name = name,
        isDirectory = false,
        mimeType = mimeType,
        size = size,
        lastModified = null,
        fileId = 42,
        hasPreview = hasPreview,
        etag = "etag",
        permissions = permissions,
    )

    private companion object {
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val ODS_MIME = "application/vnd.oasis.opendocument.spreadsheet"
        const val PPTX_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    }
}
