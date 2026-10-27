package dev.obiente.nextcloudnative.nativeui.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicAuxiliaryDestinationsTest {
    private fun destination(resourceId: String, label: String = resourceId) =
        DynamicNavigationDestination(layoutId = "$resourceId.list", label = label, resourceId = resourceId, actionId = "$resourceId-read")

    @Test
    fun `session token and connection test reads are auxiliary`() {
        assertTrue(destination("session", "Session").isAuxiliaryProtocolDestination())
        assertTrue(destination("token", "Token").isAuxiliaryProtocolDestination())
        assertTrue(destination("accountTest", "Test").isAuxiliaryProtocolDestination())
        assertTrue(destination("csrf_token").isAuxiliaryProtocolDestination())
    }

    @Test
    fun `whole words are matched so content resources stay visible`() {
        listOf("latest", "shopping", "contests", "polls", "overview", "testimonials", "mapping")
            .forEach { id -> assertFalse(destination(id).isAuxiliaryProtocolDestination(), id) }
    }

    @Test
    fun `filtering never removes the only destination`() {
        val polls = destination("polls", "Polls")
        val session = destination("session", "Session")
        assertEquals(listOf(polls), listOf(polls, session).withoutAuxiliaryProtocolDestinations())
        assertEquals(listOf(session), listOf(session).withoutAuxiliaryProtocolDestinations())
    }
}
