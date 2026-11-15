package dev.obiente.nextcloudnative.app

/**
 * Durable, account- and file-scoped record of the one comment post whose result is not known.
 * Posting is not idempotent, so the comment is recorded before its request leaves the device and
 * stays on record, across leaving the file and restarting the app, until its result is learned.
 */
interface FileCommentPostAttemptStore {
    /** The comment text of the unresolved post, or null when there is none. */
    suspend fun load(): String?

    suspend fun save(message: String)

    suspend fun clear()
}

/**
 * The record holds the comment text, so it goes to the platform's encrypted text draft storage,
 * which binds it to the account and deletes it when the account is removed. Null when the
 * platform keeps no draft storage.
 */
internal fun fileCommentPostAttemptStore(
    services: TextEditorDraftPlatformServices,
    session: NextcloudSession,
    fileId: Long,
): FileCommentPostAttemptStore? {
    val path = "$FILE_COMMENT_POST_PATH_PREFIX$fileId"
    return services.textEditorDraftStore(session, path)?.let {
        TextDraftBackedFileCommentPostAttemptStore { services.textEditorDraftStore(session, path) }
    }
}

/** Stores the unresolved comment as one encrypted text draft record. */
internal class TextDraftBackedFileCommentPostAttemptStore(
    private val bind: () -> TextEditorDraftStore?,
) : FileCommentPostAttemptStore {
    override suspend fun load(): String? = bind()?.load()?.text?.trim()?.takeIf(String::isNotEmpty)

    override suspend fun save(message: String) {
        val store = checkNotNull(bind()) { "Comment attempt storage is unavailable." }
        store.save(TextEditorDraft(originalText = "", text = message, etag = null))
    }

    override suspend fun clear() {
        bind()?.remove()
    }
}

private const val FILE_COMMENT_POST_PATH_PREFIX = "nextcloud-native:file-comment-post:"
