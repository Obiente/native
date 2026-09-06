package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.LocalUploadFile
import dev.obiente.nextcloudnative.app.LocalUploadSelectionResult
import dev.obiente.nextcloudnative.app.localUploadFile
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidLocalUploadCapabilityLifecycleTest {
    @Test
    fun `selected capability is released when cancellation wins result delivery`() {
        val file = localUploadFile(
            selectionId = "selection-1234567890",
            displayName = "cancelled.txt",
            mimeType = "text/plain",
            sizeBytes = 12L,
        )
        val dispatcher = PausedDispatcher()
        var resumeSelection: ((LocalUploadSelectionResult) -> Unit)? = null
        var delivered = false
        var persistedCapability: LocalUploadFile? = file
        var cachedCapability: LocalUploadFile? = file
        val scopeJob = Job()
        val selectionJob = CoroutineScope(scopeJob + dispatcher).launch(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine<LocalUploadSelectionResult> { continuation ->
                resumeSelection = { result ->
                    resumeLocalUploadSelectionResult(
                        continuation = continuation,
                        result = result,
                        releaseSelected = { cancelledFile ->
                            assertEquals(cachedCapability, cancelledFile)
                            persistedCapability = null
                            cachedCapability = null
                        },
                    )
                }
            }
            delivered = true
        }

        checkNotNull(resumeSelection)(LocalUploadSelectionResult.Selected(file))
        selectionJob.cancel()
        dispatcher.runAll()

        assertTrue(selectionJob.isCancelled)
        assertFalse(delivered)
        assertEquals(null, persistedCapability)
        assertEquals(null, cachedCapability)
        scopeJob.cancel()
    }

    @Test
    fun `non-selected results do not request capability cleanup when delivery is cancelled`() {
        listOf(
            LocalUploadSelectionResult.Cancelled,
            LocalUploadSelectionResult.Rejected("synthetic rejection"),
        ).forEach { result ->
            val dispatcher = PausedDispatcher()
            var resumeSelection: (() -> Unit)? = null
            var releases = 0
            val scopeJob = Job()
            val selectionJob = CoroutineScope(scopeJob + dispatcher).launch(start = CoroutineStart.UNDISPATCHED) {
                suspendCancellableCoroutine<LocalUploadSelectionResult> { continuation ->
                    resumeSelection = {
                        resumeLocalUploadSelectionResult(
                            continuation = continuation,
                            result = result,
                            releaseSelected = { releases += 1 },
                        )
                    }
                }
            }

            checkNotNull(resumeSelection).invoke()
            selectionJob.cancel()
            dispatcher.runAll()

            assertTrue(selectionJob.isCancelled)
            assertEquals(0, releases)
            scopeJob.cancel()
        }
    }

    @Test
    fun `permission is taken before metadata commit and retained after success`() {
        val events = mutableListOf<String>()

        acquireDurableUploadCapability(
            takePermission = { events += "permission" },
            persistMetadata = {
                events += "metadata"
                true
            },
            releasePermission = { events += "release" },
        )

        assertEquals(listOf("permission", "metadata"), events)
    }

    @Test
    fun `failed metadata commit rolls back the persisted uri permission`() {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            acquireDurableUploadCapability(
                takePermission = { events += "permission" },
                persistMetadata = {
                    events += "metadata"
                    false
                },
                releasePermission = { events += "release" },
            )
        }

        assertEquals(listOf("permission", "metadata", "release"), events)
    }

    @Test
    fun `permission release precedes synchronous metadata deletion`() {
        val events = mutableListOf<String>()

        val released = releaseDurableUploadCapability(
            releasePermission = { events += "permission" },
            removeMetadata = {
                events += "metadata"
                true
            },
        )

        assertTrue(released)
        assertEquals(listOf("permission", "metadata"), events)
    }

    @Test
    fun `failed metadata deletion still revokes permission`() {
        var permissionReleased = false

        val released = releaseDurableUploadCapability(
            releasePermission = { permissionReleased = true },
            removeMetadata = { false },
        )

        assertFalse(released)
        assertTrue(permissionReleased)
    }

    @Test
    fun `release exception with exact read grant present retains metadata`() {
        var metadataPresent = true

        val released = releaseDurableUploadCapability(
            releasePermission = { error("provider failure") },
            isPermissionAbsent = { false },
            removeMetadata = { true.also { metadataPresent = false } },
        )

        assertFalse(released)
        assertTrue(metadataPresent)
    }

    @Test
    fun `release exception with exact read grant absent deletes metadata`() {
        var metadataPresent = true

        val released = releaseDurableUploadCapability(
            releasePermission = { error("grant already absent") },
            isPermissionAbsent = { true },
            removeMetadata = { true.also { metadataPresent = false } },
        )

        assertTrue(released)
        assertFalse(metadataPresent)
    }

    @Test
    fun `release verification failure retains metadata`() {
        var metadataPresent = true

        val released = releaseDurableUploadCapability(
            releasePermission = { error("provider failure") },
            isPermissionAbsent = { error("permission query failed") },
            removeMetadata = { true.also { metadataPresent = false } },
        )

        assertFalse(released)
        assertTrue(metadataPresent)
    }

    @Test
    fun `uncached unreadable encrypted metadata is retained without claiming capability release`() {
        var encryptedMetadata: String? = "unreadable-encrypted-capability"
        var permissionReleased = false
        var cleanupPending = true

        val result = resultAfterDurableUploadCapabilityRelease(
            releaseCapability = {
                releaseStoredDurableUploadCapability<String>(
                    cachedCapability = null,
                    loadCapability = { throw GeneralSecurityException("synthetic decryption failure") },
                    releasePermission = { permissionReleased = true },
                    removeMetadata = { true.also { encryptedMetadata = null } },
                )
            },
            completeCapabilityCleanup = { cleanupPending = false },
            releasedResult = "finished",
            retainedResult = "retry",
        )

        assertEquals("retry", result)
        assertTrue(cleanupPending)
        assertFalse(permissionReleased)
        assertEquals("unreadable-encrypted-capability", encryptedMetadata)
    }

    @Test
    fun `restored capability release revokes permission before deleting metadata`() {
        val events = mutableListOf<String>()

        val released = releaseStoredDurableUploadCapability(
            cachedCapability = null,
            loadCapability = {
                events += "restore"
                "content://synthetic/upload"
            },
            releasePermission = { events += "permission:$it" },
            removeMetadata = {
                events += "metadata"
                true
            },
        )

        assertTrue(released)
        assertEquals(
            listOf("restore", "permission:content://synthetic/upload", "metadata"),
            events,
        )
    }

    @Test
    fun `missing capability metadata is an idempotent cleanup success`() {
        var metadataRemovals = 0

        val released = releaseStoredDurableUploadCapability<String>(
            cachedCapability = null,
            loadCapability = { null },
            releasePermission = { error("Missing metadata has no URI grant to release.") },
            removeMetadata = {
                metadataRemovals += 1
                true
            },
        )

        assertTrue(released)
        assertEquals(1, metadataRemovals)
    }

    @Test
    fun `cached capability releases without reading redundant stored metadata`() {
        val events = mutableListOf<String>()

        val released = releaseStoredDurableUploadCapability(
            cachedCapability = "content://cached/upload",
            loadCapability = { error("Cached cleanup must not read stored metadata.") },
            releasePermission = { events += "permission:$it" },
            removeMetadata = {
                events += "metadata"
                true
            },
        )

        assertTrue(released)
        assertEquals(
            listOf("permission:content://cached/upload", "metadata"),
            events,
        )
    }

    @Test
    fun `shared uri cleanup deletes only current capability metadata`() {
        val events = mutableListOf<String>()

        val released = releaseStoredDurableUploadCapability(
            cachedCapability = "content://shared/upload",
            loadCapability = { error("cache is authoritative") },
            otherCapabilityOwnsPermission = { true },
            releasePermission = { events += "permission" },
            isPermissionAbsent = { error("shared grant must remain") },
            removeMetadata = {
                events += "metadata"
                true
            },
        )

        assertTrue(released)
        assertEquals(listOf("metadata"), events)
    }

    @Test
    fun `unreadable shared uri ownership retains current capability`() {
        var metadataPresent = true

        val released = releaseStoredDurableUploadCapability(
            cachedCapability = "content://shared/upload",
            loadCapability = { error("cache is authoritative") },
            otherCapabilityOwnsPermission = { error("another capability is unreadable") },
            releasePermission = { error("ownership must be known before release") },
            isPermissionAbsent = { false },
            removeMetadata = { true.also { metadataPresent = false } },
        )

        assertFalse(released)
        assertTrue(metadataPresent)
    }

    @Test
    fun `cached duplicate selection owns shared uri without reading redundant storage`() {
        var storedLoads = 0

        val capabilities = mergeDurableUploadCapabilities(
            cachedCapabilities = mapOf("selection-cached" to "content://shared/upload"),
            storedSelectionIds = listOf("selection-cached"),
            loadStoredCapability = {
                storedLoads += 1
                error("cached capability must be authoritative")
            },
        )

        assertEquals("content://shared/upload", capabilities["selection-cached"])
        assertTrue(
            durableUploadCapabilityPermissionOwnedByAnother(
                capabilities = capabilities,
                targetSelectionId = "selection-target",
                targetPermission = "content://shared/upload",
                permissionOf = { capability -> capability },
                samePermission = String::equals,
            ),
        )
        assertEquals(0, storedLoads)
    }

    @Test
    fun `persisted duplicate selection owns shared uri`() {
        val capabilities = mergeDurableUploadCapabilities(
            cachedCapabilities = emptyMap(),
            storedSelectionIds = listOf("selection-persisted"),
            loadStoredCapability = { "content://shared/upload" },
        )

        assertEquals("content://shared/upload", capabilities["selection-persisted"])
        assertTrue(
            durableUploadCapabilityPermissionOwnedByAnother(
                capabilities = capabilities,
                targetSelectionId = "selection-target",
                targetPermission = "content://shared/upload",
                permissionOf = { capability -> capability },
                samePermission = String::equals,
            ),
        )
    }

    @Test
    fun `malformed persisted peer does not hide valid capabilities or block a new selection`() {
        val snapshot = loadDurableUploadCapabilitySnapshot(
            cachedCapabilities = emptyMap(),
            storedSelectionIds = listOf("selection-malformed", "selection-valid"),
            loadStoredCapability = { selectionId ->
                if (selectionId == "selection-malformed") {
                    throw AndroidLocalUploadCapabilityMalformedException(
                        message = "invalid phase",
                        cleanupPermissionIdentity = "content://synthetic/malformed",
                        grantPreExisting = false,
                    )
                }
                "content://synthetic/valid"
            },
        )

        assertEquals(mapOf("selection-valid" to "content://synthetic/valid"), snapshot.capabilities)
        assertEquals(setOf("selection-malformed"), snapshot.malformedCapabilities.keys)
        assertEquals(2, snapshot.trackedCapabilityCount)
        assertTrue(
            durableUploadCapabilityHasCapacity(
                trackedCapabilityCount = snapshot.trackedCapabilityCount,
                maximumTrackedCapabilities = 64,
            ),
        )
        assertEquals(
            "content://synthetic/new",
            (snapshot.capabilities + ("selection-new" to "content://synthetic/new"))["selection-new"],
        )
    }

    @Test
    fun `snapshot isolation preserves transient capability read failures`() {
        assertFailsWith<GeneralSecurityException> {
            loadDurableUploadCapabilitySnapshot<String>(
                cachedCapabilities = emptyMap(),
                storedSelectionIds = listOf("selection-unreadable"),
                loadStoredCapability = { throw GeneralSecurityException("synthetic decryption failure") },
            )
        }
    }

    @Test
    fun `corrupt ciphertext remains durable when grant ownership cannot be reconstructed`() {
        var encryptedMetadataPresent = true
        val snapshot = loadDurableUploadCapabilitySnapshot<String>(
            cachedCapabilities = emptyMap(),
            storedSelectionIds = listOf("selection-corrupt"),
            loadStoredCapability = {
                decryptAndroidLocalUploadCapability {
                    throw InvalidSessionCiphertextException("authentication failed")
                }
            },
        )
        val corrupt = snapshot.malformedCapabilities.getValue("selection-corrupt")

        val recovered = recoverMalformedDurableUploadCapability<String>(
            capability = corrupt,
            permission = null,
            peerProtection = DurableUploadPermissionPeerProtection.Ambiguous,
            releasePermission = { error("Unknown permission must not be released.") },
            isPermissionAbsent = { error("Unknown permission cannot be queried.") },
            removeMetadata = { true.also { encryptedMetadataPresent = false } },
        )

        assertEquals(null, corrupt.cleanupPermissionIdentity)
        assertEquals(null, corrupt.grantPreExisting)
        assertFalse(recovered)
        assertTrue(encryptedMetadataPresent)
    }

    @Test
    fun `startup recovery releases a malformed app owned capability before deleting its row`() {
        val events = mutableListOf<String>()
        val capability = MalformedDurableUploadCapability(
            selectionId = "selection-malformed",
            cleanupPermissionIdentity = "content://synthetic/malformed",
            grantPreExisting = false,
        )

        val recovered = recoverMalformedDurableUploadCapability(
            capability = capability,
            permission = checkNotNull(capability.cleanupPermissionIdentity),
            peerProtection = DurableUploadPermissionPeerProtection.None,
            releasePermission = { events += "release:$it" },
            isPermissionAbsent = { false },
            removeMetadata = {
                events += "remove:$it"
                true
            },
        )

        assertTrue(recovered)
        assertEquals(
            listOf(
                "release:content://synthetic/malformed",
                "remove:selection-malformed",
            ),
            events,
        )
    }

    @Test
    fun `malformed capability with unknown grant ownership remains durable while permission exists`() {
        var metadataPresent = true

        val recovered = recoverMalformedDurableUploadCapability(
            capability = MalformedDurableUploadCapability(
                selectionId = "selection-malformed",
                cleanupPermissionIdentity = "content://synthetic/malformed",
                grantPreExisting = null,
            ),
            permission = "content://synthetic/malformed",
            peerProtection = DurableUploadPermissionPeerProtection.None,
            releasePermission = { error("unknown ownership must not release") },
            isPermissionAbsent = { false },
            removeMetadata = { true.also { metadataPresent = false } },
        )

        assertFalse(recovered)
        assertTrue(metadataPresent)
    }

    @Test
    fun `unreadable persisted duplicate ownership fails closed`() {
        assertFailsWith<GeneralSecurityException> {
            mergeDurableUploadCapabilities(
                cachedCapabilities = emptyMap(),
                storedSelectionIds = listOf("selection-unreadable"),
                loadStoredCapability = { throw GeneralSecurityException("synthetic decryption failure") },
            )
        }
    }

    @Test
    fun `failed acquisition rollback retains capability record for recovery`() {
        var acquiringTracked = false
        var capabilityClears = 0
        var recoveryRequests = 0

        assertFailsWith<IllegalStateException> {
            acquireDurableUploadCapability(
                persistAcquiring = { true.also { acquiringTracked = true } },
                takePermission = {},
                persistMetadata = { false },
                releasePermission = { error("provider failure") },
                isPermissionAbsent = { false },
                removeCapability = { true.also { capabilityClears += 1 } },
                onRollbackRetained = { recoveryRequests += 1 },
            )
        }

        assertTrue(acquiringTracked)
        assertEquals(0, capabilityClears)
        assertEquals(1, recoveryRequests)
    }

    @Test
    fun `ambiguous permission acquisition retains capability record for recovery`() {
        val expected = IllegalStateException("binder failure")
        var capabilityClears = 0
        var recoveryRequests = 0

        val actual = assertFailsWith<IllegalStateException> {
            acquireDurableUploadCapability(
                persistAcquiring = { true },
                takePermission = { throw expected },
                persistMetadata = { error("metadata must not be written") },
                releasePermission = { error("ambiguous acquisition is reconciled later") },
                removeCapability = { true.also { capabilityClears += 1 } },
                onRollbackRetained = { recoveryRequests += 1 },
            )
        }

        assertTrue(actual === expected)
        assertEquals(0, capabilityClears)
        assertEquals(1, recoveryRequests)
    }

    @Test
    fun `failed ready persistence cleans possibly written capability after grant release`() {
        val events = mutableListOf<String>()
        var persistedPhase: CapabilityPhase? = null

        assertFailsWith<IllegalStateException> {
            acquireDurableUploadCapability(
                persistAcquiring = {
                    events += "acquiring"
                    true.also { persistedPhase = CapabilityPhase.Acquiring }
                },
                takePermission = { events += "permission" },
                persistMetadata = {
                    events += "ready-false"
                    false.also { persistedPhase = CapabilityPhase.Ready }
                },
                markCleanupPending = {
                    events += "cleanup-pending"
                    true.also { persistedPhase = CapabilityPhase.CleanupPending }
                },
                releasePermission = { events += "release" },
                removeCapability = {
                    events += "metadata"
                    true.also { persistedPhase = null }
                },
            )
        }

        assertEquals(
            listOf("acquiring", "permission", "ready-false", "cleanup-pending", "release", "metadata"),
            events,
        )
        assertEquals(null, persistedPhase)
    }

    @Test
    fun `current ready record is retained unless cleanup was requested`() {
        assertFalse(
            shouldRecoverDurableUploadCapability(
                phase = CapabilityPhase.Ready,
                processGeneration = "current-generation",
                currentProcessGeneration = "current-generation",
                ownedByDurableJob = false,
                cleanupExplicitlyPending = false,
            ),
        )
        assertTrue(
            shouldRecoverDurableUploadCapability(
                phase = CapabilityPhase.Ready,
                processGeneration = "current-generation",
                currentProcessGeneration = "current-generation",
                ownedByDurableJob = false,
                cleanupExplicitlyPending = true,
            ),
        )
    }

    @Test
    fun `prior ready and cleanup phases recover unless durable job owns them`() {
        assertTrue(
            shouldRecoverDurableUploadCapability(
                phase = CapabilityPhase.Ready,
                processGeneration = "prior-generation",
                currentProcessGeneration = "current-generation",
                ownedByDurableJob = false,
                cleanupExplicitlyPending = false,
            ),
        )
        assertTrue(
            shouldRecoverDurableUploadCapability(
                phase = CapabilityPhase.CleanupPending,
                processGeneration = "current-generation",
                currentProcessGeneration = "current-generation",
                ownedByDurableJob = false,
                cleanupExplicitlyPending = false,
            ),
        )
        assertFalse(
            shouldRecoverDurableUploadCapability(
                phase = CapabilityPhase.CleanupPending,
                processGeneration = "prior-generation",
                currentProcessGeneration = "current-generation",
                ownedByDurableJob = true,
                cleanupExplicitlyPending = false,
            ),
        )
    }

    @Test
    fun `preexisting or shared grants are never revoked by capability cleanup`() {
        assertFalse(
            shouldReleaseDurableUploadPermission(
                grantPreExisting = true,
                ownedByAnotherCapability = false,
            ),
        )
        assertFalse(
            shouldReleaseDurableUploadPermission(
                grantPreExisting = false,
                ownedByAnotherCapability = true,
            ),
        )
        assertTrue(
            shouldReleaseDurableUploadPermission(
                grantPreExisting = false,
                ownedByAnotherCapability = false,
            ),
        )
    }

    @Test
    fun `failed acquiring commit clears possible record before taking permission`() {
        val events = mutableListOf<String>()
        var capabilityPresent = false
        var recoveryRequests = 0

        assertFailsWith<IllegalStateException> {
            acquireDurableUploadCapability(
                persistAcquiring = {
                    events += "acquiring-false"
                    capabilityPresent = true
                    false
                },
                takePermission = { events += "permission" },
                persistMetadata = {
                    events += "ready"
                    true
                },
                releasePermission = { events += "release" },
                removeCapability = {
                    events += "metadata"
                    true.also { capabilityPresent = false }
                },
                onRollbackRetained = { recoveryRequests += 1 },
            )
        }

        assertEquals(listOf("acquiring-false", "metadata"), events)
        assertFalse(capabilityPresent)
        assertEquals(1, recoveryRequests)
    }

    @Test
    fun `only ready capability phase may open or enqueue`() {
        assertTrue(isDurableUploadCapabilityReady(CapabilityPhase.Ready))
        assertFalse(isDurableUploadCapabilityReady(CapabilityPhase.Acquiring))
        assertFalse(isDurableUploadCapabilityReady(CapabilityPhase.OwnershipCheckPending))
        assertFalse(isDurableUploadCapabilityReady(CapabilityPhase.CleanupPending))
    }

    @Test
    fun `ownership check intent restores only after the durable job is found`() {
        assertTrue(
            shouldRestoreDurableUploadCapabilityAfterOwnershipCheck(
                phase = CapabilityPhase.OwnershipCheckPending,
                ownedByDurableJob = true,
            ),
        )
        assertFalse(
            shouldRestoreDurableUploadCapabilityAfterOwnershipCheck(
                phase = CapabilityPhase.OwnershipCheckPending,
                ownedByDurableJob = false,
            ),
        )
        assertTrue(
            shouldRecoverDurableUploadCapability(
                phase = CapabilityPhase.OwnershipCheckPending,
                processGeneration = "current-generation",
                currentProcessGeneration = "current-generation",
                ownedByDurableJob = false,
                cleanupExplicitlyPending = false,
            ),
        )
    }

    @Test
    fun `cancelled capability delivery publishes then cleans without leaking cleanup failure`() {
        val events = mutableListOf<String>()

        val delivered = finalizeDurableUploadCapabilityDelivery(
            publishReady = { events += "ready" },
            continuationIsActive = { false },
            cleanupUndelivered = {
                events += "cleanup"
                error("synthetic retained cleanup")
            },
        )

        assertFalse(delivered)
        assertEquals(listOf("ready", "cleanup"), events)
    }

    @Test
    fun `retained cleanup requests recovery and reports false`() {
        var recoveryRequests = 0

        val released = retainDurableUploadCapabilityCleanup { recoveryRequests += 1 }

        assertFalse(released)
        assertEquals(1, recoveryRequests)
    }

    @Test
    fun `legacy nullable generation stays absent across cleanup serialization`() {
        val payload = JSONObject().put("phase", CapabilityPhase.CleanupPending.persistedValue)

        assertEquals(null, payload.optionalStrictString("processGeneration"))
        assertFalse(payload.has("processGeneration"))
    }

    @Test
    fun `legacy upload grants remain app owned while new provenance stays explicit`() {
        val legacy = JSONObject()
        val preExisting = JSONObject().put("grantPreExisting", true)
        val appOwned = JSONObject().put("grantPreExisting", false)
        val malformed = JSONObject().put("grantPreExisting", "false")

        assertFalse(persistedDurableUploadGrantPreExisting(legacy))
        assertTrue(persistedDurableUploadGrantPreExisting(preExisting))
        assertFalse(persistedDurableUploadGrantPreExisting(appOwned))
        assertFailsWith<IllegalArgumentException> {
            persistedDurableUploadGrantPreExisting(malformed)
        }
    }

    @Test
    fun `capability acquisition reserves space before reaching the persisted limit`() {
        assertTrue(durableUploadCapabilityHasCapacity(trackedCapabilityCount = 63, maximumTrackedCapabilities = 64))
        assertFalse(durableUploadCapabilityHasCapacity(trackedCapabilityCount = 64, maximumTrackedCapabilities = 64))
    }

    @Test
    fun `capability restore preserves cancellation`() {
        assertFailsWith<CancellationException> {
            releaseStoredDurableUploadCapability<String>(
                cachedCapability = null,
                loadCapability = { throw CancellationException("cleanup stopped") },
                releasePermission = {},
                removeMetadata = { true },
            )
        }
    }

    private class PausedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}
