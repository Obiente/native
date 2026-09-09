package dev.obiente.nextcloudnative.app

import java.util.prefs.Preferences

internal fun restoreDesktopAccountRegistry(
    preferences: Preferences,
    session: NextcloudSession,
    recordDiagnostic: (SupportDiagnosticEventDraft) -> Unit,
) {
    val registryStore = DesktopAccountRegistryPreferenceStore(preferences)
    val restored = restoreNextcloudAccountRegistry(registryStore.read(), session)
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
    encodeNextcloudAccountRegistry(registry)

internal fun persistDesktopAccountRegistry(preferences: Preferences, encodedRegistry: String) {
    DesktopAccountRegistryPreferenceStore(preferences).write(encodedRegistry)
}

internal fun clearDesktopAccountRegistry(preferences: Preferences) {
    DesktopAccountRegistryPreferenceStore(preferences).write(null)
}

internal const val DESKTOP_ACCOUNT_REGISTRY_KEY = "account_registry_v1"
