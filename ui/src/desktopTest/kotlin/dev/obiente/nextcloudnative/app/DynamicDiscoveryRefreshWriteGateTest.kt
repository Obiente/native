package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_APP_DESCRIPTOR_VERSION
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.EndpointPolicy
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resumeWithException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Re-checking a verified contract keeps its write actions on screen and holds writes instead.
 * The server is synthetic: its first request stays pending until the test releases it with a
 * network failure, and later requests fail at once.
 */
class DynamicDiscoveryRefreshWriteGateTest {
    @Test
    fun aVerifiedContractStaysVerifiedOnScreenWhileItIsCheckedAndWritesWait() = withPendingServer { server, outer ->
        val gate = DynamicContractWriteGate()
        val shown = mutableListOf<DynamicDescriptorDiscovery?>()
        val refresh = outer.launch { refresh(server, gate, shown, appId = "gate_check_hold") }
        server.awaitPendingRequest()

        assertEquals(DynamicContractVersionStatus.VerifiedCurrent, shown.last()?.versionStatus,
            "Write actions must not be withdrawn just because a check is running")
        assertFalse(shown.any { it?.versionStatus == DynamicContractVersionStatus.LastKnownReadOnly })
        val write = async { gate.authorizesWrite(ActionRisk.mutating, verifiedDescriptor) }
        repeat(5) { yield() }
        assertFalse(write.isCompleted, "A write started during the check waits before anything is sent")

        server.failPendingRequest()
        refresh.join()
        assertFalse(write.await(), "A check that could not verify the contract rejects the waiting write")
        assertEquals(DynamicContractVersionStatus.LastKnownReadOnly, shown.last()?.versionStatus,
            "An unverified contract falls back to read-only, as before")
    }

    @Test
    fun cancellingTheCheckRejectsTheWaitingWrite() = withPendingServer { server, outer ->
        val gate = DynamicContractWriteGate()
        val refresh = outer.launch { refresh(server, gate, mutableListOf(), appId = "gate_check_cancel") }
        server.awaitPendingRequest()
        val write = async { gate.authorizesWrite(ActionRisk.mutating, verifiedDescriptor) }
        yield()
        // Leaving the app cancels the check; the network layer then ends the pending request.
        refresh.cancel()
        server.failPendingRequest()
        refresh.join()
        assertFalse(write.await(), "An abandoned check never authorizes writes")
    }

    /**
     * Runs the refresh in the outer scope and always releases the pending request afterwards, so a
     * failed assertion or timeout fails the test instead of waiting on the synthetic server forever.
     */
    private fun withPendingServer(body: suspend CoroutineScope.(PendingServer, CoroutineScope) -> Unit) =
        runBlocking<Unit> {
            val server = PendingServer()
            val outer = this
            try {
                withTimeout(30_000) { body(server, outer) }
            } finally {
                server.releaseIfPending()
            }
        }

    private suspend fun refresh(
        server: PendingServer,
        gate: DynamicContractWriteGate,
        shown: MutableList<DynamicDescriptorDiscovery?>,
        appId: String,
    ) {
        val discovery = verifiedDiscovery(appId)
        refreshDynamicAppDiscovery(
            services = server.services,
            session = NextcloudSession("https://cloud.example.test", "synthetic-user", "synthetic-app-password"),
            app = NextcloudAppEntry(appId, "Synthetic app", null),
            cachedDiscovery = discovery,
            serverVersion = null,
            installedAppVersionHint = discovery.descriptor.app.version,
            serverVersionVerified = true,
            discoveryAttempt = 1,
            writeGate = gate,
            callbacks = DynamicDiscoveryRefreshCallbacks(
                onProgress = {},
                onDiscovery = { shown += it },
                onPublished = {},
                onError = {},
            ),
        )
    }

    private val verifiedDescriptor = verifiedDiscovery("gate_check_hold").descriptor

    private fun verifiedDiscovery(appId: String) = DynamicDescriptorDiscovery(
        descriptor = DynamicAppDescriptor(
            descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
            app = AppIdentity(appId, "Synthetic app", "1.0.0"),
            endpointPolicy = EndpointPolicy(
                serverOrigin = "https://cloud.example.test",
                approvedApiPrefixes = listOf("/apps/$appId"),
            ),
        ),
        sourcePath = null,
        acquisition = DynamicDescriptorAcquisition.SignedAppStorePackage,
        versionStatus = DynamicContractVersionStatus.VerifiedCurrent,
    )

    /** Platform services whose first suspending call stays pending; everything else fails offline. */
    private class PendingServer {
        @Volatile private var pending: Continuation<Any?>? = null
        @Volatile private var released = false

        @Suppress("UNCHECKED_CAST")
        val services: NextcloudPlatformServices = Proxy.newProxyInstance(
            NextcloudPlatformServices::class.java.classLoader,
            arrayOf(NextcloudPlatformServices::class.java),
        ) { _, method, args ->
            val continuation = args?.lastOrNull() as? Continuation<Any?>
            when {
                method.name == "toString" -> "PendingServer"
                continuation != null && !released && pending == null -> {
                    pending = continuation
                    COROUTINE_SUSPENDED
                }
                else -> throw IOException("Synthetic offline server")
            }
        } as NextcloudPlatformServices

        suspend fun awaitPendingRequest() {
            repeat(1_000) {
                if (pending != null) return
                yield()
            }
            assertNotNull(pending, "The check never reached the server")
        }

        fun failPendingRequest() {
            released = true
            assertNotNull(pending).resumeWithException(IOException("Synthetic offline server"))
        }

        fun releaseIfPending() {
            if (released) return
            released = true
            pending?.resumeWithException(IOException("Synthetic offline server"))
        }
    }
}
