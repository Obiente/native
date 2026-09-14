package dev.obiente.nextcloudnative

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.obiente.nextcloudnative.app.FileSyncLocalRoot
import dev.obiente.nextcloudnative.app.FileSyncPair
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal enum class AndroidFileSyncCapabilityPhase {
    Acquiring,
    Ready,
    Owned,
    CleanupPending,
}

internal data class AndroidFileSyncCapabilityRecord(
    val id: String,
    val uri: String,
    val displayName: String,
    val phase: AndroidFileSyncCapabilityPhase,
    val processGeneration: String,
    val preExistingReadGrant: Boolean,
    val preExistingWriteGrant: Boolean,
    val accountId: AndroidFileSyncCapabilityAccountId? = null,
    val pairIds: Set<String> = emptySet(),
) {
    init {
        UUID.fromString(id)
        require(uri.startsWith("content://") && uri.length <= MAX_CAPABILITY_URI_CHARACTERS)
        require(displayName.isNotBlank() && displayName.length <= MAX_CAPABILITY_DISPLAY_NAME_CHARACTERS)
        UUID.fromString(processGeneration)
        require(phase != AndroidFileSyncCapabilityPhase.Owned || pairIds.isNotEmpty())
        require(phase !in setOf(
            AndroidFileSyncCapabilityPhase.Acquiring,
            AndroidFileSyncCapabilityPhase.Ready,
        ) || pairIds.isEmpty())
        pairIds.forEach(UUID::fromString)
    }
}

internal class AndroidFileSyncCapabilityRecoveryException(cause: Exception) : IllegalStateException(
    "Saved folder access metadata is unavailable. Folder permissions may still need recovery.",
    cause,
)

internal interface AndroidFileSyncCapabilityEncryptedStorage {
    fun read(): String?
    fun write(value: String): Boolean
}

internal interface AndroidFileSyncCapabilityCipher {
    fun encrypt(value: String): String
    fun decrypt(value: String): String
}

internal interface AndroidFileSyncGrantAccess {
    fun exactGrant(uri: String): AndroidFileSyncGrantState
    fun takeExactReadWriteGrant(uri: String)
    fun releaseExactGrant(uri: String, read: Boolean, write: Boolean)
}

internal data class AndroidFileSyncGrantState(val read: Boolean, val write: Boolean)

@JvmInline
internal value class AndroidFileSyncCapabilityAccountId(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= MAX_CAPABILITY_ACCOUNT_ID_CHARACTERS) {
            "The folder capability account is invalid."
        }
    }
}

internal fun hasDuplicateAndroidFileSyncRoot(
    pairs: List<FileSyncPair>,
    accountId: String,
    localRootId: String,
    remoteRootPath: String,
): Boolean = pairs.any { pair ->
    pair.localRootId == localRootId && (
        localRootId.startsWith("content://") ||
            pair.accountId == accountId && pair.remoteRootPath == remoteRootPath
        )
}

