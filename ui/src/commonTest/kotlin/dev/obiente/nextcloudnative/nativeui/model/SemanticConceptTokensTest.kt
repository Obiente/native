package dev.obiente.nextcloudnative.nativeui.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SemanticConceptTokensTest {
    @Test
    fun `taxonomy concepts recognize singular plural and identifier boundaries`() {
        listOf(
            "category",
            "categories",
            "tag",
            "tags",
            "keyword",
            "keywords",
            "label",
            "labels",
            "recipeCategories",
            "/api/v1/recipe-keywords/{keyword}",
        ).forEach { identifier ->
            assertTrue(
                identifier.hasAnySemanticConcept(SEMANTIC_TAXONOMY_CONCEPTS),
                identifier,
            )
        }
    }

    @Test
    fun `taxonomy concepts reject substrings inside unrelated words`() {
        listOf("staging", "tagline", "categorically", "/api/v1/staging/{id}").forEach { identifier ->
            assertFalse(
                identifier.hasAnySemanticConcept(SEMANTIC_TAXONOMY_CONCEPTS),
                identifier,
            )
        }
    }
}
