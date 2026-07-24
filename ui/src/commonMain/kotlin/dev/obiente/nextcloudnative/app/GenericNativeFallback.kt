package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.CompilerWarning
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.Evidence
import dev.obiente.nextcloudnative.nativeui.model.EvidenceSource
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.NativeScreenState

data class GenericNativeFallback(
    val schema: NativeAppSchema,
    val view: ViewSpec,
    val state: NativeScreenState.Ready,
)

/**
 * Converts authenticated app-navigation metadata into the lowest-risk native schema.
 *
 * No API action is synthesized. A later OpenAPI or verified adapter discovery can replace this
 * schema without changing the renderer.
 */
fun buildGenericNativeFallback(
    app: NextcloudAppEntry,
    nativeFamily: String,
): GenericNativeFallback {
    val fields = buildList {
        add(readOnlyStringField("id", "App ID"))
        add(readOnlyStringField("name", "Name"))
        app.href?.takeIf(String::isNotBlank)?.let { add(readOnlyStringField("route", "Advertised route")) }
        add(readOnlyStringField("family", "Native family"))
        add(readOnlyStringField("status", "Native mode"))
    }
    val resource = ResourceSpec(
        id = GENERIC_APP_RESOURCE_ID,
        name = "App metadata",
        confidence = Confidence.low,
        fields = fields,
        evidence = listOf(
            Evidence(
                source = EvidenceSource.appMetadata,
                detail = "Installed app identity and navigation metadata",
            ),
        ),
    )
    val view = ViewSpec(
        id = "$GENERIC_APP_RESOURCE_ID.detail",
        title = app.name,
        resourceId = GENERIC_APP_RESOURCE_ID,
        component = NativeComponent.detail,
        sourceActionId = "",
        confidence = Confidence.low,
        evidence = listOf(
            Evidence(
                source = EvidenceSource.localInference,
                detail = "No typed API is available, so only read-only app metadata is rendered",
            ),
        ),
    )
    val schema = NativeAppSchema(
        schemaVersion = "0.1",
        app = AppIdentity(
            id = app.id,
            name = app.name,
            version = "unreported",
        ),
        confidence = Confidence.low,
        resources = listOf(resource),
        views = listOf(view),
        actions = emptyList(),
        warnings = listOf(
            CompilerWarning(
                code = "metadata-only",
                message = "No typed API was advertised; native writes and inferred endpoint access are disabled",
            ),
        ),
    )
    val record = NativeRecord(
        id = app.id,
        values = buildMap {
            put("id", app.id)
            put("name", app.name)
            app.href?.takeIf(String::isNotBlank)?.let { put("route", it) }
            put("family", nativeFamily)
            put("status", "Read-only metadata fallback")
        },
    )
    return GenericNativeFallback(
        schema = schema,
        view = view,
        state = NativeScreenState.Ready(records = listOf(record)),
    )
}

private fun readOnlyStringField(id: String, label: String): FieldSpec = FieldSpec(
    id = id,
    label = label,
    kind = FieldKind.string,
    required = false,
    readOnly = true,
)

private const val GENERIC_APP_RESOURCE_ID = "app-metadata"
