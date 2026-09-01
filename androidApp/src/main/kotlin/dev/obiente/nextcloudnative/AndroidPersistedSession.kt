package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.SupportDiagnosticComponent
import dev.obiente.nextcloudnative.app.SupportDiagnosticEventDraft
import dev.obiente.nextcloudnative.app.SupportDiagnosticSeverity
import dev.obiente.nextcloudnative.app.encodeNextcloudAccountRegistry
import dev.obiente.nextcloudnative.app.restoreNextcloudAccountRegistry
import dev.obiente.nextcloudnative.app.singleAccountRegistry
import org.json.JSONObject

internal fun restoreAndroidPersistedSession(
    encoded: String,
    persistMigrated: (String) -> Unit,
    recordDiagnostic: (SupportDiagnosticEventDraft) -> Unit,
): NextcloudSession {
    val json = JSONObject(encoded)
    val session = NextcloudSession(
        serverUrl = json.getString("serverUrl"),
        loginName = json.getString("loginName"),
        appPassword = json.getString("appPassword"),
    )
    val encodedRegistry = when (val registry = json.opt(KEY_ACCOUNT_REGISTRY)) {
        null -> null
        is String -> registry
        else -> ""
    }
    val restored = restoreNextcloudAccountRegistry(encodedRegistry, session)
    restored.recoveryReason?.let { reason ->
        recordDiagnostic(
            SupportDiagnosticEventDraft(
                severity = SupportDiagnosticSeverity.Warning,
                component = SupportDiagnosticComponent.Authentication,
                operation = "account-registry.restore",
                outcome = "recovered",
                code = reason.diagnosticCode,
            ),
        )
    }
    if (restored.needsPersistence) {
        runCatching {
            persistMigrated(
                json.put(KEY_ACCOUNT_REGISTRY, encodeNextcloudAccountRegistry(restored.registry)).toString(),
            )
        }.onFailure {
            recordDiagnostic(
                SupportDiagnosticEventDraft(
                    severity = SupportDiagnosticSeverity.Warning,
                    component = SupportDiagnosticComponent.Authentication,
                    operation = "account-registry.migrate",
                    outcome = "failed",
                    code = "ACCOUNT_REGISTRY_MIGRATION_FAILED",
                ),
            )
        }
    }
    return session
}

internal fun encodeAndroidPersistedSession(session: NextcloudSession): String = JSONObject()
    .put("serverUrl", session.serverUrl)
    .put("loginName", session.loginName)
    .put("appPassword", session.appPassword)
    .put(KEY_ACCOUNT_REGISTRY, encodeNextcloudAccountRegistry(singleAccountRegistry(session)))
    .toString()

private const val KEY_ACCOUNT_REGISTRY = "account_registry_v1"
