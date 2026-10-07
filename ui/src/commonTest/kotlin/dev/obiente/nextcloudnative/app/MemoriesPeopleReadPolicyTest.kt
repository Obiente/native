package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoriesPeopleReadPolicyTest {
    private val unavailable = """{"message":"Recognize app not enabled or not the required version."}"""

    @Test
    fun verifiedRecognizeListPreconditionExplainsSetup() {
        val failure = assertFailsWith<MemoriesPeopleSetupRequired> {
            requireMemoriesPeopleListSuccess("recognize", 412, unavailable)
        }
        assertTrue(failure.message.orEmpty().contains("server administrator"))
        assertTrue(failure.message.orEmpty().contains("face recognition"))
    }

    @Test
    fun otherPreconditionsAndMalformedBodiesNeverClaimSetup() {
        for (body in listOf(
            """{"message":"User not logged in"}""", "{}", "[]", "not-json",
            """{"message":"Recognize app not enabled or not the required version. private suffix"}""",
            " ".repeat(4_096) + unavailable,
        )) {
            val failure = assertFailsWith<IllegalStateException> {
                requireMemoriesPeopleListSuccess("recognize", 412, body)
            }
            assertFalse(failure is MemoriesPeopleSetupRequired)
            assertEquals("Loading people from Memories failed (HTTP 412).", failure.message)
        }
        for ((backend, status) in listOf("facerecognition" to 412, "recognize" to 500)) {
            val failure = assertFailsWith<IllegalStateException> {
                requireMemoriesPeopleListSuccess(backend, status, unavailable)
            }
            assertFalse(failure is MemoriesPeopleSetupRequired)
        }
    }

    @Test
    fun successfulReadsRemainUnchanged() {
        requireMemoriesPeopleListSuccess("recognize", 200, "[]")
    }
}
