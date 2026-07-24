package dev.obiente.nextcloudnative.app

/** A document family used to select native presentation without trusting a file extension alone. */
enum class DocumentKind {
    PlainText,
    Markdown,
    Pdf,
    WordProcessing,
    Spreadsheet,
    Presentation,
    Drawing,
    Diagram,
    Whiteboard,
    Other,
}

/**
 * The only preview mechanisms supported by the native foundation.
 *
 * Neither mechanism executes document content. UTF-8 text stays native, Markdown is parsed by the
 * shared Compose renderer within a stricter size bound, and server previews are decoded as bounded
 * raster images. Office/WOPI HTML is deliberately not one of the strategies.
 */
enum class DocumentPreviewMethod {
    NativeText,
    ServerRaster,
    Unsupported,
}

/** Presentation selected from the document model rather than a Nextcloud app identifier. */
internal enum class NativeTextPresentation {
    LiteralText,
    RenderedMarkdown,
    MarkdownSourceOnly,
}

internal fun planNativeTextPresentation(
    descriptor: DocumentDescriptor,
    utf8Bytes: Long,
    maxRenderedMarkdownBytes: Long = MAX_RENDERED_MARKDOWN_PREVIEW_BYTES,
): NativeTextPresentation {
    require(utf8Bytes >= 0)
    require(maxRenderedMarkdownBytes > 0)
    return when {
        descriptor.kind != DocumentKind.Markdown -> NativeTextPresentation.LiteralText
        utf8Bytes <= maxRenderedMarkdownBytes -> NativeTextPresentation.RenderedMarkdown
        else -> NativeTextPresentation.MarkdownSourceOnly
    }
}

data class DocumentDescriptor(
    val kind: DocumentKind,
    val method: DocumentPreviewMethod,
    val mimeType: String?,
    val officeEditable: Boolean,
)

data class DocumentPreviewPolicy(
    val maxTextBytes: Long = DEFAULT_DOCUMENT_TEXT_PREVIEW_LIMIT_BYTES,
    val rasterWidth: Int = DEFAULT_DOCUMENT_PREVIEW_WIDTH,
    val rasterHeight: Int = DEFAULT_DOCUMENT_PREVIEW_HEIGHT,
) {
    init {
        require(maxTextBytes in 1..MAX_DOCUMENT_TEXT_PREVIEW_LIMIT_BYTES)
        require(rasterWidth in MIN_PREVIEW_DIMENSION..MAX_PREVIEW_DIMENSION)
        require(rasterHeight in MIN_PREVIEW_DIMENSION..MAX_PREVIEW_DIMENSION)
    }
}

sealed interface DocumentPreview {
    val descriptor: DocumentDescriptor

    data class Text(
        override val descriptor: DocumentDescriptor,
        val value: String,
    ) : DocumentPreview

    class Raster(
        override val descriptor: DocumentDescriptor,
        val encodedImage: ByteArray,
        /** Office/PDF conversion currently represents a page-sized thumbnail, not a full document. */
        val firstPageOnly: Boolean,
    ) : DocumentPreview

    data class Unavailable(
        override val descriptor: DocumentDescriptor,
        val reason: DocumentPreviewUnavailableReason,
    ) : DocumentPreview
}

enum class DocumentPreviewUnavailableReason {
    Directory,
    UnsupportedType,
    FileTooLarge,
    InvalidTextEncoding,
    MissingFileId,
    ServerPreviewUnavailable,
    InvalidServerPreview,
}

/** Minimal transport boundary so document policy can be tested without a platform HTTP client. */
interface DocumentPreviewBackend {
    suspend fun downloadTextCandidate(file: NextcloudFile, maxBytes: Long): NextcloudFileContent

    suspend fun loadRasterPreview(file: NextcloudFile, width: Int, height: Int): ByteArray
}

class NextcloudDocumentPreviewBackend(
    private val services: NextcloudPlatformServices,
    private val session: NextcloudSession,
    private val userId: String,
) : DocumentPreviewBackend {
    override suspend fun downloadTextCandidate(file: NextcloudFile, maxBytes: Long): NextcloudFileContent =
        services.downloadFile(
            session = session,
            userId = userId,
            path = file.path,
            maxBytes = maxBytes,
        )

    override suspend fun loadRasterPreview(file: NextcloudFile, width: Int, height: Int): ByteArray =
        services.loadPreviewCached(session, file, width, height)
}

