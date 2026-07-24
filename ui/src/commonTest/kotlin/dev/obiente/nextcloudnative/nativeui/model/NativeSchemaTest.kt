package dev.obiente.nextcloudnative.nativeui.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class NativeSchemaTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun rustSchemaContractDecodesWithoutPlatformKnowledge() {
        val schema = json.decodeFromString<NativeAppSchema>(
            """
            {
              "schemaVersion": "0.1",
              "app": { "id": "memories", "name": "Memories", "version": "7.0" },
              "confidence": "high",
              "resources": [
                {
                  "id": "media",
                  "name": "Media",
                  "confidence": "high",
                  "fields": [],
                  "evidence": []
                }
              ],
              "views": [
                {
                  "id": "media.media",
                  "title": "Media",
                  "resourceId": "media",
                  "component": "mediaGrid",
                  "sourceActionId": "memories-timeline-list",
                  "confidence": "medium",
                  "evidence": []
                }
              ],
              "actions": [],
              "warnings": []
            }
            """.trimIndent(),
        )

        assertEquals("memories", schema.app.id)
        assertEquals(NativeComponent.mediaGrid, schema.views.single().component)
        assertTrue(schema.resource("media") != null)
    }
}
