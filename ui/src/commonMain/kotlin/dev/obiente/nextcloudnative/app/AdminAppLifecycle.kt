package dev.obiente.nextcloudnative.app

/**
 * Executes one reviewed app lifecycle plan with the password-confirmed transport.
 *
 * The plan is built from the verified catalog, exact app ID, and observed version, so a catalog
 * that changed after confirmation is rejected before anything is sent. Success is confirmed by a
 * fresh catalog read that shows the requested state.
 */
internal suspend fun runNativeAppLifecycle(
    transport: AccountPasswordConfirmedTransport,
    session: NextcloudSession,
    catalog: NativeAppCatalog,
    app: NativeManagedApp,
    action: NativeAppLifecycleAction,
    secret: AccountPasswordConfirmationSecret,
    readCatalog: suspend () -> NativeAppCatalogResult,
): ConfirmedWriteOutcome {
    val plan = try {
        buildNativeAppMutationRequest(
            catalog = catalog,
            app = app,
            action = action,
            authorization = nativeAppRequiredAuthorization(action),
            approval = NativeAppMutationApproval(
                appId = app.id,
                action = action,
                observedVersion = app.installedVersion,
                confirmationChallenge = nativeAppConfirmationChallenge(app, action),
                // The password dialog for uninstall is the separate destructive confirmation.
                destructiveImpactAccepted = action == NativeAppLifecycleAction.Uninstall,
            ),
        )
    } catch (_: IllegalArgumentException) {
        secret.clear()
        return ConfirmedWriteOutcome.Rejected(ConfirmedWriteRejection.Invalid)
    }
    return try {
        runPasswordConfirmedWrite(transport, session, plan.request, secret) {
            val fresh = (readCatalog() as? NativeAppCatalogResult.Available)?.catalog ?: return@runPasswordConfirmedWrite false
            nativeAppLifecycleApplied(app, action, fresh.apps.firstOrNull { it.id == app.id })
        }
    } finally {
        secret.clear()
    }
}

/**
 * Strict routes (enable, install, update, uninstall) read the real password from the request;
 * disable needs a recent confirmation, which a password login on the same request provides.
 */
internal fun nativeAppRequiredAuthorization(action: NativeAppLifecycleAction): NativeAdminAuthorization = when (action) {
    NativeAppLifecycleAction.Disable -> NativeAdminAuthorization.RecentlyPasswordConfirmed
    else -> NativeAdminAuthorization.AccountPasswordOnRequest
}

internal fun nativeAppLifecycleApplied(
    before: NativeManagedApp,
    action: NativeAppLifecycleAction,
    after: NativeManagedApp?,
): Boolean = when (action) {
    NativeAppLifecycleAction.InstallAndEnable, NativeAppLifecycleAction.Enable -> after?.installed == true && after.enabled
    NativeAppLifecycleAction.Disable -> after != null && !after.enabled
    NativeAppLifecycleAction.Update -> after != null && after.installed &&
        (after.installedVersion == before.availableVersion || (!after.updateAvailable && after.installedVersion != before.installedVersion))
    // An uninstalled app either leaves the catalog or stays listed as available.
    NativeAppLifecycleAction.Uninstall -> after == null || !after.installed
}

internal fun NativeAppLifecycleAction.lifecycleDescription(): String = when (this) {
    NativeAppLifecycleAction.InstallAndEnable -> "This downloads server-side code and enables the app for users."
    NativeAppLifecycleAction.Enable -> "This activates the app and may add navigation, jobs, and integrations for users."
    NativeAppLifecycleAction.Disable -> "This makes the app and its integrations unavailable until an administrator enables it again."
    NativeAppLifecycleAction.Update -> "The server may enter maintenance mode while the app package is updated. Keep this window open."
    NativeAppLifecycleAction.Uninstall -> "This removes the app package. App data retention depends on the app and is not guaranteed."
}
