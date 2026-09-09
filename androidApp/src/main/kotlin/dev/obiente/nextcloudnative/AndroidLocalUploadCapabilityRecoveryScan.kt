package dev.obiente.nextcloudnative

import kotlinx.coroutines.CancellationException

internal class DurableUploadCapabilityRecoveryScan<Capability> {
    private val capabilities = linkedMapOf<String, Capability>()
    private val malformed = linkedMapOf<String, MalformedDurableUploadCapability>()

    fun loadPage(
        cachedCapabilities: Map<String, Capability>,
        storedSelectionIds: Iterable<String>,
        maximumRows: Int,
        loadStoredCapability: (String) -> Capability?,
    ): DurableUploadCapabilitySnapshot<Capability> {
        require(maximumRows > 0)
        val storedIds = linkedSetOf<String>()
        storedSelectionIds.forEach { selectionId ->
            if (selectionId !in storedIds && storedIds.size == maximumRows) {
                capabilities.clear()
                malformed.clear()
                return DurableUploadCapabilitySnapshot(
                    capabilities = emptyMap(),
                    malformedCapabilities = emptyMap(),
                    storedCapabilityCount = maximumRows + 1,
                    scanComplete = false,
                    recoveryQuarantined = true,
                )
            }
            storedIds += selectionId
        }
        capabilities.keys.retainAll(storedIds)
        malformed.keys.retainAll(storedIds)
        cachedCapabilities.forEach { (selectionId, capability) ->
            if (selectionId in storedIds) {
                capabilities[selectionId] = capability
                malformed.remove(selectionId)
            }
        }
        storedIds.asSequence()
            .filterNot { selectionId -> selectionId in capabilities || selectionId in malformed }
            .sorted()
            .take(maximumRows)
            .forEach { selectionId ->
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
        val scannedIds = capabilities.keys + malformed.keys
        return DurableUploadCapabilitySnapshot(
            capabilities = capabilities.toMap(),
            malformedCapabilities = malformed.toMap(),
            storedCapabilityCount = storedIds.size,
            scanComplete = scannedIds.containsAll(storedIds),
        )
    }
}
