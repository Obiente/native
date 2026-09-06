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
            persistDesktopAccountRegistry(preferences, prepareDesktopAccountRegistry(restored.registry))
        }.onFailure { failure ->
            recordDiagnostic(
                SupportDiagnosticEventDraft(
                    severity = SupportDiagnosticSeverity.Warning,
                    component = SupportDiagnosticComponent.Authentication,
                    operation = "account-registry.migrate",
                    outcome = "failed",
                    code = "ACCOUNT_REGISTRY_MIGRATION_FAILED",
                    exception = failure.toNonSecretSupportDiagnosticExceptionDraft(),
                ),
            )
        }
    }
}

internal fun persistDesktopAccountRegistry(preferences: Preferences, session: NextcloudSession) {
    persistDesktopAccountRegistry(preferences, prepareDesktopAccountRegistry(singleAccountRegistry(session)))
}

internal fun prepareDesktopAccountRegistry(session: NextcloudSession): String =
    prepareDesktopAccountRegistry(singleAccountRegistry(session))

internal fun prepareDesktopAccountRegistry(registry: NextcloudAccountRegistry): String =
    encodeNextcloudAccountRegistry(registry).also { encoded ->
        require(encoded.length <= Preferences.MAX_VALUE_LENGTH) {
            "The account registry exceeds the desktop preference value limit."
        }
    }

internal fun persistDesktopAccountRegistry(preferences: Preferences, encodedRegistry: String) {
    require(encodedRegistry.length <= Preferences.MAX_VALUE_LENGTH)
    preferences.put(DESKTOP_ACCOUNT_REGISTRY_KEY, encodedRegistry)
}

internal fun clearDesktopAccountRegistry(preferences: Preferences) {
    preferences.remove(DESKTOP_ACCOUNT_REGISTRY_KEY)
}

internal const val DESKTOP_ACCOUNT_REGISTRY_KEY = "account_registry_v1"
