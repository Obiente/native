package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.*

/** Pantry 0.34.0 House/Checklist/ListItem timestamps are PHP time() epoch seconds. */
internal fun NativeAppSchema.withNativePantryPresentation(): NativeAppSchema {
    if (app.id != "pantry" || app.version != "0.34.0") return this
    return copy(resources = resources.map { resource ->
        if (resource.id !in setOf("houses", "lists", "items") ||
            resource.confidence !in setOf(Confidence.high, Confidence.verified) ||
            resource.evidence.none { it.source in setOf(EvidenceSource.verifiedAppPackage,
                EvidenceSource.openApi, EvidenceSource.verifiedAdapter) }) resource else resource.copy(fields = resource.fields.map { field ->
            if (field.id in setOf("createdAt", "updatedAt") && field.kind == FieldKind.integer &&
                field.format in setOf(null, "int64")) field.copy(format = "unix-seconds") else field
        })
    })
}