class DocumentPreviewLoader(
    private val backend: DocumentPreviewBackend,
    private val policy: DocumentPreviewPolicy = DocumentPreviewPolicy(),
) {
    suspend fun load(file: NextcloudFile): DocumentPreview {
        val descriptor = describeDocument(file)
        if (file.isDirectory) {
            return DocumentPreview.Unavailable(descriptor, DocumentPreviewUnavailableReason.Directory)
        }

        return when (descriptor.method) {
            DocumentPreviewMethod.NativeText -> loadText(file, descriptor)
            DocumentPreviewMethod.ServerRaster -> loadRaster(file, descriptor)
            DocumentPreviewMethod.Unsupported -> DocumentPreview.Unavailable(
                descriptor,
                DocumentPreviewUnavailableReason.UnsupportedType,
            )
        }
    }

    private suspend fun loadText(file: NextcloudFile, descriptor: DocumentDescriptor): DocumentPreview {
        if (file.size != null && file.size > policy.maxTextBytes) {
            return DocumentPreview.Unavailable(descriptor, DocumentPreviewUnavailableReason.FileTooLarge)
        }

        val content = backend.downloadTextCandidate(file, policy.maxTextBytes)
        if (content.bytes.size.toLong() > policy.maxTextBytes) {
            return DocumentPreview.Unavailable(descriptor, DocumentPreviewUnavailableReason.FileTooLarge)
        }

        val text = decodeDocumentText(content.bytes)
            ?: return DocumentPreview.Unavailable(descriptor, DocumentPreviewUnavailableReason.InvalidTextEncoding)
        return DocumentPreview.Text(descriptor, text)
    }

    private suspend fun loadRaster(file: NextcloudFile, descriptor: DocumentDescriptor): DocumentPreview {
        if (file.fileId == null) {
            return DocumentPreview.Unavailable(descriptor, DocumentPreviewUnavailableReason.MissingFileId)
        }
        if (!file.hasPreview) {
            return DocumentPreview.Unavailable(descriptor, DocumentPreviewUnavailableReason.ServerPreviewUnavailable)
        }

        val bytes = backend.loadRasterPreview(file, policy.rasterWidth, policy.rasterHeight)
        if (bytes.isEmpty()) {
            return DocumentPreview.Unavailable(descriptor, DocumentPreviewUnavailableReason.InvalidServerPreview)
        }
        return DocumentPreview.Raster(
            descriptor = descriptor,
            encodedImage = bytes,
            firstPageOnly = descriptor.kind in rasterDocumentKinds,
        )
    }
}

fun describeDocument(file: NextcloudFile): DocumentDescriptor {
    val mimeType = file.mimeType?.substringBefore(';')?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
    val extension = file.name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    val kind = kindForMimeType(mimeType) ?: kindForExtension(extension)
    val method = when {
        kind == DocumentKind.PlainText || kind == DocumentKind.Markdown -> DocumentPreviewMethod.NativeText
        kind == DocumentKind.Spreadsheet && (mimeType == "text/csv" || extension == "csv") ->
            DocumentPreviewMethod.NativeText
        kind in rasterDocumentKinds -> DocumentPreviewMethod.ServerRaster
        else -> DocumentPreviewMethod.Unsupported
    }
    return DocumentDescriptor(
        kind = kind,
        method = method,
        mimeType = mimeType,
        officeEditable = kind in officeEditableKinds,
    )
}

/** Strict UTF-8 prevents platform-dependent replacement of malformed document bytes. */
internal fun decodeDocumentText(bytes: ByteArray): String? = runCatching {
    bytes.decodeToString(throwOnInvalidSequence = true)
}.getOrNull()

