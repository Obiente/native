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
                    val snapshot = loadCapabilitySnapshot()
                    val existing = snapshot.capabilities
                    check(
                        durableUploadCapabilityHasCapacity(
                            trackedCapabilityCount = snapshot.trackedCapabilityCount,
                            maximumTrackedCapabilities = MAX_TRACKED_CAPABILITIES,
                        ),
                    ) {
                        "Too many picker capabilities are tracked."
                    }
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
        } catch (malformed: AndroidLocalUploadCapabilityMalformedException) {
            return@synchronized releaseMalformedCapability(file.selectionId, malformed)
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
        val snapshot = try {
            loadCapabilitySnapshot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@synchronized retainCapabilityCleanup(file.selectionId)
        }
        val malformedPeerBlocksCleanup = malformedPeerBlocksDirectCleanup(
            malformedCapabilities = snapshot.malformedCapabilities,
            targetSelectionId = file.selectionId,
            targetPermissionIdentity = source.uri.toString(),
        )
        if (malformedPeerBlocksCleanup) {
            return@synchronized retainCapabilityCleanup(file.selectionId)
        }
        val cleanupPlan = durableUploadPermissionCleanupPlan(
            grantPreExisting = source.grantPreExisting,
            peerProtection = permissionPeerProtection(
                capabilities = snapshot.capabilities,
                malformedCapabilities = emptyMap(),
                targetSelectionId = file.selectionId,
                targetPermissionIdentity = source.uri.toString(),
            ),
        )
        if (cleanupPlan == DurableUploadPermissionCleanupPlan.Retain) {
            return@synchronized retainCapabilityCleanup(file.selectionId)
        }
        releaseDurableUploadCapability(
            releasePermission = {
                if (cleanupPlan == DurableUploadPermissionCleanupPlan.ReleaseThenRemove) {
                    releasePermission(source.uri)
                }
            },
            isPermissionAbsent = {
                cleanupPlan == DurableUploadPermissionCleanupPlan.RemoveWithoutRelease ||
                    exactReadPermissionIsAbsent(source.uri)
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

    fun markOwnershipCheckPending(file: LocalUploadFile): Boolean = synchronized(CAPABILITY_LOCK) {
        val source = try {
            selections[file.selectionId] ?: load(file.selectionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@synchronized retainCapabilityCleanup(file.selectionId)
        }
        if (source == null || source.file != file) {
            return@synchronized retainCapabilityCleanup(file.selectionId)
        }
        if (source.phase != CapabilityPhase.Ready) {
            return@synchronized retainCapabilityCleanup(file.selectionId)
        }
        val ownershipCheckPending = source.copy(phase = CapabilityPhase.OwnershipCheckPending)
        selections[file.selectionId] = ownershipCheckPending
        val persisted = durableUploadCleanupStep { persist(ownershipCheckPending) }
        requestQueuedDurableUploadSchedulingRecovery()
        persisted
    }

    fun reconcileCapabilities(ownedSelectionIds: Set<String>): Boolean = synchronized(CAPABILITY_LOCK) {
        val snapshot = try {
            loadCapabilityRecoverySnapshot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@synchronized false
        }
        if (snapshot.malformedCapabilities.isNotEmpty()) {
            PENDING_CLEANUP_SELECTIONS += snapshot.malformedCapabilities.keys
            requestQueuedDurableUploadSchedulingRecovery()
        }
        if (!snapshot.scanComplete) {
            requestQueuedDurableUploadSchedulingRecovery()
            return@synchronized false
        }
        val capabilities = snapshot.capabilities.toMutableMap()
        val malformedCapabilities = snapshot.malformedCapabilities.toMutableMap()
        var allRecovered = true
        var remainingRecoveryActions = MAX_RECOVERY_ROWS_PER_PASS
        val malformedRecovery = malformedDurableUploadCapabilitiesForRecovery(
            malformedCapabilities,
            ownedSelectionIds,
        )
        malformedRecovery.forEach { malformed ->
            if (!malformedRecoveryIsActionable(malformed, capabilities, malformedCapabilities)) {
                allRecovered = false
                return@forEach
            }
            if (remainingRecoveryActions == 0) {
                allRecovered = false
                return@forEach
            }
            remainingRecoveryActions -= 1
            val recovered = recoverMalformedCapability(
                malformed,
                capabilities,
                malformedCapabilities,
            )
            if (recovered) {
                malformedCapabilities.remove(malformed.selectionId)
                selections.remove(malformed.selectionId)
                PENDING_CLEANUP_SELECTIONS.remove(malformed.selectionId)
            } else {
                allRecovered = false
            }
        }
        capabilities.values
            .sortedWith(
                compareByDescending<SelectedSource> { capability -> capability.grantPreExisting }
                    .thenBy { capability -> capability.file.selectionId },
            )
            .forEach { capability ->
                val selectionId = capability.file.selectionId
                val ownedByDurableJob = selectionId in ownedSelectionIds
                if (
                    shouldRestoreDurableUploadCapabilityAfterOwnershipCheck(
                        phase = capability.phase,
                        ownedByDurableJob = ownedByDurableJob,
                    )
                ) {
                    if (remainingRecoveryActions == 0) {
                        allRecovered = false
                        return@forEach
                    }
                    remainingRecoveryActions -= 1
                    val ready = capability.copy(
                        phase = CapabilityPhase.Ready,
                        processGeneration = PROCESS_GENERATION,
                    )
                    if (durableUploadCleanupStep { persist(ready) }) {
                        capabilities[selectionId] = ready
                        selections[selectionId] = ready
                    } else {
                        allRecovered = false
                    }
                    return@forEach
                }
                if (!shouldRecoverDurableUploadCapability(
                    phase = capability.phase,
                    processGeneration = capability.processGeneration,
                    currentProcessGeneration = PROCESS_GENERATION,
                    ownedByDurableJob = ownedByDurableJob,
                    cleanupExplicitlyPending = selectionId in PENDING_CLEANUP_SELECTIONS,
                )) return@forEach
                val cleanupPlan = durableUploadPermissionCleanupPlan(
                    grantPreExisting = capability.grantPreExisting,
                    peerProtection = permissionPeerProtection(
                        capabilities = capabilities,
                        malformedCapabilities = malformedCapabilities,
                        targetSelectionId = selectionId,
                        targetPermissionIdentity = capability.uri.toString(),
                    ),
                )
                if (cleanupPlan == DurableUploadPermissionCleanupPlan.Retain) {
                    allRecovered = false
                    return@forEach
                }
                if (remainingRecoveryActions == 0) {
                    allRecovered = false
                    return@forEach
                }
                remainingRecoveryActions -= 1
                val released = releaseDurableUploadCapability(
                    releasePermission = {
                        if (cleanupPlan == DurableUploadPermissionCleanupPlan.ReleaseThenRemove) {
                            releasePermission(capability.uri)
                        }
                    },
                    isPermissionAbsent = {
                        cleanupPlan == DurableUploadPermissionCleanupPlan.RemoveWithoutRelease ||
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

    private fun loadCapabilitySnapshot(): DurableUploadCapabilitySnapshot<SelectedSource> {
        val storedSelectionIds = storedCapabilitySelectionIds()
        val snapshot = loadDurableUploadCapabilitySnapshot(
            cachedCapabilities = selections.toMap(),
            storedSelectionIds = storedSelectionIds,
            maximumRecoverableCapabilities = MAX_RECOVERABLE_CAPABILITIES,
            loadStoredCapability = ::load,
        )
        if (snapshot.malformedCapabilities.isNotEmpty()) {
            PENDING_CLEANUP_SELECTIONS += snapshot.malformedCapabilities.keys
            requestQueuedDurableUploadSchedulingRecovery()
        }
        return snapshot
    }

    private fun loadCapabilityRecoverySnapshot(): DurableUploadCapabilitySnapshot<SelectedSource> =
        RECOVERY_SCAN.loadPage(
            cachedCapabilities = selections.toMap(),
            storedSelectionIds = storedCapabilitySelectionIds(),
            maximumRows = MAX_RECOVERY_ROWS_PER_PASS,
            loadStoredCapability = ::load,
        )

    private fun storedCapabilitySelectionIds(): List<String> = preferences.all.keys
        .asSequence()
        .filter { key -> key.startsWith(PREFERENCE_PREFIX) }
        .map { key -> key.removePrefix(PREFERENCE_PREFIX) }
        .toList()

    private fun releaseMalformedCapability(
        selectionId: String,
        malformed: AndroidLocalUploadCapabilityMalformedException,
    ): Boolean {
        PENDING_CLEANUP_SELECTIONS += selectionId
        val snapshot = try {
            loadCapabilitySnapshot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return retainCapabilityCleanup(selectionId)
        }
        val isolated = snapshot.malformedCapabilities[selectionId]
            ?: MalformedDurableUploadCapability(
                selectionId,
                malformed.cleanupPermissionIdentity,
                malformed.grantPreExisting,
            )
        val permissionIdentity = isolated.cleanupPermissionIdentity
        if (
            permissionIdentity != null &&
            malformedPeerBlocksDirectCleanup(
                malformedCapabilities = snapshot.malformedCapabilities,
                targetSelectionId = selectionId,
                targetPermissionIdentity = permissionIdentity,
            )
        ) {
            return retainCapabilityCleanup(selectionId)
        }
        val recovered = recoverMalformedCapability(
            isolated,
            snapshot.capabilities,
            emptyMap(),
        )
        if (recovered) {
            selections.remove(selectionId)
            PENDING_CLEANUP_SELECTIONS.remove(selectionId)
        } else {
            requestQueuedDurableUploadSchedulingRecovery()
        }
        return recovered
    }

    private fun recoverMalformedCapability(
        malformed: MalformedDurableUploadCapability,
        capabilities: Map<String, SelectedSource>,
        malformedCapabilities: Map<String, MalformedDurableUploadCapability>,
    ): Boolean {
        val permission = malformed.cleanupPermissionIdentity?.let(Uri::parse)
        val peerProtection = permission?.let { target ->
            permissionPeerProtection(
                capabilities = capabilities,
                malformedCapabilities = malformedCapabilities,
                targetSelectionId = malformed.selectionId,
                targetPermissionIdentity = target.toString(),
            )
        } ?: DurableUploadPermissionPeerProtection.Ambiguous
        return recoverMalformedDurableUploadCapability(
            capability = malformed,
            permission = permission,
            peerProtection = peerProtection,
            releasePermission = ::releasePermission,
            isPermissionAbsent = ::exactReadPermissionIsAbsent,
            removeMetadata = ::removeMetadata,
        )
    }

    private fun malformedRecoveryIsActionable(
        malformed: MalformedDurableUploadCapability,
        capabilities: Map<String, SelectedSource>,
        malformedCapabilities: Map<String, MalformedDurableUploadCapability>,
    ): Boolean {
        val permission = malformed.cleanupPermissionIdentity?.let(Uri::parse) ?: return false
        val peerProtection = permissionPeerProtection(
            capabilities = capabilities,
            malformedCapabilities = malformedCapabilities,
            targetSelectionId = malformed.selectionId,
            targetPermissionIdentity = permission.toString(),
        )
        val permissionAbsent = if (
            malformed.grantPreExisting == null &&
            peerProtection == DurableUploadPermissionPeerProtection.None
        ) {
            try {
                exactReadPermissionIsAbsent(permission)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
        return durableUploadPermissionCleanupPlan(
            malformed.grantPreExisting,
            peerProtection,
            permissionAbsent,
        ) != DurableUploadPermissionCleanupPlan.Retain
    }

    private fun permissionPeerProtection(
        capabilities: Map<String, SelectedSource>,
        malformedCapabilities: Map<String, MalformedDurableUploadCapability>,
        targetSelectionId: String,
        targetPermissionIdentity: String,
    ): DurableUploadPermissionPeerProtection = durableUploadPermissionPeerProtection(
        peers = (
            capabilities.asSequence().map { (selectionId, capability) ->
                DurableUploadPermissionPeer(
                    selectionId,
                    capability.uri.toString(),
                    capability.grantPreExisting,
                )
            } + malformedCapabilities.asSequence().map { (_, capability) ->
                DurableUploadPermissionPeer(
                    capability.selectionId,
                    capability.cleanupPermissionIdentity,
                    capability.grantPreExisting,
                )
            }
        ).asIterable(),
        targetSelectionId = targetSelectionId,
        targetPermission = targetPermissionIdentity,
        samePermission = String::equals,
    )

    private fun malformedPeerBlocksDirectCleanup(
        malformedCapabilities: Map<String, MalformedDurableUploadCapability>,
        targetSelectionId: String,
        targetPermissionIdentity: String,
    ): Boolean = malformedDurableUploadPeerBlocksDirectCleanup(
        malformedPeers = malformedCapabilities.values.asSequence().map { capability ->
            DurableUploadPermissionPeer(
                capability.selectionId,
                capability.cleanupPermissionIdentity,
                capability.grantPreExisting,
            )
        }.asIterable(),
        targetSelectionId = targetSelectionId,
        targetPermission = targetPermissionIdentity,
        samePermission = String::equals,
    )

    private fun persistedSource(file: LocalUploadFile): SelectedSource {
        return requiredSource(file, useCachedSource = true)
    }

    private fun requiredSource(
        file: LocalUploadFile,
        useCachedSource: Boolean,
    ): SelectedSource {
        val source = readAndroidLocalUploadCapability {
            selections[file.selectionId].takeIf { useCachedSource } ?: load(file.selectionId)
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
        val encrypted = readAndroidLocalUploadCapabilityPreference {
            preferences.getString(preferenceKey(selectionId), null)
        } ?: return null
        val decrypted = decryptAndroidLocalUploadCapability { cipher.decrypt(encrypted) }
        val payload = try {
            JSONObject(decrypted)
        } catch (failure: Exception) {
            throw AndroidLocalUploadCapabilityMalformedException(
                "The local file selection metadata is invalid.",
                failure,
            )
        }
        val cleanupPermissionIdentity = try {
            payload.requireStrictString("uri")
        } catch (_: Exception) {
            null
        }
        val cleanupGrantPreExisting = try {
            persistedDurableUploadGrantPreExisting(payload)
        } catch (_: Exception) {
            null
        }
        return try {
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
            val grantPreExisting = persistedDurableUploadGrantPreExisting(payload)
            SelectedSource(
                uri = Uri.parse(payload.getString("uri")),
                file = file,
                phase = phase,
                processGeneration = processGeneration,
                grantPreExisting = grantPreExisting,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw AndroidLocalUploadCapabilityMalformedException(
                "The local file selection metadata is invalid.",
                failure,
                cleanupPermissionIdentity,
                cleanupGrantPreExisting,
            )
        }
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
        const val MAX_RECOVERABLE_CAPABILITIES = 1_024
        const val MAX_RECOVERY_ROWS_PER_PASS = 1_024
        val PROCESS_GENERATION = UUID.randomUUID().toString()
        val PROCESS_SELECTIONS = ConcurrentHashMap<String, SelectedSource>()
        val PENDING_CLEANUP_SELECTIONS = ConcurrentHashMap.newKeySet<String>()
        val CAPABILITY_LOCK = Any()
        val RECOVERY_SCAN = DurableUploadCapabilityRecoveryScan<SelectedSource>()
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

internal fun persistedDurableUploadGrantPreExisting(payload: JSONObject): Boolean =
    payload.optionalStrictBoolean("grantPreExisting") ?: false

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
