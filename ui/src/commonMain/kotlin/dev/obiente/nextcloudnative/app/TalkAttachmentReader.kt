package dev.obiente.nextcloudnative.app

data class TalkAttachmentReadPolicy(
    val maxInMemoryBytes: Long = DEFAULT_TALK_ATTACHMENT_IN_MEMORY_BYTES,
) {
    init {
        require(maxInMemoryBytes in 1..MAX_TALK_ATTACHMENT_IN_MEMORY_BYTES)
    }
}

enum class TalkAttachmentReadUnavailableReason {
    DownloadHidden,
    MissingPath,
    InMemoryReadTooLarge,
}

sealed interface TalkAttachmentReadResult {
    data class Content(val file: NextcloudFileContent) : TalkAttachmentReadResult

    data class Unavailable(val reason: TalkAttachmentReadUnavailableReason) : TalkAttachmentReadResult
}

interface TalkAttachmentDownloadBackend {
    suspend fun download(path: String, maxBytes: Long): NextcloudFileContent
}

class NextcloudTalkAttachmentDownloadBackend(
    private val services: NextcloudPlatformServices,
    private val session: NextcloudSession,
    private val userId: String,
) : TalkAttachmentDownloadBackend {
    override suspend fun download(path: String, maxBytes: Long): NextcloudFileContent = services.downloadFile(
        session = session,
        userId = userId,
        path = path,
        maxBytes = maxBytes,
    )
}

/** Bounded in-memory reader for previews. Explicit save/open actions must use a streaming handoff. */
class TalkAttachmentReader(
    private val backend: TalkAttachmentDownloadBackend,
    private val policy: TalkAttachmentReadPolicy = TalkAttachmentReadPolicy(),
) {
    suspend fun read(model: TalkAttachmentRenderModel): TalkAttachmentReadResult {
        if (model.attachment.hideDownload) {
            return TalkAttachmentReadResult.Unavailable(TalkAttachmentReadUnavailableReason.DownloadHidden)
        }
        val path = model.attachment.path?.takeIf(String::isNotBlank)
            ?: return TalkAttachmentReadResult.Unavailable(TalkAttachmentReadUnavailableReason.MissingPath)
        if (model.attachment.size != null && model.attachment.size > policy.maxInMemoryBytes) {
            return TalkAttachmentReadResult.Unavailable(TalkAttachmentReadUnavailableReason.InMemoryReadTooLarge)
        }

        val content = backend.download(path, policy.maxInMemoryBytes)
        if (content.bytes.size.toLong() > policy.maxInMemoryBytes) {
            return TalkAttachmentReadResult.Unavailable(TalkAttachmentReadUnavailableReason.InMemoryReadTooLarge)
        }
        return TalkAttachmentReadResult.Content(content)
    }
}

const val DEFAULT_TALK_ATTACHMENT_IN_MEMORY_BYTES = 64L * 1024L * 1024L
const val MAX_TALK_ATTACHMENT_IN_MEMORY_BYTES = 256L * 1024L * 1024L
