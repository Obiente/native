package dev.obiente.nextcloudnative.app.design

import kotlin.test.Test
import kotlin.test.assertEquals

class NextcloudChoiceFieldTest {
    @Test
    fun searchMatchesLabelsWireIdsAndCallerAliasesWithoutChangingOrder() {
        val choices = listOf(
            NextcloudChoiceOption("d:1", "Every day", searchTerms = listOf("Daily")),
            NextcloudChoiceOption("w:1", "Every week", enabled = false, searchTerms = listOf("Weekly")),
            NextcloudChoiceOption("m:1", "Every month"),
        )
        assertEquals(choices, nextcloudChoiceOptionsMatchingQuery(choices, "  "))
        assertEquals(choices, nextcloudChoiceOptionsMatchingQuery(choices, "EVERY"))
        assertEquals(listOf(choices[1]), nextcloudChoiceOptionsMatchingQuery(choices, " weekly "))
        assertEquals(listOf(choices[1]), nextcloudChoiceOptionsMatchingQuery(choices, "w:1"))
        assertEquals(emptyList(), nextcloudChoiceOptionsMatchingQuery(choices, "missing"))
    }
}