private fun kindForMimeType(mimeType: String?): DocumentKind? = when {
    mimeType == null || mimeType == "application/octet-stream" -> null
    mimeType in markdownMimeTypes -> DocumentKind.Markdown
    mimeType == "text/csv" || mimeType == "text/spreadsheet" -> DocumentKind.Spreadsheet
    mimeType in pdfMimeTypes -> DocumentKind.Pdf
    mimeType in wordProcessingMimeTypes || mimeType.startsWith("application/vnd.oasis.opendocument.text") ->
        DocumentKind.WordProcessing
    mimeType in spreadsheetMimeTypes || mimeType.startsWith("application/vnd.oasis.opendocument.spreadsheet") ->
        DocumentKind.Spreadsheet
    mimeType in presentationMimeTypes || mimeType.startsWith("application/vnd.oasis.opendocument.presentation") ->
        DocumentKind.Presentation
    mimeType in diagramMimeTypes -> DocumentKind.Diagram
    mimeType in whiteboardMimeTypes -> DocumentKind.Whiteboard
    mimeType in drawingMimeTypes || mimeType.startsWith("application/vnd.oasis.opendocument.graphics") ->
        DocumentKind.Drawing
    mimeType.startsWith("text/") || mimeType in safeTextApplicationMimeTypes -> DocumentKind.PlainText
    else -> DocumentKind.Other
}

private fun kindForExtension(extension: String): DocumentKind = when (extension) {
    "md", "markdown", "mdown", "mkd" -> DocumentKind.Markdown
    "txt", "text", "log", "json", "xml", "yaml", "yml", "toml" -> DocumentKind.PlainText
    "pdf" -> DocumentKind.Pdf
    "odt", "ott", "doc", "docx", "dot", "dotx", "rtf", "pages", "wpd" -> DocumentKind.WordProcessing
    "csv", "ods", "ots", "xls", "xlsx", "xlsm", "numbers" -> DocumentKind.Spreadsheet
    "odp", "otp", "ppt", "pptx", "pptm", "key" -> DocumentKind.Presentation
    "odg", "otg", "vsd", "vsdx", "vdx" -> DocumentKind.Drawing
    "drawio" -> DocumentKind.Diagram
    "excalidraw" -> DocumentKind.Whiteboard
    else -> DocumentKind.Other
}

private val markdownMimeTypes = setOf(
    "text/markdown",
    "text/x-markdown",
)

private val safeTextApplicationMimeTypes = setOf(
    "application/json",
    "application/ld+json",
    "application/xml",
    "application/x-yaml",
)

private val pdfMimeTypes = setOf("application/pdf")

private val wordProcessingMimeTypes = setOf(
    "application/msword",
    "application/rtf",
    "text/rtf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
    "application/vnd.ms-word.document.macroenabled.12",
    "application/vnd.ms-word.template.macroenabled.12",
    "application/vnd.lotus-wordpro",
    "application/vnd.wordperfect",
    "application/x-iwork-pages-sffpages",
)

private val spreadsheetMimeTypes = setOf(
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
    "application/vnd.ms-excel.sheet.macroenabled.12",
    "application/vnd.ms-excel.template.macroenabled.12",
    "application/vnd.ms-excel.addin.macroenabled.12",
    "application/vnd.ms-excel.sheet.binary.macroenabled.12",
    "application/x-iwork-numbers-sffnumbers",
)

private val presentationMimeTypes = setOf(
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.openxmlformats-officedocument.presentationml.template",
    "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
    "application/vnd.ms-powerpoint.addin.macroenabled.12",
    "application/vnd.ms-powerpoint.presentation.macroenabled.12",
    "application/vnd.ms-powerpoint.template.macroenabled.12",
    "application/vnd.ms-powerpoint.slideshow.macroenabled.12",
    "application/x-iwork-keynote-sffkey",
)

private val drawingMimeTypes = setOf(
    "application/vnd.visio",
    "application/vnd.ms-visio.drawing",
)

private val diagramMimeTypes = setOf("application/x-drawio")

private val whiteboardMimeTypes = setOf("application/vnd.excalidraw+json")

private val officeEditableKinds = setOf(
    DocumentKind.WordProcessing,
    DocumentKind.Spreadsheet,
    DocumentKind.Presentation,
    DocumentKind.Drawing,
)

private val rasterDocumentKinds = officeEditableKinds +
    setOf(DocumentKind.Pdf, DocumentKind.Diagram, DocumentKind.Whiteboard)

const val DEFAULT_DOCUMENT_TEXT_PREVIEW_LIMIT_BYTES = 2L * 1024L * 1024L
const val MAX_DOCUMENT_TEXT_PREVIEW_LIMIT_BYTES = 8L * 1024L * 1024L
const val MAX_RENDERED_MARKDOWN_PREVIEW_BYTES = 512L * 1024L
const val DEFAULT_DOCUMENT_PREVIEW_WIDTH = 1_600
const val DEFAULT_DOCUMENT_PREVIEW_HEIGHT = 2_048
