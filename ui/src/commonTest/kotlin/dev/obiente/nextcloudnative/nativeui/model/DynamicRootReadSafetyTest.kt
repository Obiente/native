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
        assertTrue(
            readAction("api-general-index", "/init")
                .copy(resourceId = "overview")
                .hasPositiveRootReadEvidence(),
        )
    }

    @Test
    fun `trusted catalog provenance does not make command GETs safe automatic roots`() {
        listOf(ProvenanceKind.verifiedAdapter, ProvenanceKind.verifiedAppPackage).forEach { kind ->
            val provenance = listOf(
                Provenance(
                    kind = kind,
                    source = "trusted catalog",
                    detail = "The catalog authenticates the contract source, not GET read semantics.",
                ),
            )
            assertFalse(readAction("start", "/start").copy(provenance = provenance).hasPositiveRootReadEvidence())
            assertFalse(readAction("scan", "/scan").copy(provenance = provenance).hasPositiveRootReadEvidence())
        }
    }

    @Test
    fun `successful observation remains affirmative read evidence`() {
        val provenance = listOf(
            Provenance(
                kind = ProvenanceKind.successfulReadObservation,
                source = "observed response",
                detail = "A successful read was observed.",
            ),
        )

        assertTrue(readAction("fetch-widget", "/widget").copy(provenance = provenance).hasPositiveRootReadEvidence())
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
