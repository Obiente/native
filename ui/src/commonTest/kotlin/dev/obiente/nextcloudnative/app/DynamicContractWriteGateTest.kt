package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_APP_DESCRIPTOR_VERSION
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.EndpointPolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DynamicContractWriteGateTest {
    private val verified = descriptor("1.0.9")
    private val upgraded = descriptor("1.1.0")

    @Test
    fun readsNeverWaitForACheck() = runBlocking<Unit> {
        val gate = DynamicContractWriteGate()
        gate.beginCheck()
        assertTrue(gate.authorizesWrite(ActionRisk.readOnly, verified))
    }

    @Test
    fun aWriteDuringTheCheckWaitsAndContinuesForTheSameVerifiedContract() = runBlocking<Unit> {
        val gate = DynamicContractWriteGate()
        gate.beginCheck()
        val write = async { gate.authorizesWrite(ActionRisk.mutating, verified) }
        repeat(5) { yield() }
        assertFalse(write.isCompleted, "Nothing is sent while the contract is being checked")
        gate.settle(verified)
        assertTrue(write.await())
    }

    @Test
    fun aFailedCheckRejectsTheWaitingWrite() = runBlocking<Unit> {
        val gate = DynamicContractWriteGate()
        gate.beginCheck()
        val write = async { gate.authorizesWrite(ActionRisk.destructive, verified) }
        yield()
        gate.settle(null)
        assertFalse(write.await())
    }

    @Test
    fun aChangedContractRejectsWritesOfferedFromTheOldOne() = runBlocking<Unit> {
        val gate = DynamicContractWriteGate()
        gate.beginCheck()
        val oldWrite = async { gate.authorizesWrite(ActionRisk.mutating, verified) }
        yield()
        gate.settle(upgraded)
        assertFalse(oldWrite.await(), "A write prepared for the previous version must not run against the new one")
        assertTrue(gate.authorizesWrite(ActionRisk.mutating, upgraded))
    }

    @Test
    fun anAbandonedCheckRejectsWritesUntilANewCheckSettles() = runBlocking<Unit> {
        val gate = DynamicContractWriteGate()
        gate.beginCheck()
        val write = async { gate.authorizesWrite(ActionRisk.mutating, verified) }
        yield()
        gate.abandon()
        assertFalse(write.await())
        assertFalse(gate.authorizesWrite(ActionRisk.mutating, verified))
        gate.beginCheck()
        gate.settle(verified)
        assertTrue(gate.authorizesWrite(ActionRisk.mutating, verified))
    }

    @Test
    fun abandoningAfterTheOutcomeKeepsTheOutcome() = runBlocking<Unit> {
        val gate = DynamicContractWriteGate()
        gate.beginCheck()
        gate.settle(verified)
        gate.abandon()
        assertTrue(gate.authorizesWrite(ActionRisk.mutating, verified))
    }

    @Test
    fun writesAreRejectedBeforeAnyCheckHasRun() = runBlocking<Unit> {
        assertFalse(DynamicContractWriteGate().authorizesWrite(ActionRisk.mutating, verified))
    }

    @Test
    fun onlyAVerifiedDiscoveryAuthorizesWrites() {
        val discovery = DynamicDescriptorDiscovery(
            descriptor = verified,
            sourcePath = null,
            acquisition = DynamicDescriptorAcquisition.SignedAppStorePackage,
            versionStatus = DynamicContractVersionStatus.VerifiedCurrent,
        )
        assertEquals(verified, discovery.writeAuthority())
        assertNull(discovery.copy(versionStatus = DynamicContractVersionStatus.LastKnownReadOnly).writeAuthority())
    }

    private fun descriptor(version: String) = DynamicAppDescriptor(
        descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
        app = AppIdentity("tables", "Tables", version),
        endpointPolicy = EndpointPolicy(
            serverOrigin = "https://cloud.example.test",
            approvedApiPrefixes = listOf("/apps/tables"),
        ),
    )
}