internal class AndroidFileSyncCapabilityStore(
    private val storage: AndroidFileSyncCapabilityEncryptedStorage,
    private val cipher: AndroidFileSyncCapabilityCipher,
) {
    constructor(context: Context) : this(
        SharedPreferencesFileSyncCapabilityStorage(context),
        SessionFileSyncCapabilityCipher(),
    )

    fun list(): List<AndroidFileSyncCapabilityRecord> = synchronized(LOCK) { readAll() }

    fun add(record: AndroidFileSyncCapabilityRecord) = synchronized(LOCK) {
        val current = readAll()
        require(current.none { it.id == record.id }) { "The folder capability ID is already in use." }
        require(current.none { it.uri == record.uri }) { "That local folder is already selected." }
        require(current.size < MAX_CAPABILITY_RECORDS) { "Too many local folders are awaiting setup." }
        writeAll(current + record)
    }

    fun replace(
        id: String,
        expected: AndroidFileSyncCapabilityPhase,
        update: (AndroidFileSyncCapabilityRecord) -> AndroidFileSyncCapabilityRecord,
    ): AndroidFileSyncCapabilityRecord = synchronized(LOCK) {
        val current = readAll().toMutableList()
        val index = current.indexOfFirst { it.id == id && it.phase == expected }
        check(index >= 0) { "The folder capability changed before it could be updated." }
        val updated = update(current[index])
        check(updated.id == id && updated.uri == current[index].uri) {
            "Folder capability identity cannot change."
        }
        current[index] = updated
        writeAll(current)
        updated
    }

    fun remove(id: String, expected: AndroidFileSyncCapabilityPhase) = synchronized(LOCK) {
        val current = readAll()
        check(current.any { it.id == id && it.phase == expected }) {
            "The folder capability changed before it could be removed."
        }
        writeAll(current.filterNot { it.id == id })
    }

    private fun readAll(): List<AndroidFileSyncCapabilityRecord> {
        val encrypted = try {
            storage.read()
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            throw AndroidFileSyncCapabilityRecoveryException(failure)
        } ?: return emptyList()
        return try {
            val array = JSONArray(cipher.decrypt(encrypted))
            check(array.length() <= MAX_CAPABILITY_RECORDS) { "Too many folder capabilities were saved." }
            buildList {
                repeat(array.length()) { index -> add(array.getJSONObject(index).toCapabilityRecord()) }
            }.also { records ->
                check(records.distinctBy(AndroidFileSyncCapabilityRecord::id).size == records.size) {
                    "Saved folder capability IDs are duplicated."
                }
                check(records.distinctBy(AndroidFileSyncCapabilityRecord::uri).size == records.size) {
                    "Saved folder capabilities are ambiguous."
                }
            }
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            if (failure is AndroidFileSyncCapabilityRecoveryException) throw failure
            throw AndroidFileSyncCapabilityRecoveryException(failure)
        }
    }

    private fun writeAll(records: List<AndroidFileSyncCapabilityRecord>) {
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        val encrypted = try {
            cipher.encrypt(array.toString())
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            throw IllegalStateException("Folder capability recovery data could not be encrypted.", failure)
        }
        val saved = try {
            storage.write(encrypted)
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            throw IllegalStateException("Folder capability recovery data could not be saved.", failure)
        }
        check(saved) { "Folder capability recovery data could not be saved." }
    }

    private companion object {
        val LOCK = Any()
    }
}

