package dev.obiente.nextcloudnative

import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidLocalUploadCapabilityOverflowRecoveryTest {
    @Test
    fun `over admission limit capability state remains recoverable`() {
        val storedIds = (1..65).map { index -> "selection-$index" }

        val snapshot = loadDurableUploadCapabilitySnapshot(
            cachedCapabilities = emptyMap(),
            storedSelectionIds = storedIds,
            maximumRecoverableCapabilities = 1_024,
            loadStoredCapability = { selectionId -> "content://synthetic/$selectionId" },
        )

        assertEquals(65, snapshot.trackedCapabilityCount)
        assertEquals(65, snapshot.capabilities.size)
        assertFalse(
            durableUploadCapabilityHasCapacity(
                trackedCapabilityCount = snapshot.trackedCapabilityCount,
                maximumTrackedCapabilities = 64,
            ),
        )
    }

    @Test
    fun `malformed capability owned by a durable job is excluded from recovery`() {
        val queued = malformed("selection-queued")
        val abandoned = malformed("selection-abandoned")

        val recoverable = malformedDurableUploadCapabilitiesForRecovery(
            capabilities = mapOf(
                queued.selectionId to queued,
                abandoned.selectionId to abandoned,
            ),
            ownedSelectionIds = setOf(queued.selectionId),
        )

        assertEquals(listOf(abandoned), recoverable)
    }

    @Test
    fun `oversized state is quarantined without retaining or loading unbounded rows`() {
        var rowLoads = 0
        val storedIds = (1..1_025).map { index -> "selection-$index" }
        val scan = DurableUploadCapabilityRecoveryScan<String>()

        val snapshot = scan.loadPage(emptyMap(), storedIds, maximumRows = 1_024) {
            rowLoads += 1
            "content://synthetic/$it"
        }
        assertFalse(snapshot.scanComplete)
        assertTrue(snapshot.recoveryQuarantined)
        assertEquals(1_025, snapshot.trackedCapabilityCount)
        assertTrue(snapshot.capabilities.isEmpty())
        assertTrue(snapshot.malformedCapabilities.isEmpty())
        assertEquals(0, rowLoads)
    }

    @Test
    fun `malformed ciphertext without a permission identity remains quarantined without polling`() {
        assertFalse(
            malformedDurableUploadCapabilityCanBecomeActionable(
                malformed("selection-corrupt").copy(cleanupPermissionIdentity = null),
            ),
        )
        assertTrue(
            malformedDurableUploadCapabilityCanBecomeActionable(
                malformed("selection-recoverable", "content://synthetic/recoverable"),
            ),
        )
    }

    @Test
    fun `paged recovery isolates malformed rows without swallowing transient failures`() {
        val scan = DurableUploadCapabilityRecoveryScan<String>()
        val malformed = AndroidLocalUploadCapabilityMalformedException("invalid metadata")

        assertFailsWith<GeneralSecurityException> {
            scan.loadPage(
                cachedCapabilities = emptyMap(),
                storedSelectionIds = listOf("selection-malformed", "selection-transient"),
                maximumRows = 2,
            ) { selectionId ->
                when (selectionId) {
                    "selection-malformed" -> throw malformed
                    else -> throw GeneralSecurityException("synthetic decryption failure")
                }
            }
        }

        val recovered = scan.loadPage(
            cachedCapabilities = emptyMap(),
            storedSelectionIds = listOf("selection-malformed", "selection-transient"),
            maximumRows = 2,
            loadStoredCapability = { selectionId -> "content://synthetic/$selectionId" },
        )

        assertTrue(recovered.scanComplete)
        assertEquals(setOf("selection-malformed"), recovered.malformedCapabilities.keys)
        assertEquals("content://synthetic/selection-transient", recovered.capabilities["selection-transient"])
    }

    @Test
    fun `owned malformed app grant permits abandoned valid metadata cleanup without revocation`() {
        val sharedUri = "content://synthetic/shared"
        val owned = malformed("selection-owned", sharedUri)
        val protection = protection(
            targetSelectionId = "selection-valid",
            targetPermission = sharedUri,
            peers = arrayOf(owned.peer()),
        )
        val cleanupPlan = durableUploadPermissionCleanupPlan(
            grantPreExisting = false,
            peerProtection = protection,
        )
        val events = mutableListOf<String>()

        val recovered = releaseDurableUploadCapability(
            releasePermission = {
                if (cleanupPlan == DurableUploadPermissionCleanupPlan.ReleaseThenRemove) {
                    events += "release"
                }
            },
            isPermissionAbsent = {
                cleanupPlan == DurableUploadPermissionCleanupPlan.RemoveWithoutRelease
            },
            removeMetadata = { true.also { events += "remove-valid" } },
        )

        assertEquals(DurableUploadPermissionPeerProtection.RetainedAppOwnedGrant, protection)
        assertTrue(recovered)
        assertEquals(listOf("remove-valid"), events)
    }

    @Test
    fun `owned malformed app grant permits abandoned malformed metadata cleanup without revocation`() {
        val sharedUri = "content://synthetic/shared"
        val owned = malformed("selection-owned", sharedUri)
        val abandoned = malformed("selection-abandoned", sharedUri)
        val protection = protection(
            targetSelectionId = abandoned.selectionId,
            targetPermission = sharedUri,
            peers = arrayOf(owned.peer(), abandoned.peer()),
        )
        val events = mutableListOf<String>()

        val recovered = recoverMalformedDurableUploadCapability(
            capability = abandoned,
            permission = sharedUri,
            peerProtection = protection,
            releasePermission = { events += "release" },
            isPermissionAbsent = { false },
            removeMetadata = { true.also { events += "remove-malformed" } },
        )

        assertEquals(DurableUploadPermissionPeerProtection.RetainedAppOwnedGrant, protection)
        assertTrue(recovered)
        assertEquals(listOf("remove-malformed"), events)
    }

    @Test
    fun `ambiguous malformed peers block direct valid and malformed cleanup`() {
        val sharedUri = "content://synthetic/shared"
        val appOwnedPeer = malformed("selection-app-owned", sharedUri).peer()
        val exactPeer = malformed("selection-peer", sharedUri).copy(grantPreExisting = true).peer()
        val unknownPeer = malformed("selection-unknown").copy(cleanupPermissionIdentity = null).peer()

        listOf(appOwnedPeer, exactPeer, unknownPeer).forEach { peer ->
            assertTrue(
                malformedDurableUploadPeerBlocksDirectCleanup(
                    malformedPeers = listOf(peer),
                    targetSelectionId = "selection-valid",
                    targetPermission = sharedUri,
                    samePermission = String::equals,
                ),
            )
            assertTrue(
                malformedDurableUploadPeerBlocksDirectCleanup(
                    malformedPeers = listOf(peer),
                    targetSelectionId = "selection-malformed",
                    targetPermission = sharedUri,
                    samePermission = String::equals,
                ),
            )
        }
    }

    @Test
    fun `unknowable malformed peer quarantines blocked cleanup without polling`() {
        val disposition = durableUploadMalformedPeerCleanupDisposition(
            malformedPeers = listOf(peer("selection-unknown", permission = null, grantPreExisting = null)),
            targetSelectionId = "selection-valid",
            targetPermission = "content://synthetic/valid",
            samePermission = String::equals,
        )

        assertEquals(DurableUploadMalformedPeerCleanupDisposition.Quarantine, disposition)
    }

    @Test
    fun `peer protection keeps ambiguous provenance distinct from a retained app grant`() {
        val sharedUri = "content://synthetic/shared"
        val exactAppGrant = peer("selection-false", sharedUri, grantPreExisting = false)
        val exactPreExisting = peer("selection-true", sharedUri, grantPreExisting = true)
        val exactUnknownGrant = peer("selection-null", sharedUri, grantPreExisting = null)
        val unknownPermission = peer("selection-unknown", permission = null, grantPreExisting = false)

        assertEquals(
            DurableUploadPermissionPeerProtection.RetainedAppOwnedGrant,
            protection("selection-target", sharedUri, arrayOf(exactAppGrant)),
        )
        listOf(exactPreExisting, exactUnknownGrant, unknownPermission).forEach { peer ->
            val protection = protection("selection-target", sharedUri, arrayOf(peer))
            assertEquals(
                DurableUploadPermissionPeerProtection.Ambiguous,
                protection,
            )
            assertEquals(
                DurableUploadPermissionCleanupPlan.Retain,
                durableUploadPermissionCleanupPlan(false, protection),
            )
        }
    }

    @Test
    fun `valid false grant is retained while only peer claims preexisting ownership`() {
        val protection = protection(
            targetSelectionId = "selection-false",
            targetPermission = "content://synthetic/shared",
            peers = arrayOf(
                peer("selection-false", "content://synthetic/shared", grantPreExisting = false),
                peer("selection-true", "content://synthetic/shared", grantPreExisting = true),
            ),
        )

        assertEquals(DurableUploadPermissionPeerProtection.Ambiguous, protection)
        assertEquals(
            DurableUploadPermissionCleanupPlan.Retain,
            durableUploadPermissionCleanupPlan(false, protection),
        )
    }

    @Test
    fun `cleanup planning preserves unknown target provenance until absence is proven`() {
        assertEquals(
            DurableUploadPermissionCleanupPlan.RemoveWithoutRelease,
            durableUploadPermissionCleanupPlan(
                grantPreExisting = null,
                peerProtection = DurableUploadPermissionPeerProtection.RetainedAppOwnedGrant,
            ),
        )
        assertEquals(
            DurableUploadPermissionCleanupPlan.Retain,
            durableUploadPermissionCleanupPlan(
                grantPreExisting = null,
                peerProtection = DurableUploadPermissionPeerProtection.Ambiguous,
            ),
        )
        assertEquals(
            DurableUploadPermissionCleanupPlan.Retain,
            durableUploadPermissionCleanupPlan(
                grantPreExisting = null,
                peerProtection = DurableUploadPermissionPeerProtection.None,
                permissionAbsent = false,
            ),
        )
        assertEquals(
            DurableUploadPermissionCleanupPlan.RemoveWithoutRelease,
            durableUploadPermissionCleanupPlan(
                grantPreExisting = null,
                peerProtection = DurableUploadPermissionPeerProtection.None,
                permissionAbsent = true,
            ),
        )
        assertEquals(
            DurableUploadPermissionCleanupPlan.RemoveWithoutRelease,
            durableUploadPermissionCleanupPlan(
                grantPreExisting = true,
                peerProtection = DurableUploadPermissionPeerProtection.Ambiguous,
            ),
        )
    }

    @Test
    fun `mutable malformed ownership leaves the final duplicate to revoke the grant`() {
        val sharedUri = "content://synthetic/shared"
        val peers = linkedMapOf(
            "selection-one" to peer("selection-one", sharedUri, grantPreExisting = false),
            "selection-two" to peer("selection-two", sharedUri, grantPreExisting = false),
        )

        val firstPlan = durableUploadPermissionCleanupPlan(
            grantPreExisting = false,
            peerProtection = protection("selection-one", sharedUri, peers.values.toTypedArray()),
        )
        peers.remove("selection-one")
        val secondPlan = durableUploadPermissionCleanupPlan(
            grantPreExisting = false,
            peerProtection = protection("selection-two", sharedUri, peers.values.toTypedArray()),
        )

        assertEquals(DurableUploadPermissionCleanupPlan.RemoveWithoutRelease, firstPlan)
        assertEquals(DurableUploadPermissionCleanupPlan.ReleaseThenRemove, secondPlan)
    }

    private fun protection(
        targetSelectionId: String,
        targetPermission: String,
        peers: Array<DurableUploadPermissionPeer<String>>,
    ): DurableUploadPermissionPeerProtection = durableUploadPermissionPeerProtection(
        peers = peers.asIterable(),
        targetSelectionId = targetSelectionId,
        targetPermission = targetPermission,
        samePermission = String::equals,
    )

    private fun peer(
        selectionId: String,
        permission: String?,
        grantPreExisting: Boolean?,
    ) = DurableUploadPermissionPeer(selectionId, permission, grantPreExisting)

    private fun MalformedDurableUploadCapability.peer() = DurableUploadPermissionPeer(
        selectionId,
        cleanupPermissionIdentity,
        grantPreExisting,
    )

    private fun malformed(
        selectionId: String,
        permissionIdentity: String = "content://synthetic/$selectionId",
    ) = MalformedDurableUploadCapability(
        selectionId = selectionId,
        cleanupPermissionIdentity = permissionIdentity,
        grantPreExisting = false,
    )
}
