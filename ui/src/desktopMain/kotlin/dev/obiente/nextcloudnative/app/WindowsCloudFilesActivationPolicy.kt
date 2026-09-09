package dev.obiente.nextcloudnative.app

internal fun windowsCloudFilesAutomaticActivationAllowed(
    windowsDesktop: Boolean,
    previousFailure: String?,
): Boolean = !windowsDesktop || previousFailure == null
