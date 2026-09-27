package dev.obiente.nextcloudnative.app

internal fun requireSupportedDesktopRegistryForSessionLoad(
    unsupportedVersion: Boolean,
    recordDiagnostic: (String, String, Throwable?) -> Unit,
) {
    if (!unsupportedVersion) return
    recordDiagnostic("ACCOUNT_REGISTRY_VERSION_UNSUPPORTED", "account-registry.restore", null)
    throw NextcloudSessionStorageVersionUnsupportedException()
}

internal fun desktopSessionWithoutLegacyRecovery(registryPresent: Boolean): NextcloudSession? {
    if (registryPresent) throw NextcloudSessionStorageMalformedException()
    return null
}
