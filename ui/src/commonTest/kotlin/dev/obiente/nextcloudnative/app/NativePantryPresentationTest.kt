package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.nativeui.runtime.formatNativeField
import kotlin.test.*

class NativePantryPresentationTest {
    private val field = FieldSpec("createdAt", "Created", FieldKind.integer, false, true, "int64")
    private val resource = ResourceSpec("houses", "Households", Confidence.high, listOf(field),
        listOf(Evidence(EvidenceSource.verifiedAppPackage, "Synthetic verified contract")))
    private val schema = NativeAppSchema("test", AppIdentity("pantry", "Pantry", "0.34.0"),
        Confidence.high, resources = listOf(resource))
    @Test fun reviewedEpochSecondsBecomeReadableWithoutChangingInputTypeOrValues() {
        val formattedField = schema.withNativePantryPresentation().resources.single().fields.single()
        assertEquals(FieldKind.integer, formattedField.kind)
        assertEquals(field.readOnly, formattedField.readOnly)
        assertEquals("2026-01-01 00:00 UTC", formatNativeField(formattedField, "1767225600").displayValue)
        assertEquals("1767225600000", formatNativeField(formattedField, "1767225600000").displayValue)
        assertEquals("invalid", formatNativeField(formattedField, "invalid").displayValue)
        assertEquals(schema.actions, schema.withNativePantryPresentation().actions)
    }
    @Test fun otherVersionsAppsResourcesAndUnverifiedFieldsRemainUntouched() {
        for (candidate in listOf(schema.copy(app = schema.app.copy(version = "0.35.0")),
            schema.copy(app = schema.app.copy(id = "other")),
            schema.copy(resources = listOf(resource.copy(id = "notes"))),
            schema.copy(resources = listOf(resource.copy(evidence = emptyList()))),
            schema.copy(resources = listOf(resource.copy(fields = listOf(field.copy(id = "reminderTime"))))))) {
            assertEquals(candidate, candidate.withNativePantryPresentation())
        }
    }
}
