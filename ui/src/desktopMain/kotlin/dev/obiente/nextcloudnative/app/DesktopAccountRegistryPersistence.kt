package dev.obiente.nextcloudnative.app

import java.util.prefs.Preferences

internal fun restoreDesktopAccountRegistry(
    preferences: Preferences,
    session: NextcloudSession,
    recordDiagnostic: (SupportDiagnosticEventDraft) -> Unit,
) {
    val restored = restoreNextcloudAccountRegistry(preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null), session)
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
            preferences.put(DESKTOP_ACCOUNT_REGISTRY_KEY, encodeNextcloudAccountRegistry(restored.registry))
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
}

internal fun persistDesktopAccountRegistry(preferences: Preferences, session: NextcloudSession) {
    preferences.put(
        DESKTOP_ACCOUNT_REGISTRY_KEY,
        encodeNextcloudAccountRegistry(singleAccountRegistry(session)),
    )
}

internal fun clearDesktopAccountRegistry(preferences: Preferences) {
    preferences.remove(DESKTOP_ACCOUNT_REGISTRY_KEY)
}

internal const val DESKTOP_ACCOUNT_REGISTRY_KEY = "account_registry_v1"
