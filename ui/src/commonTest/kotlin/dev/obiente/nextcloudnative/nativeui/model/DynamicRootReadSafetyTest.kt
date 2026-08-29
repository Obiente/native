package dev.obiente.nextcloudnative.nativeui.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicRootReadSafetyTest {
    @Test
    fun `unproven parameterless command GETs are not safe automatic roots`() {
        assertFalse(readAction("logout", "/logout").hasPositiveRootReadEvidence())
        assertFalse(readAction("start", "/start").hasPositiveRootReadEvidence())
        assertTrue(readAction("get-status", "/status").hasPositiveRootReadEvidence())
    }

    private fun readAction(id: String, path: String) = DynamicAction(
        id = id,
        label = id,
        resourceId = "singleton",
        intent = ActionIntent.read,
        risk = ActionRisk.readOnly,
        requiresConfirmation = false,
        binding = DynamicHttpBinding(HttpMethod.GET, path),
        confidence = Confidence.high,
        provenance = listOf(
            Provenance(
                kind = ProvenanceKind.advertisedOpenApi,
                source = "synthetic OpenAPI",
                detail = "A declared GET alone is not positive read-safety evidence.",
            ),
        ),
    )
}
