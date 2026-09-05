package dev.obiente.nextcloudnative

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.ActivityResultLauncher
import dev.obiente.nextcloudnative.app.LocalUploadFile
import dev.obiente.nextcloudnative.app.LocalUploadSelectionResult
import dev.obiente.nextcloudnative.app.isAcceptedUploadMimeType
import dev.obiente.nextcloudnative.app.localUploadFile
import dev.obiente.nextcloudnative.app.requireSafeUploadPickerRequest
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Single-flight bridge to Android's OpenDocument picker.
 *
 * Selected content URIs remain private to this platform class. Common code receives only an
 * opaque token and validated metadata. Read grants and encrypted capability metadata are persisted
 * so a user-approved background upload can survive activity and process recreation.
 */
internal class AndroidLocalUploadPicker(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = context.applicationContext.contentResolver
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val cipher = SessionCipher()
    private val selections = ConcurrentHashMap<String, SelectedSource>()
    private var launcher: ActivityResultLauncher<Array<String>>? = null
    private var pending: PendingSelection? = null

    fun attach(launcher: ActivityResultLauncher<Array<String>>) {
        check(this.launcher == null) { "The local file picker is already attached." }
        this.launcher = launcher
    }

    suspend fun choose(
        acceptedMimeTypes: List<String>,
        maximumBytes: Long,
    ): LocalUploadSelectionResult = suspendCancellableCoroutine { continuation ->
        val accepted = requireSafeUploadPickerRequest(acceptedMimeTypes, maximumBytes)
        check(pending == null) { "A local file picker is already open." }
        val activeLauncher = checkNotNull(launcher) { "The local file picker is not attached." }
        val selection = PendingSelection(continuation, accepted, maximumBytes)
        pending = selection
        continuation.invokeOnCancellation {
            if (pending === selection) pending = null
        }
        activeLauncher.launch(accepted.toTypedArray())
    }

    fun complete(uri: Uri?) {
        val selection = pending ?: return
        pending = null
        if (!selection.continuation.isActive) return
        if (uri == null) {
            selection.continuation.resume(LocalUploadSelectionResult.Cancelled)
            return
        }
        val result = runCatching {
            val metadata = resolver.queryUploadMetadata(uri)
            val mimeType = resolver.getType(uri)?.trim()?.lowercase()?.takeIf(String::isNotBlank)
            if (!isAcceptedUploadMimeType(mimeType, selection.acceptedMimeTypes)) {
                return@runCatching LocalUploadSelectionResult.Rejected(
                    "The selected file type is not accepted.",
                )
            }
            if (metadata.sizeBytes != null && metadata.sizeBytes > selection.maximumBytes) {
                return@runCatching LocalUploadSelectionResult.Rejected(
                    "The selected file is larger than the allowed upload limit.",
                )
            }
            val token = UUID.randomUUID().toString()
            val file = localUploadFile(
                selectionId = token,
                displayName = metadata.displayName,
                mimeType = mimeType,
                sizeBytes = metadata.sizeBytes,
            )
            runCatching {
                acquireDurableUploadCapability(
                    takePermission = {
                        resolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    },
                    persistMetadata = { persist(source = SelectedSource(uri, file)) },
                    releasePermission = {
                        resolver.releasePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    },
                )
            }.getOrElse {
                return@runCatching LocalUploadSelectionResult.Rejected(
                    "The selected file provider cannot keep access for a background upload.",
                )
            }
            val source = SelectedSource(uri, file)
            selections[token] = source
            LocalUploadSelectionResult.Selected(file)
        }.getOrElse {
            LocalUploadSelectionResult.Rejected(
                "The selected file could not be opened.",
            )
        }
        selection.continuation.resume(result)
    }

    fun open(file: LocalUploadFile): InputStream {
        val source = persistedSource(file).also {
            selections[file.selectionId] = it
        }
        return checkNotNull(resolver.openInputStream(source.uri)) {
            "The selected file could not be opened."
        }
    }

    fun requirePersisted(file: LocalUploadFile) {
        requiredSource(file, useCachedSource = false)
    }

    fun release(file: LocalUploadFile): Boolean {
        return releaseStoredDurableUploadCapability(
            cachedCapability = selections[file.selectionId],
            loadCapability = { load(file.selectionId) },
            releasePermission = { source ->
                resolver.releasePersistableUriPermission(
                    source.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            },
            removeMetadata = {
                preferences.edit()
                    .remove(preferenceKey(file.selectionId))
                    .commit()
            },
        ).also { released ->
            if (released) selections.remove(file.selectionId)
        }
    }

    private fun persist(source: SelectedSource): Boolean {
        val payload = JSONObject()
            .put("uri", source.uri.toString())
            .put("selectionId", source.file.selectionId)
            .put("displayName", source.file.displayName)
            .put("mimeType", source.file.mimeType)
            .put("sizeBytes", source.file.sizeBytes)
            .toString()
        return preferences.edit()
            .putString(preferenceKey(source.file.selectionId), cipher.encrypt(payload))
            .commit()
    }

    private fun persistedSource(file: LocalUploadFile): SelectedSource {
        return requiredSource(file, useCachedSource = true)
    }

    private fun requiredSource(
        file: LocalUploadFile,
        useCachedSource: Boolean,
    ): SelectedSource {
        val source = try {
            selections[file.selectionId].takeIf { useCachedSource } ?: load(file.selectionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw AndroidLocalUploadCapabilityUnavailableException(
                "The local file selection metadata could not be read.",
                failure,
            )
        } ?: throw AndroidLocalUploadCapabilityUnavailableException(
            "The local file selection was not durably saved.",
        )
        if (source.file != file) {
            throw AndroidLocalUploadCapabilityUnavailableException(
                "The persisted local file metadata changed.",
            )
        }
        return source
    }

    private fun load(selectionId: String): SelectedSource? {
        val encrypted = preferences.getString(preferenceKey(selectionId), null) ?: return null
        val payload = JSONObject(cipher.decrypt(encrypted))
        val file = localUploadFile(
            selectionId = payload.getString("selectionId"),
            displayName = payload.getString("displayName"),
            mimeType = if (payload.isNull("mimeType")) null else payload.getString("mimeType"),
            sizeBytes = if (payload.isNull("sizeBytes")) null else payload.getLong("sizeBytes"),
        )
        require(file.selectionId == selectionId) { "The persisted upload capability changed." }
        return SelectedSource(Uri.parse(payload.getString("uri")), file)
    }

    private fun preferenceKey(selectionId: String): String = "$PREFERENCE_PREFIX$selectionId"

    private data class PendingSelection(
        val continuation: CancellableContinuation<LocalUploadSelectionResult>,
        val acceptedMimeTypes: List<String>,
        val maximumBytes: Long,
    )

    private data class SelectedSource(
        val uri: Uri,
        val file: LocalUploadFile,
    )

    private companion object {
        const val PREFERENCES = "nextcloud_native_upload_capabilities"
        const val PREFERENCE_PREFIX = "upload_"
    }
}

internal class AndroidLocalUploadCapabilityUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Acquires a durable picker capability without exposing an interval where a successful selection
 * can be reported before its metadata reaches app-private storage.
 */
internal fun acquireDurableUploadCapability(
    takePermission: () -> Unit,
    persistMetadata: () -> Boolean,
    releasePermission: () -> Unit,
) {
    takePermission()
    val persisted = runCatching { persistMetadata() }
    if (persisted.getOrNull() == true) return
    runCatching(releasePermission)
    persisted.exceptionOrNull()?.let { throw it }
    error("The durable upload capability could not be saved.")
}

/**
 * Revokes the URI grant before synchronously deleting capability metadata. A failed grant release
 * is still followed by metadata deletion because Android also throws when the grant was already
 * absent; in either case the app must not retain an indefinitely reusable picker capability.
 */
internal fun releaseDurableUploadCapability(
    releasePermission: () -> Unit,
    removeMetadata: () -> Boolean,
): Boolean {
    runCatching(releasePermission)
    return runCatching(removeMetadata).getOrDefault(false)
}

internal fun <Capability> releaseStoredDurableUploadCapability(
    cachedCapability: Capability?,
    loadCapability: () -> Capability?,
    releasePermission: (Capability) -> Unit,
    removeMetadata: () -> Boolean,
): Boolean {
    val capability = cachedCapability ?: try {
        loadCapability()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return false
    }
    return releaseDurableUploadCapability(
        releasePermission = { capability?.let(releasePermission) },
        removeMetadata = removeMetadata,
    )
}

private data class AndroidUploadMetadata(
    val displayName: String,
    val sizeBytes: Long?,
)

private fun ContentResolver.queryUploadMetadata(uri: Uri): AndroidUploadMetadata =
    query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
    )?.use { cursor ->
        check(cursor.moveToFirst()) { "The selected file has no metadata." }
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        val displayName = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
            cursor.getString(nameIndex)
        } else {
            ""
        }
        val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
            cursor.getLong(sizeIndex).takeIf { it >= 0L }
        } else {
            null
        }
        AndroidUploadMetadata(
            displayName = displayName?.trim().orEmpty().ifBlank { "upload.bin" },
            sizeBytes = size,
        )
    } ?: error("The selected file provider returned no metadata.")
