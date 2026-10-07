package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.*
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class NativeMailReadingNavigationTest {
    private val proof = listOf(Provenance(ProvenanceKind.verifiedAppPackage, "synthetic", "Mail read contract"))
    private fun action(section: String) = DynamicAction(section, section, "messages", ActionIntent.read,
        ActionRisk.readOnly, false, DynamicHttpBinding(HttpMethod.GET, "/apps/mail/api/messages/{id}/$section",
            listOf(HttpParameter("id", true, JsonPrimitive("integer"), ParameterSource.resourceField))),
        confidence = Confidence.high, provenance = proof)
    private val actions = listOf("dkim", "source", "raw", "itineraries", "smartreply", "thread", "body").map(::action)
    private val descriptor = DynamicAppDescriptor(DYNAMIC_APP_DESCRIPTOR_VERSION, AppIdentity("mail", "Mail", "5.12.2"),
        EndpointPolicy("https://fixture.invalid", listOf("/apps/mail")), actions = actions,
        resources = listOf("accounts", "mailboxes", "messages").map {
            DynamicResource(it, it, true, emptyList(), confidence = Confidence.high, provenance = proof)
        })
    private val destinations = actions.map { action ->
        DynamicNavigationDestination(action.id, action.label, action.resourceId, action.id, mapOf("id" to "7")) to
            ViewSpec(action.id, action.label, action.resourceId, NativeComponent.detail, action.id, Confidence.high)
    }
    @Test fun messageAndConversationArePrimaryWithoutChangingVerifiedContractsOrBindings() {
        val result = nativeMailReadingDestinations(descriptor, destinations)
        assertEquals(listOf("body", "thread"), result.map { it.first.actionId })
        assertEquals(listOf("Message", "Conversation"), result.map { it.first.label })
        assertTrue(result.all { it.first.pathParameterValues == mapOf("id" to "7") })
        assertEquals(actions, descriptor.actions)
        assertEquals(listOf(0, 1), result.map { nativeMailReadingOrder(descriptor, it.first) })
        assertEquals("Read the message and attachment details", nativeMailReadingSupportingText(descriptor, result.first().first))
    }
    @Test fun noPolicyIsAppliedToUnverifiedOrUnrelatedReadShapes() {
        val unrelated = descriptor.copy(resources = descriptor.resources.filterNot { it.id == "mailboxes" })
        assertEquals(destinations, nativeMailReadingDestinations(unrelated, destinations))
        for (altered in listOf(actions.map { it.copy(provenance = emptyList()) },
            actions.map { it.copy(confidence = Confidence.low) },
            actions.map { it.copy(binding = it.binding.copy(path = "/foreign/messages/{id}/raw")) },
            actions.map { it.copy(binding = it.binding.copy(method = HttpMethod.POST)) })) {
            assertEquals(destinations, nativeMailReadingDestinations(descriptor.copy(actions = altered), destinations))
        }
    }
}
