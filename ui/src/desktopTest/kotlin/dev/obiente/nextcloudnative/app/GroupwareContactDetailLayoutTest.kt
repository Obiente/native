package dev.obiente.nextcloudnative.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GroupwareContactDetailLayoutTest {
    @Test
    fun longContactBodyScrollsInLandscapeWithoutDisplacingActions() {
        checkScrollableDetails(780, 320, 1f)
    }

    @Test
    fun largeTextKeepsLongContactDetailsAndActionsReachable() {
        checkScrollableDetails(360, 640, 1.6f)
    }

    private fun checkScrollableDetails(width: Int, height: Int, fontScale: Float) {
        var edits = 0
        var deletes = 0
        val contact = GroupwareContact("/synthetic/contact.vcf", "synthetic-etag", "/synthetic/", "synthetic",
            "Synthetic contact", emails = listOf("fixture@example.invalid"), phones = listOf("+1 555 0100"),
            notes = List(60) { "Synthetic contact note line ${it + 1}." }.joinToString("\n"), rawVCard = "")
        nativeSceneTest(width, height, fontScale, content = {
            ContactDetailDialog(contact, canEdit = true, editLoading = false, error = "End of contact details",
                onDismiss = {}, onEdit = { edits++ }, onDelete = { deletes++ })
        }) {
            val scroll = assertNotNull(nodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue()?.let { max -> max > 0 } == true
            }, "Long contact details must offer vertical scrolling")
            val action = assertNotNull(scroll.config.getOrNull(SemanticsActions.ScrollBy)?.action)
            assertTrue(action.invoke(0f, 100_000f))
            settle()
            val end = assertNotNull(node("End of contact details"))
            assertTrue(end.boundsInRoot.height > 0f && end.boundsInRoot.top >= 0f && end.boundsInRoot.bottom <= height,
                "The final detail must be visible within the window after scrolling")
            for (label in listOf("Edit", "Delete")) {
                val button = assertNotNull(node(label))
                assertTrue(button.boundsInRoot.top >= 0f && button.boundsInRoot.bottom <= height,
                    "$label must remain within the window")
            }
            click("Edit")
            click("Delete")
            assertEquals(1, edits)
            assertEquals(1, deletes)
        }
    }
}