internal class AndroidFileSyncCapabilityLifecycle internal constructor(
    private val store: AndroidFileSyncCapabilityStore,
    private val grants: AndroidFileSyncGrantAccess,
    private val processGeneration: String,
    private val abandonedSelections: MutableSet<String> = linkedSetOf(),
    private val deliveredSelections: MutableSet<String> = linkedSetOf(),
    private val requestRecovery: () -> Unit = {},
    private val requestedAbandonments: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet(),
    private val loadConfiguredPairs: () -> List<FileSyncPair> = { emptyList() },
) {
    constructor(context: Context) : this(
        AndroidFileSyncCapabilityStore(context.applicationContext),
        ContentResolverFileSyncGrantAccess(context.applicationContext.contentResolver),
        PROCESS_GENERATION,
        ABANDONED_SELECTIONS,
        DELIVERED_SELECTIONS,
        { requestAndroidFileSyncCapabilityRecovery(context.applicationContext) },
        REQUESTED_ABANDONMENTS,
        { AndroidFileSyncStore(context.applicationContext).load().coordinator.pairs },
    )

    fun hasRecoveryWork(): Boolean = synchronized(LIFECYCLE_LOCK) {
        store.list().any { record ->
            record.phase == AndroidFileSyncCapabilityPhase.Acquiring ||
                record.phase == AndroidFileSyncCapabilityPhase.CleanupPending ||
                record.phase == AndroidFileSyncCapabilityPhase.Ready
        }
    }

    fun acquire(
        accountId: AndroidFileSyncCapabilityAccountId,
        exactUri: String,
        displayName: String,
    ): FileSyncLocalRoot = synchronized(LIFECYCLE_LOCK) {
        val preExisting = grants.exactGrant(exactUri)
        var existing = store.list().singleOrNull { it.uri == exactUri }
        if (existing == null) {
            val legacyPairs = loadConfiguredPairs().filter { it.localRootId == exactUri }
            if (legacyPairs.isNotEmpty()) {
                require(legacyPairs.any { it.accountId == accountId.value }) { "This folder belongs to another account." }
                val adopted = AndroidFileSyncCapabilityRecord(
                    id = UUID.randomUUID().toString(), uri = exactUri, displayName = displayName,
                    phase = AndroidFileSyncCapabilityPhase.Owned, processGeneration = processGeneration,
                    preExistingReadGrant = false, preExistingWriteGrant = false,
                    accountId = legacyPairs.singleAccountOwner(), pairIds = legacyPairs.mapTo(linkedSetOf(), FileSyncPair::id),
                )
                store.add(adopted)
                existing = adopted
            }
        }
        val retained = existing
        val ownsExisting = retained?.accountId == accountId ||
            (retained?.phase == AndroidFileSyncCapabilityPhase.Owned && retained.accountId == null &&
                loadConfiguredPairs().any { pair ->
                    pair.id in retained.pairIds && pair.localRootId == exactUri && pair.accountId == accountId.value
                })
        if (retained?.phase == AndroidFileSyncCapabilityPhase.Owned && ownsExisting &&
            (!preExisting.read || !preExisting.write)
        ) {
            store.replace(retained.id, retained.phase) {
                it.copy(
                    preExistingReadGrant = it.preExistingReadGrant && preExisting.read,
                    preExistingWriteGrant = it.preExistingWriteGrant && preExisting.write,
                )
            }
            grants.takeExactReadWriteGrant(exactUri)
            val restored = grants.exactGrant(exactUri)
            check(restored.read && restored.write) { "The folder provider did not restore read and write access." }
            return@synchronized FileSyncLocalRoot(exactUri, displayName, retained.id, accessRestored = true)
        }
        val record = AndroidFileSyncCapabilityRecord(
            id = UUID.randomUUID().toString(),
            uri = exactUri,
            displayName = displayName,
            phase = AndroidFileSyncCapabilityPhase.Acquiring,
            processGeneration = processGeneration,
            preExistingReadGrant = preExisting.read,
            preExistingWriteGrant = preExisting.write,
            accountId = accountId,
        )
        try {
            requestRecovery()
            store.add(record)
            if (!preExisting.read || !preExisting.write) grants.takeExactReadWriteGrant(exactUri)
            val acquired = grants.exactGrant(exactUri)
            check(acquired.read && acquired.write) {
                "The selected folder provider did not persist read and write access."
            }
            store.replace(record.id, AndroidFileSyncCapabilityPhase.Acquiring) {
                it.copy(phase = AndroidFileSyncCapabilityPhase.Ready)
            }
            deliveredSelections += record.id
            FileSyncLocalRoot(exactUri, displayName, savedStateId = record.id)
        } catch (failure: Exception) {
            recoverAcquisition(record.id)
            throw failure
        }
    }

    fun restoreSelection(accountId: AndroidFileSyncCapabilityAccountId, reference: FileSyncLocalRoot): FileSyncLocalRoot? =
        synchronized(LIFECYCLE_LOCK) {
            val record = store.list().singleOrNull {
                it.id == reference.savedStateId && it.accountId == accountId &&
                    it.phase == AndroidFileSyncCapabilityPhase.Ready
            } ?: return@synchronized null
            val grant = grants.exactGrant(record.uri)
            if (!grant.read || !grant.write) return@synchronized null
            store.replace(record.id, record.phase) { it.copy(processGeneration = processGeneration) }
            deliveredSelections += record.id
            FileSyncLocalRoot(record.uri, record.displayName, savedStateId = record.id)
        }

    fun bindReady(
        accountId: AndroidFileSyncCapabilityAccountId,
        localRootId: String,
        pairId: String,
    ) = synchronized(LIFECYCLE_LOCK) {
        val record = store.list().singleOrNull {
            it.uri == localRootId &&
                it.accountId == accountId &&
                it.phase == AndroidFileSyncCapabilityPhase.Ready
        } ?: error("The selected local folder is no longer available.")
        store.replace(record.id, AndroidFileSyncCapabilityPhase.Ready) {
            it.copy(phase = AndroidFileSyncCapabilityPhase.Owned, pairIds = setOf(pairId))
        }
    }

    fun requestSelectionAbandonment(reference: String) {
        requestedAbandonments += reference
    }

    fun abandonSelection(localRootId: String): Boolean {
        requestSelectionAbandonment(localRootId)
        return synchronized(LIFECYCLE_LOCK) {
            val records = store.list()
            if (records.none { it.uri == localRootId || it.id == localRootId }) {
                requestedAbandonments.remove(localRootId)
                return@synchronized true
            }
            val record = records.singleOrNull {
                (it.uri == localRootId || it.id == localRootId) &&
                    it.pairIds.isEmpty() &&
                    it.phase in setOf(
                        AndroidFileSyncCapabilityPhase.Acquiring,
                        AndroidFileSyncCapabilityPhase.Ready,
                        AndroidFileSyncCapabilityPhase.CleanupPending,
                    )
            } ?: return@synchronized false
            requestRecovery()
            deliveredSelections.remove(record.id)
            abandonedSelections += record.id
            prepareAndFinishCleanup(record)
        }
    }

    fun abandonUncommittedPair(pairId: String): Boolean = synchronized(LIFECYCLE_LOCK) {
        requestRecovery()
        val record = store.list().singleOrNull {
            pairId in it.pairIds && it.phase == AndroidFileSyncCapabilityPhase.Owned
        } ?: return@synchronized false
        val retainedIds = record.pairIds - pairId
        val updated = store.replace(record.id, record.phase) {
            it.copy(
                phase = if (retainedIds.isEmpty()) AndroidFileSyncCapabilityPhase.CleanupPending else record.phase,
                pairIds = retainedIds,
            )
        }
        if (retainedIds.isEmpty()) finishCleanup(updated) else true
    }

    fun preparePairCleanup(pairId: String): Boolean = synchronized(LIFECYCLE_LOCK) {
        requestRecovery()
        val record = store.list().singleOrNull { pairId in it.pairIds }
            ?: return@synchronized false
        when (record.phase) {
            AndroidFileSyncCapabilityPhase.Owned -> {
                store.replace(record.id, AndroidFileSyncCapabilityPhase.Owned) {
                    if (it.pairIds.size == 1) {
                        it.copy(phase = AndroidFileSyncCapabilityPhase.CleanupPending)
                    } else {
                        it.copy(pairIds = it.pairIds - pairId)
                    }
                }
            }
            AndroidFileSyncCapabilityPhase.CleanupPending -> Unit
            else -> error("The sync pair does not own its saved folder capability.")
        }
        true
    }

    fun finishPairCleanup(pairId: String): Boolean = synchronized(LIFECYCLE_LOCK) {
        val record = store.list().singleOrNull {
            pairId in it.pairIds && it.phase == AndroidFileSyncCapabilityPhase.CleanupPending
        } ?: return@synchronized false
        finishCleanup(record)
    }

    fun finishPairCleanupOrRetry(
        pairId: String,
        allowDeferredCleanup: Boolean = false,
        load: () -> AndroidFileSyncPersistedState,
    ) {
        try {
            synchronized(LIFECYCLE_LOCK) {
                val pending = store.list().singleOrNull {
                    pairId in it.pairIds && it.phase == AndroidFileSyncCapabilityPhase.CleanupPending
                } ?: return
                if (finishCleanup(pending)) return
                reconcile(load())
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!allowDeferredCleanup) throw failure
            if (load().coordinator.pairs.any { it.id == pairId }) throw failure
            // Removal is committed. The recovery owner was scheduled before the
            // cleanup intent and retains the remaining grant cleanup across restart.
        }
    }

    fun persistPairRemoval(
        pairId: String,
        load: () -> AndroidFileSyncPersistedState,
        persist: () -> Unit,
    ) = try {
        persist()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        if (!recoverAmbiguousPairRemoval(pairId, load)) throw failure
        Unit
    }

    private fun recoverAmbiguousPairRemoval(pairId: String, load: () -> AndroidFileSyncPersistedState): Boolean = synchronized(LIFECYCLE_LOCK) {
        val authoritative = try {
            load()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@synchronized false
        }
        if (authoritative.coordinator.pairs.any { it.id == pairId }) return@synchronized false
        try {
            reconcile(authoritative)
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Confirmed removal is complete even if its durable grant cleanup is pending.
            true
        }
    }

    fun reconcile(state: AndroidFileSyncPersistedState, reclaimUnrestoredReady: Boolean = false) = synchronized(LIFECYCLE_LOCK) {
        var records = store.list()
        abandonedSelections.retainAll(records.map { it.id }.toSet())
        deliveredSelections.retainAll(records.map { it.id }.toSet())
        requestedAbandonments.retainAll(records.flatMap { listOf(it.id, it.uri) }.toSet())
        records.filter { it.id in requestedAbandonments || it.uri in requestedAbandonments }
            .forEach { abandonedSelections += it.id }
        val safPairs = state.coordinator.pairs.filter { it.localRootId.startsWith("content://") }
        check(!hasConflictingOwnership(records, safPairs)) {
            "Folder capability ownership must be reconciled before changing sync pairs."
        }
        var recoveryFailure: Exception? = null
        fun attemptRecovery(action: () -> Unit) {
            try {
                action()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (recoveryFailure == null) recoveryFailure = failure
            }
        }
        safPairs.groupBy(FileSyncPair::localRootId).forEach { (uri, matches) ->
            attemptRecovery {
                if (records.none { it.uri == uri }) adoptLegacyCapability(uri, matches, state.localDisplayNames)
            }
        }
        records = store.list()
        records.forEach { original -> attemptRecovery {
            val record = store.list().firstOrNull { it.id == original.id } ?: return@attemptRecovery
            val matchingPairs = safPairs.filter { it.localRootId == record.uri }
            val matchingIds = matchingPairs.mapTo(linkedSetOf(), FileSyncPair::id)
            when (record.phase) {
                AndroidFileSyncCapabilityPhase.Acquiring -> if (record.processGeneration != processGeneration || reclaimUnrestoredReady) {
                    if (matchingIds.isNotEmpty()) {
                        store.replace(record.id, record.phase) {
                            it.copy(
                                phase = AndroidFileSyncCapabilityPhase.Owned,
                                accountId = matchingPairs.singleAccountOwner(),
                                pairIds = matchingIds,
                            )
                        }
                    } else {
                        check(prepareAndFinishCleanup(record)) { CLEANUP_RETRY_MESSAGE }
                    }
                }
                AndroidFileSyncCapabilityPhase.Ready -> if (record.processGeneration != processGeneration || record.id in abandonedSelections ||
                    (reclaimUnrestoredReady && record.id !in deliveredSelections)
                ) {
                    if (matchingIds.isNotEmpty()) {
                        store.replace(record.id, record.phase) {
                            it.copy(
                                phase = AndroidFileSyncCapabilityPhase.Owned,
                                accountId = matchingPairs.singleAccountOwner(),
                                pairIds = matchingIds,
                            )
                        }
                    } else if (record.accountId == null || reclaimUnrestoredReady || record.id in abandonedSelections) {
                        check(prepareAndFinishCleanup(record)) { CLEANUP_RETRY_MESSAGE }
                    }
                }
                AndroidFileSyncCapabilityPhase.Owned -> {
                    if (matchingIds.isNotEmpty() && matchingIds != record.pairIds) {
                        store.replace(record.id, record.phase) { it.copy(pairIds = matchingIds) }
                    } else if (matchingIds.isEmpty() && record.processGeneration != processGeneration) {
                        check(prepareAndFinishCleanup(record)) { CLEANUP_RETRY_MESSAGE }
                    }
                }
                AndroidFileSyncCapabilityPhase.CleanupPending -> {
                    if (matchingIds.isNotEmpty()) {
                        store.replace(record.id, record.phase) {
                            it.copy(phase = AndroidFileSyncCapabilityPhase.Owned, pairIds = matchingIds)
                        }
                    } else {
                        check(finishCleanup(record)) { CLEANUP_RETRY_MESSAGE }
                    }
                }
            }
        } }
        recoveryFailure?.let { throw it }
        Unit
    }


    fun reconcileRestoredSetup(
        accountId: AndroidFileSyncCapabilityAccountId,
        restoredLocalRootId: String?,
        state: AndroidFileSyncPersistedState,
    ): Boolean = reconcileSetup(accountId, restoredLocalRootId, state, includeCurrentGeneration = false)

    fun retireAccountSetup(
        accountId: AndroidFileSyncCapabilityAccountId,
        state: AndroidFileSyncPersistedState,
    ) = reconcileSetup(accountId, restoredLocalRootId = null, state, includeCurrentGeneration = true)

    private fun reconcileSetup(
        accountId: AndroidFileSyncCapabilityAccountId,
        restoredLocalRootId: String?,
        state: AndroidFileSyncPersistedState,
        includeCurrentGeneration: Boolean,
    ): Boolean = synchronized(LIFECYCLE_LOCK) {
        reconcile(state)
        val restoredContentRoot = restoredLocalRootId?.takeIf { it.startsWith("content://") }
        val records = store.list()
        val restored = restoredContentRoot?.let { uri ->
            records.singleOrNull { record ->
                record.uri == uri &&
                    record.accountId == accountId &&
                    record.phase == AndroidFileSyncCapabilityPhase.Ready
            }
        }
        if (restored != null) deliveredSelections += restored.id
        if (restored != null && restored.processGeneration != processGeneration) {
            store.replace(restored.id, AndroidFileSyncCapabilityPhase.Ready) {
                it.copy(processGeneration = processGeneration)
            }
        }
        records.asSequence()
            .filter { record ->
                record.accountId == accountId &&
                    record.phase == AndroidFileSyncCapabilityPhase.Ready &&
                    (includeCurrentGeneration || record.processGeneration != processGeneration) &&
                    record.id != restored?.id
            }
            .forEach { record ->
                check(prepareAndFinishCleanup(record)) { CLEANUP_RETRY_MESSAGE }
            }
        restoredContentRoot == null || restored != null
    }

    private fun hasConflictingOwnership(
        records: List<AndroidFileSyncCapabilityRecord>,
        pairs: List<FileSyncPair>,
    ): Boolean = records.any { record ->
        record.pairIds.any { pairId ->
            pairs.any { pair ->
                pair.id == pairId &&
                    (pair.localRootId != record.uri ||
                        record.accountId?.value?.let { owner -> owner != pair.accountId } == true)
            }
        }
    }

    private fun adoptLegacyCapability(
        uri: String,
        pairs: List<FileSyncPair>,
        displayNames: Map<String, String>,
    ) {
        val grant = grants.exactGrant(uri)
        if (!grant.read && !grant.write) return
        val pairIds = pairs.mapTo(linkedSetOf(), FileSyncPair::id)
        val displayName = pairs.asSequence().mapNotNull { displayNames[it.id] }.firstOrNull() ?: "Selected folder"
        store.add(AndroidFileSyncCapabilityRecord(
            id = UUID.randomUUID().toString(),
            uri = uri,
            displayName = displayName,
            phase = AndroidFileSyncCapabilityPhase.Owned,
            processGeneration = processGeneration,
            preExistingReadGrant = false,
            preExistingWriteGrant = false,
            accountId = pairs.singleAccountOwner(),
            pairIds = pairIds,
        ))
    }

    private fun recoverAcquisition(recordId: String) {
        val record = try {
            store.list().singleOrNull { it.id == recordId }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return
        try {
            prepareAndFinishCleanup(record)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The durable recovery owner retains this incomplete acquisition.
        }
    }

    private fun prepareAndFinishCleanup(record: AndroidFileSyncCapabilityRecord): Boolean {
        val pending = when (record.phase) {
            AndroidFileSyncCapabilityPhase.CleanupPending -> record
            AndroidFileSyncCapabilityPhase.Acquiring,
            AndroidFileSyncCapabilityPhase.Ready,
            AndroidFileSyncCapabilityPhase.Owned,
            -> store.replace(record.id, record.phase) {
                it.copy(phase = AndroidFileSyncCapabilityPhase.CleanupPending)
            }
        }
        return finishCleanup(pending)
    }

    private fun finishCleanup(record: AndroidFileSyncCapabilityRecord): Boolean {
        val ownedRead = !record.preExistingReadGrant
        val ownedWrite = !record.preExistingWriteGrant
        if (ownedRead || ownedWrite) {
            val granted = try {
                grants.exactGrant(record.uri)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return false
            }
            if ((ownedRead && granted.read) || (ownedWrite && granted.write)) {
                try {
                    grants.releaseExactGrant(record.uri, ownedRead, ownedWrite)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return false
                }
                val retained = try {
                    grants.exactGrant(record.uri)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return false
                }
                if ((ownedRead && retained.read) || (ownedWrite && retained.write)) return false
                if ((record.preExistingReadGrant && !retained.read) ||
                    (record.preExistingWriteGrant && !retained.write)
                ) return false
            }
        }
        return try {
            store.remove(record.id, AndroidFileSyncCapabilityPhase.CleanupPending)
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            try {
                store.list().none { it.id == record.id }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) { false }
        }.also { removed ->
            if (removed) {
                abandonedSelections.remove(record.id)
                requestedAbandonments.remove(record.id)
                requestedAbandonments.remove(record.uri)
            }
        }
    }

    private companion object {
        val LIFECYCLE_LOCK = Any()
        val ABANDONED_SELECTIONS = linkedSetOf<String>()
        val DELIVERED_SELECTIONS = linkedSetOf<String>()
        val REQUESTED_ABANDONMENTS: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        val PROCESS_GENERATION: String = UUID.randomUUID().toString()
    }
}

private class SharedPreferencesFileSyncCapabilityStorage(context: Context) :
    AndroidFileSyncCapabilityEncryptedStorage {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun read(): String? = preferences.getString(KEY_RECORDS, null)

    override fun write(value: String): Boolean = preferences.edit().putString(KEY_RECORDS, value).commit()

    private companion object {
        const val PREFERENCES = "nextcloud_native_file_sync_capabilities"
        const val KEY_RECORDS = "records"
    }
}

private class SessionFileSyncCapabilityCipher : AndroidFileSyncCapabilityCipher {
    private val delegate = SessionCipher()

    override fun encrypt(value: String): String = delegate.encrypt(value)
    override fun decrypt(value: String): String = delegate.decrypt(value)
}

private class ContentResolverFileSyncGrantAccess(private val resolver: ContentResolver) :
    AndroidFileSyncGrantAccess {
    override fun exactGrant(uri: String): AndroidFileSyncGrantState {
        val target = Uri.parse(uri)
        val exact = resolver.persistedUriPermissions.firstOrNull { it.uri == target }
        return AndroidFileSyncGrantState(exact?.isReadPermission == true, exact?.isWritePermission == true)
    }

    override fun takeExactReadWriteGrant(uri: String) {
        resolver.takePersistableUriPermission(Uri.parse(uri), READ_WRITE_GRANT_FLAGS)
    }

    override fun releaseExactGrant(uri: String, read: Boolean, write: Boolean) {
        resolver.releasePersistableUriPermission(Uri.parse(uri), grantFlags(read, write))
    }
}

private fun AndroidFileSyncCapabilityRecord.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("uri", uri)
    .put("displayName", displayName)
    .put("phase", phase.name)
    .put("processGeneration", processGeneration)
    .put("preExistingReadGrant", preExistingReadGrant)
    .put("preExistingWriteGrant", preExistingWriteGrant)
    .put("accountId", accountId?.value)
    .put("pairIds", JSONArray().also { array -> pairIds.sorted().forEach(array::put) })

private fun JSONObject.toCapabilityRecord(): AndroidFileSyncCapabilityRecord = AndroidFileSyncCapabilityRecord(
    id = getString("id"),
    uri = getString("uri"),
    displayName = getString("displayName"),
    phase = AndroidFileSyncCapabilityPhase.valueOf(getString("phase")),
    processGeneration = getString("processGeneration"),
    preExistingReadGrant = getBoolean("preExistingReadGrant"),
    preExistingWriteGrant = getBoolean("preExistingWriteGrant"),
    accountId = optionalCapabilityAccountId(),
    pairIds = when {
        has("pairIds") -> getJSONArray("pairIds").let { array ->
            buildSet { repeat(array.length()) { add(array.getString(it)) } }
        }
        !isNull("pairId") -> setOf(getString("pairId"))
        else -> emptySet()
    },
)

private const val READ_WRITE_GRANT_FLAGS =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
private fun grantFlags(read: Boolean, write: Boolean): Int =
    (if (read) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
        (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
private const val MAX_CAPABILITY_RECORDS = 64
private const val MAX_CAPABILITY_URI_CHARACTERS = 8 * 1024
private const val MAX_CAPABILITY_DISPLAY_NAME_CHARACTERS = 256
private const val CLEANUP_RETRY_MESSAGE = "Saved folder access cleanup is still pending."
private const val MAX_CAPABILITY_ACCOUNT_ID_CHARACTERS = 256

private fun List<FileSyncPair>.singleAccountOwner(): AndroidFileSyncCapabilityAccountId? =
    map(FileSyncPair::accountId).distinct().singleOrNull()?.let(::AndroidFileSyncCapabilityAccountId)

private fun JSONObject.optionalCapabilityAccountId(): AndroidFileSyncCapabilityAccountId? =
    when (val stored = opt("accountId")) {
        null, JSONObject.NULL -> null
        is String -> AndroidFileSyncCapabilityAccountId(stored)
        else -> error("Saved folder capability account is invalid.")
    }
