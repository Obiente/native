package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DynamicDiscoveryDiagnosticsTest {
    @Test
    fun canceledCatalogOrStaticProbeStopsDiscoveryImmediately() = runBlocking {
        for (cancelAt in listOf(1, 2)) {
            var requests = 0
            val cancelled = CancellationException("stop")
            assertSame(cancelled, assertFailsWith<CancellationException> {
                discoverDynamicAppDescriptor("https://fixture.invalid", NextcloudAppEntry("example", "Example", null),
                    execute = {
                        requests += 1
                        if (requests == cancelAt) throw cancelled
                        NextcloudApiResponse(404, ByteArray(0), "application/json", etag = null)
                    })
            })
            assertEquals(cancelAt, requests)
        }
    }
    @Test
    fun stagesReportBoundedOutcomesWithoutExceptionOrContractData() = runBlocking {
        val events = mutableListOf<SupportDiagnosticEventDraft>()
        val failure = IllegalArgumentException("private response https://private.invalid/?token=secret")
        assertEquals("private contract", observeDynamicDiscoveryStage(DynamicDiscoveryStage.PackageAcquisition,
            events::add) { "private contract" }.getOrThrow())
        assertNull(observeDynamicDiscoveryStage(DynamicDiscoveryStage.ContractParsing, events::add) { null }.getOrThrow())
        assertSame(failure, observeDynamicDiscoveryStage(DynamicDiscoveryStage.DescriptorCompilation,
            events::add) { throw failure }.exceptionOrNull())
        assertEquals(listOf("completed", "unavailable", "failed"), events.map { it.outcome })
        assertEquals(DynamicDiscoveryStage.entries.map { it.diagnosticValue }, events.map { it.fields.single().value })
        assertTrue(events.all { it.message == null && it.exception == null && it.fields.single().name == "stage" })
    }

    @Test
    fun cancellationIsRethrownWithoutFailureEventAndRecorderFailureDoesNotLoseContract() = runBlocking {
        val events = mutableListOf<SupportDiagnosticEventDraft>()
        val cancelled = CancellationException("stop")
        assertSame(cancelled, assertFailsWith<CancellationException> {
            observeDynamicDiscoveryStage(DynamicDiscoveryStage.PackageAcquisition, events::add) { throw cancelled }
        })
        assertTrue(events.isEmpty())
        assertEquals("contract", observeDynamicDiscoveryStage(DynamicDiscoveryStage.ContractParsing,
            { throw IllegalStateException("recorder unavailable") }) { "contract" }.getOrThrow())
    }
}
