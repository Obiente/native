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
    private val selections = PROCESS_SELECTIONS
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
            runCatching { selection.readyFile?.let(::release) }
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
        val result = runCatching selectionResult@{
            val metadata = resolver.queryUploadMetadata(uri)
            val mimeType = resolver.getType(uri)?.trim()?.lowercase()?.takeIf(String::isNotBlank)
            if (!isAcceptedUploadMimeType(mimeType, selection.acceptedMimeTypes)) {
                return@selectionResult LocalUploadSelectionResult.Rejected(
                    "The selected file type is not accepted.",
                )
            }
            if (metadata.sizeBytes != null && metadata.sizeBytes > selection.maximumBytes) {
                return@selectionResult LocalUploadSelectionResult.Rejected(
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
            val source = SelectedSource(uri, file)
            var cancelledAfterAcquire = false
            val acquisitionFailure = runCatching {
                synchronized(CAPABILITY_LOCK) {
                    val existing = loadCapabilitySnapshot()
                    check(
                        !durableUploadCapabilityPermissionOwnedByAnother(
                            capabilities = existing,
                            targetSelectionId = token,
                            targetPermission = uri,
                            permissionOf = SelectedSource::uri,
                            samePermission = { first, second -> first == second },
                        ),
                    ) {
                        "The selected file already has an active picker capability."
                    }
                    val grantPreExisting = !exactReadPermissionIsAbsent(uri)
                    val acquiring = source.copy(
                        phase = CapabilityPhase.Acquiring,
                        processGeneration = PROCESS_GENERATION,
                        grantPreExisting = grantPreExisting,
                    )
                    val ready = acquiring.copy(phase = CapabilityPhase.Ready)
                    val cleanupPending = acquiring.copy(phase = CapabilityPhase.CleanupPending)
                    acquireDurableUploadCapability(
                        persistAcquiring = { persist(acquiring) },
                        takePermission = {
                            resolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        },
                        persistMetadata = { persist(ready) },
                        markCleanupPending = {
                            PENDING_CLEANUP_SELECTIONS += token
                            persist(cleanupPending)
                        },
                        releasePermission = {
                            if (shouldReleaseDurableUploadPermission(grantPreExisting, false)) {
                                releasePermission(uri)
                            }
                        },
                        isPermissionAbsent = { exactReadPermissionIsAbsent(uri) },
                        removeCapability = { removeMetadata(token) },
                        onRollbackRetained = ::requestQueuedDurableUploadSchedulingRecovery,
                    )
                    cancelledAfterAcquire = !finalizeDurableUploadCapabilityDelivery(
                        publishReady = {
                            selections[token] = ready
                            selection.readyFile = file
                        },
                        continuationIsActive = { selection.continuation.isActive },
                        cleanupUndelivered = { release(file) },
                    )
                }
            }.exceptionOrNull()
            if (acquisitionFailure != null) {
                return@selectionResult LocalUploadSelectionResult.Rejected(
                    "The selected file provider cannot keep access for a background upload.",
                )
            }
            if (cancelledAfterAcquire) return@selectionResult LocalUploadSelectionResult.Cancelled
            LocalUploadSelectionResult.Selected(file)
        }.getOrElse {
            LocalUploadSelectionResult.Rejected(
                "The selected file could not be opened.",
            )
        }
        resumeLocalUploadSelectionResult(
            continuation = selection.continuation,
            result = result,
            releaseSelected = { file -> release(file) },
        )
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

    fun release(file: LocalUploadFile): Boolean = synchronized(CAPABILITY_LOCK) {
        val source = try {
            selections[file.selectionId] ?: load(file.selectionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@synchronized retainCapabilityCleanup(file.selectionId)
        }
        if (source == null) {
            val removed = durableUploadCleanupStep {
                removeMetadata(file.selectionId)
            }
            return@synchronized if (removed) true else retainCapabilityCleanup(file.selectionId)
        }
        val cleanupPending = source.copy(phase = CapabilityPhase.CleanupPending)
        PENDING_CLEANUP_SELECTIONS += file.selectionId
        selections[file.selectionId] = cleanupPending
        if (!durableUploadCleanupStep { persist(cleanupPending) }) {
            requestQueuedDurableUploadSchedulingRecovery()
            return@synchronized false
        }
        val capabilities = try {
            loadCapabilitySnapshot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@synchronized retainCapabilityCleanup(file.selectionId)
        }
        val ownedElsewhere = capabilities.anyOtherCapabilityOwnsUri(
            source.uri,
            file.selectionId,
        )
        releaseDurableUploadCapability(
            releasePermission = {
                if (shouldReleaseDurableUploadPermission(source.grantPreExisting, ownedElsewhere)) {
                    releasePermission(source.uri)
                }
            },
            isPermissionAbsent = {
                source.grantPreExisting || ownedElsewhere || exactReadPermissionIsAbsent(source.uri)
            },
            removeMetadata = { removeMetadata(file.selectionId) },
        ).also { released ->
            if (released) {
                selections.remove(file.selectionId)
            } else {
                requestQueuedDurableUploadSchedulingRecovery()
            }
        }
    }

    fun reconcileCapabilities(ownedSelectionIds: Set<String>): Boolean = synchronized(CAPABILITY_LOCK) {
        val capabilities = try {
            loadCapabilitySnapshot().toMutableMap()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@synchronized false
        }
        var allRecovered = true
        capabilities.values
            .sortedBy { capability -> capability.file.selectionId }
            .filter { capability ->
                shouldRecoverDurableUploadCapability(
                    phase = capability.phase,
                    processGeneration = capability.processGeneration,
                    currentProcessGeneration = PROCESS_GENERATION,
                    ownedByDurableJob = capability.file.selectionId in ownedSelectionIds,
                    cleanupExplicitlyPending = capability.file.selectionId in PENDING_CLEANUP_SELECTIONS,
                )
            }
            .forEach { capability ->
                val selectionId = capability.file.selectionId
                val ownedElsewhere = capabilities.anyOtherCapabilityOwnsUri(
                    capability.uri,
                    selectionId,
                )
                val released = releaseDurableUploadCapability(
                    releasePermission = {
                        if (
                            shouldReleaseDurableUploadPermission(
                                capability.grantPreExisting,
                                ownedElsewhere,
                            )
                        ) {
                            releasePermission(capability.uri)
                        }
                    },
                    isPermissionAbsent = {
                        capability.grantPreExisting ||
                            ownedElsewhere ||
                            exactReadPermissionIsAbsent(capability.uri)
                    },
                    removeMetadata = { removeMetadata(selectionId) },
                )
                if (released) {
                    capabilities.remove(selectionId)
                    selections.remove(selectionId)
                } else {
                    allRecovered = false
                }
        }
        allRecovered
    }

    private fun persist(source: SelectedSource): Boolean {
        val payload = JSONObject()
            .put("uri", source.uri.toString())
            .put("selectionId", source.file.selectionId)
            .put("displayName", source.file.displayName)
            .put("mimeType", source.file.mimeType)
            .put("sizeBytes", source.file.sizeBytes)
            .put("phase", source.phase.persistedValue)
            .put("grantPreExisting", source.grantPreExisting)
        source.processGeneration?.let { generation -> payload.put("processGeneration", generation) }
        val encrypted = cipher.encrypt(payload.toString())
        return preferences.edit()
            .putString(preferenceKey(source.file.selectionId), encrypted)
            .commit()
    }

    private fun removeMetadata(selectionId: String): Boolean = preferences.edit()
        .remove(preferenceKey(selectionId))
        .commit()
        .also { removed -> if (removed) PENDING_CLEANUP_SELECTIONS.remove(selectionId) }

    private fun retainCapabilityCleanup(selectionId: String): Boolean {
        PENDING_CLEANUP_SELECTIONS += selectionId
        return retainDurableUploadCapabilityCleanup(::requestQueuedDurableUploadSchedulingRecovery)
    }

    private fun releasePermission(uri: Uri) {
        resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun exactReadPermissionIsAbsent(uri: Uri): Boolean = resolver.persistedUriPermissions.none {
        it.uri == uri && it.isReadPermission
    }

    private fun loadCapabilitySnapshot(): Map<String, SelectedSource> {
        val storedSelectionIds = preferences.all.keys
            .asSequence()
            .filter { key -> key.startsWith(PREFERENCE_PREFIX) }
            .map { key -> key.removePrefix(PREFERENCE_PREFIX) }
            .toList()
        require(storedSelectionIds.size <= MAX_TRACKED_CAPABILITIES) {
            "Too many picker capabilities are tracked."
        }
        return mergeDurableUploadCapabilities(
            cachedCapabilities = selections.toMap(),
            storedSelectionIds = storedSelectionIds,
            loadStoredCapability = ::load,
        )
    }

    private fun Map<String, SelectedSource>.anyOtherCapabilityOwnsUri(
        uri: Uri,
        selectionId: String,
    ): Boolean = durableUploadCapabilityPermissionOwnedByAnother(
        capabilities = this,
        targetSelectionId = selectionId,
        targetPermission = uri,
        permissionOf = SelectedSource::uri,
        samePermission = { first, second -> first == second },
    )

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
        if (!isDurableUploadCapabilityReady(source.phase)) {
            throw AndroidLocalUploadCapabilityUnavailableException(
                "The local file selection is pending capability cleanup.",
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
        val phase = if (payload.has("phase")) {
            CapabilityPhase.fromPersistedValue(payload.requireStrictString("phase"))
        } else {
            CapabilityPhase.Ready
        }
        val processGeneration = payload.optionalStrictString("processGeneration")
            ?.also(::requireSafeProcessGeneration)
        val grantPreExisting = payload.optionalStrictBoolean("grantPreExisting") ?: true
        return SelectedSource(
            uri = Uri.parse(payload.getString("uri")),
            file = file,
            phase = phase,
            processGeneration = processGeneration,
            grantPreExisting = grantPreExisting,
        )
    }

    private fun preferenceKey(selectionId: String): String = "$PREFERENCE_PREFIX$selectionId"

    private class PendingSelection(
        val continuation: CancellableContinuation<LocalUploadSelectionResult>,
        val acceptedMimeTypes: List<String>,
        val maximumBytes: Long,
    ) {
        @Volatile
        var readyFile: LocalUploadFile? = null
    }

    private data class SelectedSource(
        val uri: Uri,
        val file: LocalUploadFile,
        val phase: CapabilityPhase = CapabilityPhase.Ready,
        val processGeneration: String? = PROCESS_GENERATION,
        val grantPreExisting: Boolean = false,
    )

    private companion object {
        const val PREFERENCES = "nextcloud_native_upload_capabilities"
        const val PREFERENCE_PREFIX = "upload_"
        const val MAX_TRACKED_CAPABILITIES = 64
        val PROCESS_GENERATION = UUID.randomUUID().toString()
        val PROCESS_SELECTIONS = ConcurrentHashMap<String, SelectedSource>()
        val PENDING_CLEANUP_SELECTIONS = ConcurrentHashMap.newKeySet<String>()
        val CAPABILITY_LOCK = Any()
    }
}

private fun requireSafeProcessGeneration(value: String) {
    require(value.length in 16..96 && value.all { it.isLetterOrDigit() || it == '-' }) {
        "The picker capability process generation is invalid."
    }
}

internal fun JSONObject.optionalStrictString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return requireStrictString(key)
}

internal fun JSONObject.requireStrictString(key: String): String = get(key).let { value ->
    require(value is String) { "The $key value changed type." }
    value
}

internal fun JSONObject.optionalStrictBoolean(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    return get(key).let { value ->
        require(value is Boolean) { "The $key value changed type." }
        value
    }
}

internal class AndroidLocalUploadCapabilityUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal fun resumeLocalUploadSelectionResult(
    continuation: CancellableContinuation<LocalUploadSelectionResult>,
    result: LocalUploadSelectionResult,
    releaseSelected: (LocalUploadFile) -> Unit,
) {
    continuation.resume(result) { _, undeliveredResult, _ ->
        if (undeliveredResult is LocalUploadSelectionResult.Selected) {
            runCatching { releaseSelected(undeliveredResult.file) }
        }
    }
}

/**
 * Acquires a durable picker capability without exposing an interval where a successful selection
 * can be reported before its metadata reaches app-private storage.
 */
internal fun acquireDurableUploadCapability(
    takePermission: () -> Unit,
    persistMetadata: () -> Boolean,
    releasePermission: () -> Unit,
    persistAcquiring: () -> Boolean = { true },
    markCleanupPending: () -> Boolean = { true },
    isPermissionAbsent: () -> Boolean = { false },
    removeCapability: () -> Boolean = { true },
    onRollbackRetained: () -> Unit = {},
) {
    val acquiringPersisted = try {
        persistAcquiring()
    } catch (cancelled: CancellationException) {
        durableUploadCleanupStep(removeCapability)
        runCatching(onRollbackRetained)
        throw cancelled
    } catch (failure: Exception) {
        durableUploadCleanupStep(removeCapability)
        runCatching(onRollbackRetained)
        throw failure
    }
    if (!acquiringPersisted) {
        durableUploadCleanupStep(removeCapability)
        runCatching(onRollbackRetained)
        error("The picker capability rollback could not be saved.")
    }
    try {
        takePermission()
    } catch (cancelled: CancellationException) {
        runCatching(onRollbackRetained)
        throw cancelled
    } catch (failure: Exception) {
        runCatching(onRollbackRetained)
        throw failure
    }
    val persisted = runCatching { persistMetadata() }
    if (persisted.getOrNull() == true) return
    if (!durableUploadCleanupStep(markCleanupPending)) runCatching(onRollbackRetained)
    val released = try {
        releaseDurableUploadPermission(releasePermission, isPermissionAbsent)
    } catch (cancelled: CancellationException) {
        runCatching(onRollbackRetained)
        throw cancelled
    }
    if (released) {
        if (!durableUploadCleanupStep(removeCapability)) runCatching(onRollbackRetained)
    } else {
        runCatching(onRollbackRetained)
    }
    persisted.exceptionOrNull()?.let { throw it }
    error("The durable upload capability could not be saved.")
}

internal enum class CapabilityPhase(val persistedValue: String) {
    Acquiring("acquiring"),
    Ready("ready"),
    CleanupPending("cleanup-pending");

    companion object {
        fun fromPersistedValue(value: String): CapabilityPhase = entries.singleOrNull {
            phase -> phase.persistedValue == value
        } ?: error("The picker capability phase is invalid.")
    }
}

internal fun shouldRecoverDurableUploadCapability(
    phase: CapabilityPhase,
    processGeneration: String?,
    currentProcessGeneration: String,
    ownedByDurableJob: Boolean,
    cleanupExplicitlyPending: Boolean,
): Boolean = !ownedByDurableJob && (
    cleanupExplicitlyPending ||
        phase != CapabilityPhase.Ready ||
        processGeneration != currentProcessGeneration
)

internal fun isDurableUploadCapabilityReady(phase: CapabilityPhase): Boolean =
    phase == CapabilityPhase.Ready

internal fun finalizeDurableUploadCapabilityDelivery(
    publishReady: () -> Unit,
    continuationIsActive: () -> Boolean,
    cleanupUndelivered: () -> Unit,
): Boolean {
    publishReady()
    if (continuationIsActive()) return true
    runCatching(cleanupUndelivered)
    return false
}

/**
 * Revokes the URI grant before synchronously deleting capability metadata. Android may throw when
 * the grant is already absent, so an exception is accepted only after absence is verified.
 */
internal fun releaseDurableUploadCapability(
    releasePermission: () -> Unit,
    removeMetadata: () -> Boolean,
    isPermissionAbsent: () -> Boolean = { false },
): Boolean {
    if (!releaseDurableUploadPermission(releasePermission, isPermissionAbsent)) return false
    return durableUploadCleanupStep(removeMetadata)
}

internal fun releaseDurableUploadPermission(
    releasePermission: () -> Unit,
    isPermissionAbsent: () -> Boolean,
): Boolean = try {
    releasePermission()
    true
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    try {
        isPermissionAbsent()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

internal fun <Capability> releaseStoredDurableUploadCapability(
    cachedCapability: Capability?,
    loadCapability: () -> Capability?,
    releasePermission: (Capability) -> Unit,
    removeMetadata: () -> Boolean,
    otherCapabilityOwnsPermission: (Capability) -> Boolean = { false },
    isPermissionAbsent: (Capability) -> Boolean = { false },
): Boolean {
    val capability = cachedCapability ?: try {
        loadCapability()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return false
    }
    return releaseDurableUploadCapability(
        releasePermission = {
            capability?.let { stored ->
                if (!otherCapabilityOwnsPermission(stored)) releasePermission(stored)
            }
        },
        removeMetadata = removeMetadata,
        isPermissionAbsent = { capability == null || isPermissionAbsent(capability) },
    )
}

internal fun <Capability> mergeDurableUploadCapabilities(
    cachedCapabilities: Map<String, Capability>,
    storedSelectionIds: Iterable<String>,
    loadStoredCapability: (String) -> Capability?,
): Map<String, Capability> = buildMap {
    putAll(cachedCapabilities)
    storedSelectionIds.forEach { selectionId ->
        if (selectionId !in this) {
            put(
                selectionId,
                checkNotNull(loadStoredCapability(selectionId)) {
                    "The picker capability disappeared during recovery."
                },
            )
        }
    }
}

internal fun <Capability, Permission> durableUploadCapabilityPermissionOwnedByAnother(
    capabilities: Map<String, Capability>,
    targetSelectionId: String,
    targetPermission: Permission,
    permissionOf: (Capability) -> Permission,
    samePermission: (Permission, Permission) -> Boolean,
): Boolean = capabilities.any { (selectionId, capability) ->
    selectionId != targetSelectionId && samePermission(targetPermission, permissionOf(capability))
}

internal fun shouldReleaseDurableUploadPermission(
    grantPreExisting: Boolean,
    ownedByAnotherCapability: Boolean,
): Boolean = !grantPreExisting && !ownedByAnotherCapability

internal fun retainDurableUploadCapabilityCleanup(onCleanupRetained: () -> Unit): Boolean {
    runCatching(onCleanupRetained)
    return false
}

private fun durableUploadCleanupStep(action: () -> Boolean): Boolean = try {
    action()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
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
