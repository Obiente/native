package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.effectiveNativeResourceId
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class DynamicMessageReadSelectionTest {
    private val proof = listOf(Provenance(ProvenanceKind.verifiedAppPackage, "synthetic", "contract"))
    private val parameter = HttpParameter("id", true, JsonPrimitive("integer"), ParameterSource.resourceField)
    private fun action(id: String, resource: String, suffix: String) = DynamicAction(id, id, resource,
        ActionIntent.read, ActionRisk.readOnly, false,
        DynamicHttpBinding(HttpMethod.GET, "/apps/example/api/messages/{id}/$suffix", listOf(parameter)),
        confidence = Confidence.high, provenance = proof)
    private val thread = action("thread.read", "thread", "thread")
    private val body = action("body.read", "body", "body")
    private val descriptor = DynamicAppDescriptor("1.0", AppIdentity("example", "Example", "1"),
        EndpointPolicy("https://fixture.invalid", listOf("/apps/example/api")),
        resources = listOf(DynamicResource("messages", "Messages", true, confidence = Confidence.high)),
        actions = listOf(thread, body))
    private val envelope = NativeRecord("23", mapOf("id" to "protocol-id"),
        displayValues = mapOf("databaseId" to "23", "mailboxId" to "7"))

    @Test
    fun exactRouteResourceWinsWhenSingularAndPluralResourcesBothExist() {
        val both = descriptor.copy(resources = descriptor.resources +
            DynamicResource("message", "Message", true, confidence = Confidence.high))
        val selected = assertNotNull(dynamicThreadMessageReadSelection(both, thread.id, envelope,
            mapOf("id" to "99", "mailboxId" to "7")))
        assertEquals("messages", selected.resourceId)
        assertEquals("23", selected.parameters["id"])
        assertFalse(selected.record.actionSafeIdentity)
        val singular = descriptor.copy(resources = listOf(
            DynamicResource("message", "Message", true, confidence = Confidence.high)))
        assertEquals("message", assertNotNull(dynamicThreadMessageReadSelection(singular, thread.id,
            envelope, emptyMap())).resourceId)
    }
    @Test
    fun threadEnvelopeUsesSelectedDatabaseIdentityForExistingBodyAndKeepsMailboxSeparate() {
        val result = assertNotNull(dynamicThreadMessageReadSelection(descriptor, thread.id, envelope,
            mapOf("id" to "99", "messageId" to "99", "mailboxId" to "7", "accountId" to "3")))
        assertEquals("messages", result.resourceId)
        assertEquals("messages", result.record.effectiveNativeResourceId("thread"))
        assertEquals("23", result.record.id)
        assertFalse(result.record.actionSafeIdentity)
        assertEquals(mapOf("id" to "23", "mailboxId" to "7", "accountId" to "3"), result.parameters)
        val resolved = descriptor.resolveDynamicRecordReadParameters(body.id,
            DynamicResourceRecordContext(result.resourceId, result.record.id, result.record.values,
                result.parameters, actionSafeIdentity = result.record.actionSafeIdentity))
        assertEquals("23", assertNotNull(resolved)["id"])
        assertEquals("protocol-id", envelope.values["id"])
    }

    @Test
    fun missingConflictingOrUnsafeIdentityDoesNotRebind() {
        listOf(envelope.copy(id = "7"), envelope.copy(displayValues = emptyMap()),
            envelope.copy(displayValues = envelope.displayValues + ("databaseId" to "../23")),
            envelope.copy(actionBindingProvenanceValid = false)).forEach {
            assertNull(dynamicThreadMessageReadSelection(descriptor, thread.id, it, emptyMap()))
        }
        assertNull(dynamicThreadMessageReadSelection(descriptor, thread.id, envelope, mapOf("mailboxId" to "8")))
    }

    @Test
    fun verifiedSiblingBodyAndExactThreadRouteAreRequired() {
        listOf(thread.copy(provenance = emptyList()), thread.copy(confidence = Confidence.low),
            thread.copy(binding = thread.binding.copy(method = HttpMethod.POST)),
            thread.copy(binding = thread.binding.copy(path = "/apps/example/api/mailboxes/{id}/thread")),
            thread.copy(binding = thread.binding.copy(path = "/unapproved/messages/{id}/thread"))).forEach {
            assertNull(dynamicThreadMessageReadSelection(descriptor.copy(actions = listOf(it, body)),
                thread.id, envelope, emptyMap()))
        }
        listOf(emptyList(), listOf(body.copy(provenance = emptyList())),
            listOf(body.copy(binding = body.binding.copy(path = "/apps/example/api/other/{id}/body")))).forEach {
            assertNull(dynamicThreadMessageReadSelection(descriptor.copy(actions = listOf(thread) + it),
                thread.id, envelope, emptyMap()))
        }
        assertNull(dynamicThreadMessageReadSelection(descriptor, body.id, envelope, emptyMap()))
    }
}
