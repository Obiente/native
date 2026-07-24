package dev.obiente.nextcloudnative.template

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BracedTemplateTest {
    @Test
    fun `scanner reads multiple tokens without regex`() {
        val scan = "/projects/{projectId}/bills/{billId}".scanBracedTemplate()

        assertFalse(scan.malformed)
        assertEquals(listOf("projectId", "billId"), scan.tokens.map { it.name })
    }

    @Test
    fun `scanner rejects unmatched and nested braces`() {
        assertTrue("/projects/{projectId".scanBracedTemplate().malformed)
        assertTrue("/projects/{{projectId}}".scanBracedTemplate().malformed)
        assertTrue("/projects/projectId}".scanBracedTemplate().malformed)
    }

    @Test
    fun `replacement preserves unknown tokens and ordinary text`() {
        val rendered = "{actor} shared {file}.".replaceBracedTemplateTokens { name, original ->
            if (name == "actor") "Ada" else original
        }

        assertEquals("Ada shared {file}.", rendered)
    }
}
