package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
data class TextEditorDraft(
    val originalText: String,
    val text: String,
    val etag: String?,
    val verificationRequired: Boolean = false,
)

/** Implementations must encrypt private content, bind account and path, and remove it on account retirement. */
interface TextEditorDraftStore {
    suspend fun load(): TextEditorDraft?
    suspend fun save(draft: TextEditorDraft)
    suspend fun remove()
}

interface TextEditorDraftPlatformServices {
    fun textEditorDraftStore(session: NextcloudSession, path: String): TextEditorDraftStore? = null
}

/** Owns revision readiness separately from content confirmation and persists recovery before PUT. */
internal class TextEditorState(
    private val store: TextEditorDraftStore?,
    private val download: suspend () -> NextcloudFileContent,
    private val upload: suspend (String, String) -> SavedTextFile,
    // The Files listing revision is a conflict-safe fallback when the download omits its ETag.
    private val listingEtag: String? = null,
) {
    var originalText by mutableStateOf<String?>(null)
        private set
    var draft by mutableStateOf("")
        private set
    var etag by mutableStateOf<String?>(null)
        private set
    var loadingError by mutableStateOf<String?>(null)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set
    var savedMessage by mutableStateOf<String?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    var verifying by mutableStateOf(false)
        private set
    private var verificationRequired = false
    private val persistence = Mutex()
    val dirty get() = originalText != null && draft != originalText
    val canSave get() = dirty && !saving && !verifying && !verificationRequired && !etag.isNullOrBlank()

    suspend fun open() {
        filesRequest {
            val restored = store?.load()
            currentCoroutineContext().ensureActive()
            if (restored != null) {
                originalText = restored.originalText
                draft = restored.text
                etag = restored.etag
                verificationRequired = restored.verificationRequired
                if (verificationRequired) verifyRevision()
            } else {
                val remote = download()
                currentCoroutineContext().ensureActive()
                val text = remote.bytes.decodeToString(throwOnInvalidSequence = true)
                originalText = text
                draft = text
                etag = remote.etag?.takeIf(String::isNotBlank) ?: listingEtag?.takeIf(String::isNotBlank)
            }
        }.onFailure { loadingError = "Could not open this text file safely." }
    }

    fun edit(text: String) {
        if (saving || verifying) return
        draft = text
        saveError = null
        savedMessage = null
    }

    suspend fun persist() {
        persistence.withLock {
            val original = originalText ?: return
            if (!dirty && !verificationRequired) store?.remove()
            else store?.save(TextEditorDraft(original, draft, etag, verificationRequired))
        }
    }

    suspend fun persistEdits() {
        filesRequest { persist() }.onFailure { saveError = "Could not keep a recovery copy of your edits." }
    }

    suspend fun discard(): Boolean = filesRequest {
        persistence.withLock {
            store?.remove()
            draft = originalText.orEmpty()
            verificationRequired = false
            saveError = null
            savedMessage = null
        }
    }.fold(
        onSuccess = { true },
        onFailure = {
            saveError = "Could not discard the recovery copy. Your edits are kept."
            false
        },
    )

    suspend fun save() {
        if (!canSave) return
        val submitted = draft
        val expected = requireNotNull(etag)
        saving = true
        saveError = null
        // A crash after this checkpoint must verify the server before another write.
        verificationRequired = true
        try {
            persist()
        } catch (cancelled: CancellationException) {
            saving = false
            throw cancelled
        } catch (_: Exception) {
            // Nothing was sent, so the known revision stays usable once local storage recovers.
            verificationRequired = false
            saveError = "Could not keep a recovery copy, so nothing was saved. Your edits are kept."
            saving = false
            return
        }
        try {
            val saved = upload(submitted, expected)
            originalText = submitted
            etag = saved.etag?.takeIf(String::isNotBlank)
            verificationRequired = etag == null
            savedMessage = if (verificationRequired) "Saved. Verify the server version before saving again." else "Saved to Nextcloud"
            persist()
        } catch (cancelled: CancellationException) {
            etag = null
            throw cancelled
        } catch (_: TextFileDavSaveConflictException) {
            etag = null
            verificationRequired = true
            saveError = "The server file changed. Your edits are kept; verify the server version before saving again."
        } catch (_: Exception) {
            etag = null
            verificationRequired = true
            saveError = "The save could not be confirmed. Verify the server version before saving again."
        } finally {
            saving = false
        }
    }

    suspend fun verifyRevision() {
        if (saving || verifying) return
        verifying = true
        if (verificationRequired) etag = null
        try {
            filesRequest {
                val remote = download()
                currentCoroutineContext().ensureActive()
                val remoteText = remote.bytes.decodeToString(throwOnInvalidSequence = true)
                val revision = remote.etag?.takeIf(String::isNotBlank)
                when {
                    remoteText == draft -> {
                        originalText = draft
                        etag = revision
                        verificationRequired = revision == null
                        savedMessage = if (revision != null) "Saved content verified" else null
                        saveError = null
                    }
                    remoteText == originalText -> {
                        etag = revision
                        verificationRequired = revision == null
                        savedMessage = null
                        saveError = null
                    }
                    else -> {
                        etag = null
                        verificationRequired = true
                        saveError = "The server file changed. Your edits are kept; resolve the changes before saving."
                    }
                }
                persist()
            }.onFailure { saveError = "Could not verify the server version. Your edits are kept." }
        } finally {
            verifying = false
        }
    }
}
