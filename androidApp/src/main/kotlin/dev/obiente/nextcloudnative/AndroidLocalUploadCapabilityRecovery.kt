package dev.obiente.nextcloudnative

import kotlinx.coroutines.CancellationException

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

internal data class MalformedDurableUploadCapability(
    val selectionId: String,
    val cleanupPermissionIdentity: String?,
    val grantPreExisting: Boolean?,
)

internal data class DurableUploadCapabilitySnapshot<Capability>(
    val capabilities: Map<String, Capability>,
    val malformedCapabilities: Map<String, MalformedDurableUploadCapability>,
    private val storedCapabilityCount: Int? = null,
    val scanComplete: Boolean = true,
    val recoveryQuarantined: Boolean = false,
) {
    val trackedCapabilityCount: Int
        get() = storedCapabilityCount ?: (capabilities.keys + malformedCapabilities.keys).size
}

internal fun malformedDurableUploadCapabilityCanBecomeActionable(
    capability: MalformedDurableUploadCapability,
): Boolean = capability.cleanupPermissionIdentity != null

internal fun <Capability> loadDurableUploadCapabilitySnapshot(
    cachedCapabilities: Map<String, Capability>,
    storedSelectionIds: Iterable<String>,
    maximumRecoverableCapabilities: Int = Int.MAX_VALUE,
    loadStoredCapability: (String) -> Capability?,
): DurableUploadCapabilitySnapshot<Capability> {
    require(maximumRecoverableCapabilities > 0)
    val storedIds = storedSelectionIds.toList()
    require((cachedCapabilities.keys + storedIds).size <= maximumRecoverableCapabilities) {
        "Too many picker capabilities are pending bounded recovery."
    }
    val capabilities = cachedCapabilities.toMutableMap()
    val malformed = linkedMapOf<String, MalformedDurableUploadCapability>()
    storedIds.forEach { selectionId ->
        if (selectionId in capabilities) return@forEach
        val stored = try {
            checkNotNull(loadStoredCapability(selectionId)) {
                "The picker capability disappeared during recovery."
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AndroidLocalUploadCapabilityMalformedException) {
            malformed[selectionId] = MalformedDurableUploadCapability(
                selectionId,
                failure.cleanupPermissionIdentity,
                failure.grantPreExisting,
            )
            return@forEach
        }
        capabilities[selectionId] = stored
    }
    return DurableUploadCapabilitySnapshot(capabilities.toMap(), malformed.toMap())
}

internal fun malformedDurableUploadCapabilitiesForRecovery(
    capabilities: Map<String, MalformedDurableUploadCapability>,
    ownedSelectionIds: Set<String>,
): List<MalformedDurableUploadCapability> = capabilities.values
    .filterNot { capability -> capability.selectionId in ownedSelectionIds }
    .sortedWith(
        compareBy<MalformedDurableUploadCapability> { capability ->
            when (capability.grantPreExisting) {
                true -> 0
                null -> 1
                false -> 2
            }
        }.thenBy(MalformedDurableUploadCapability::selectionId),
    )

internal enum class DurableUploadPermissionPeerProtection {
    None,
    RetainedAppOwnedGrant,
    Ambiguous,
}

internal data class DurableUploadPermissionPeer<Permission>(
    val selectionId: String,
    val permission: Permission?,
    val grantPreExisting: Boolean?,
)

internal fun <Permission> durableUploadPermissionPeerProtection(
    peers: Iterable<DurableUploadPermissionPeer<Permission>>,
    targetSelectionId: String,
    targetPermission: Permission,
    samePermission: (Permission, Permission) -> Boolean,
): DurableUploadPermissionPeerProtection {
    var ambiguous = false
    peers.forEach { peer ->
        if (peer.selectionId == targetSelectionId) return@forEach
        val permission = peer.permission
        if (permission == null) {
            ambiguous = true
        } else if (samePermission(targetPermission, permission)) {
            if (peer.grantPreExisting == false) {
                return DurableUploadPermissionPeerProtection.RetainedAppOwnedGrant
            }
            ambiguous = true
        }
    }
    return if (ambiguous) {
        DurableUploadPermissionPeerProtection.Ambiguous
    } else {
        DurableUploadPermissionPeerProtection.None
    }
}

internal fun <Permission> malformedDurableUploadPeerBlocksDirectCleanup(
    malformedPeers: Iterable<DurableUploadPermissionPeer<Permission>>,
    targetSelectionId: String,
    targetPermission: Permission,
    samePermission: (Permission, Permission) -> Boolean,
): Boolean = durableUploadPermissionPeerProtection(
    peers = malformedPeers,
    targetSelectionId = targetSelectionId,
    targetPermission = targetPermission,
    samePermission = samePermission,
) != DurableUploadPermissionPeerProtection.None

internal enum class DurableUploadPermissionCleanupPlan {
    ReleaseThenRemove,
    RemoveWithoutRelease,
    Retain,
}

internal fun durableUploadPermissionCleanupPlan(
    grantPreExisting: Boolean?,
    peerProtection: DurableUploadPermissionPeerProtection,
    permissionAbsent: Boolean = false,
): DurableUploadPermissionCleanupPlan = when {
    grantPreExisting == true -> DurableUploadPermissionCleanupPlan.RemoveWithoutRelease
    peerProtection == DurableUploadPermissionPeerProtection.RetainedAppOwnedGrant ->
        DurableUploadPermissionCleanupPlan.RemoveWithoutRelease
    peerProtection == DurableUploadPermissionPeerProtection.Ambiguous ->
        DurableUploadPermissionCleanupPlan.Retain
    grantPreExisting == false -> DurableUploadPermissionCleanupPlan.ReleaseThenRemove
    permissionAbsent -> DurableUploadPermissionCleanupPlan.RemoveWithoutRelease
    else -> DurableUploadPermissionCleanupPlan.Retain
}

internal fun <Permission> recoverMalformedDurableUploadCapability(
    capability: MalformedDurableUploadCapability,
    permission: Permission?,
    peerProtection: DurableUploadPermissionPeerProtection,
    releasePermission: (Permission) -> Unit,
    isPermissionAbsent: (Permission) -> Boolean,
    removeMetadata: (String) -> Boolean,
): Boolean {
    permission ?: return false
    val grantPreExisting = capability.grantPreExisting
    val permissionAbsent = if (
        grantPreExisting == null &&
        peerProtection == DurableUploadPermissionPeerProtection.None
    ) {
        try {
            isPermissionAbsent(permission)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    } else {
        false
    }
    val cleanupPlan = durableUploadPermissionCleanupPlan(
        grantPreExisting = grantPreExisting,
        peerProtection = peerProtection,
        permissionAbsent = permissionAbsent,
    )
    if (cleanupPlan == DurableUploadPermissionCleanupPlan.Retain) return false
    return releaseDurableUploadCapability(
        releasePermission = {
            if (cleanupPlan == DurableUploadPermissionCleanupPlan.ReleaseThenRemove) {
                releasePermission(permission)
            }
        },
        isPermissionAbsent = {
            cleanupPlan == DurableUploadPermissionCleanupPlan.RemoveWithoutRelease ||
                isPermissionAbsent(permission)
        },
        removeMetadata = { removeMetadata(capability.selectionId) },
    )
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

internal fun durableUploadCleanupStep(action: () -> Boolean): Boolean = try {
    action()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
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
    OwnershipCheckPending("ownership-check-pending"),
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

internal fun shouldRestoreDurableUploadCapabilityAfterOwnershipCheck(
    phase: CapabilityPhase,
    ownedByDurableJob: Boolean,
): Boolean = phase == CapabilityPhase.OwnershipCheckPending && ownedByDurableJob

internal fun isDurableUploadCapabilityReady(phase: CapabilityPhase): Boolean =
    phase == CapabilityPhase.Ready

internal fun durableUploadCapabilityHasCapacity(
    trackedCapabilityCount: Int,
    maximumTrackedCapabilities: Int,
): Boolean {
    require(trackedCapabilityCount >= 0)
    require(maximumTrackedCapabilities > 0)
    return trackedCapabilityCount < maximumTrackedCapabilities
}

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
