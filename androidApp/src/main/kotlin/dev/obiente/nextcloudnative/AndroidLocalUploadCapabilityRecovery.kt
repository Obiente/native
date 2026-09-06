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
) {
    val trackedCapabilityCount: Int
        get() = (capabilities.keys + malformedCapabilities.keys).size
}

internal fun <Capability> loadDurableUploadCapabilitySnapshot(
    cachedCapabilities: Map<String, Capability>,
    storedSelectionIds: Iterable<String>,
    loadStoredCapability: (String) -> Capability?,
): DurableUploadCapabilitySnapshot<Capability> {
    val capabilities = cachedCapabilities.toMutableMap()
    val malformed = linkedMapOf<String, MalformedDurableUploadCapability>()
    storedSelectionIds.forEach { selectionId ->
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

internal fun <Permission> recoverMalformedDurableUploadCapability(
    capability: MalformedDurableUploadCapability,
    permission: Permission?,
    ownedByAnotherCapability: Boolean,
    releasePermission: (Permission) -> Unit,
    isPermissionAbsent: (Permission) -> Boolean,
    removeMetadata: (String) -> Boolean,
): Boolean {
    permission ?: return false
    val grantPreExisting = capability.grantPreExisting
    if (grantPreExisting == null) {
        val permissionCanRemain = ownedByAnotherCapability || try {
            isPermissionAbsent(permission)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        return permissionCanRemain && durableUploadCleanupStep {
            removeMetadata(capability.selectionId)
        }
    }
    return releaseDurableUploadCapability(
        releasePermission = {
            if (shouldReleaseDurableUploadPermission(grantPreExisting, ownedByAnotherCapability)) {
                releasePermission(permission)
            }
        },
        isPermissionAbsent = {
            grantPreExisting || ownedByAnotherCapability || isPermissionAbsent(permission)
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
