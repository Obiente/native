package dev.obiente.nextcloudnative.nativeui.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeRosterPresentationTest {
    @Test
    fun `projects with participants use the shared roster projection`() {
        val roster = requireNotNull(
            nativeRosterPresentation(
                NativeRecord(
                    id = "project-9",
                    values = mapOf("title" to "Launch project", "ownerId" to "ava"),
                    structuredValues = mapOf(
                        "participants" to listValue(
                            objectValue("uid" to "ava", "label" to "Ava", "score" to "21"),
                            objectValue("uid" to "milo", "label" to "Milo", "score" to "13"),
                        ),
                        "pendingInvitations" to listValue(objectValue("invitee" to "nora")),
                    ),
                ),
            ),
        )

        assertEquals("Launch project", roster.name)
        assertEquals(listOf("ava", "milo"), roster.people.map(NativeRosterPerson::userId))
        assertTrue(roster.people.single { it.userId == "ava" }.owner)
        assertEquals(listOf("nora"), roster.invitations.map(NativeRosterInvitation::userId))
    }

    @Test
    fun `ordinary records do not opt into the roster surface`() {
        assertNull(
            nativeRosterPresentation(
                NativeRecord(id = "note-1", values = mapOf("title" to "Planning notes")),
            ),
        )
    }

    @Test
    fun `safe observed scalars describe a roster without becoming mutation values`() {
        val roster = requireNotNull(
            nativeRosterPresentation(
                NativeRecord(
                    id = "team-1",
                    values = emptyMap(),
                    displayValues = mapOf("name" to "Shared home", "owner" to "admin"),
                    structuredValues = mapOf(
                        "members" to listValue(
                            objectValue("member" to "admin", "displayName" to "Alex"),
                        ),
                    ),
                ),
            ),
        )

        assertEquals("Shared home", roster.name)
        assertTrue(roster.people.single().owner)
    }

    private fun listValue(vararg values: NativeStructuredValue): NativeStructuredValue.ListValue =
        NativeStructuredValue.ListValue(values.toList())

    private fun objectValue(vararg values: Pair<String, String>): NativeStructuredValue.ObjectValue =
        NativeStructuredValue.ObjectValue(
            values.map { (key, value) -> NativeStructuredEntry(key, key, scalar(value)) },
        )

    private fun scalar(value: String) =
        NativeStructuredValue.Scalar(value, NativeStructuredScalarKind.string)
}
