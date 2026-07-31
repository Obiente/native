package dev.obiente.nextcloudnative.app

data class AppUpdateChannelOptionPresentation(
    val channel: AndroidUpdateChannel,
    val label: String,
    val description: String,
    val selected: Boolean,
    val enabled: Boolean,
    val availabilityLabel: String?,
)

data class AppUpdateChannelPresentation(
    val selectorVisible: Boolean,
    val selectorEnabled: Boolean,
    val selectedChannel: AndroidUpdateChannel,
    val options: List<AppUpdateChannelOptionPresentation>,
)

fun canSelectAppUpdateChannel(
    support: AppUpdateSupport,
    requested: AndroidUpdateChannel,
): Boolean =
    support.channel in setOf(
        AppDistributionChannel.DirectApk,
        AppDistributionChannel.DirectDesktopPackage,
    ) &&
        support.canCheckDirectUpdates &&
        requested.available

fun retainedAppUpdateCheckResult(
    previousChannel: AndroidUpdateChannel,
    selectedChannel: AndroidUpdateChannel,
    previousResult: AppUpdateCheckResult?,
): AppUpdateCheckResult? =
    previousResult.takeIf { previousChannel == selectedChannel }

fun appUpdateChannelPresentation(
    support: AppUpdateSupport,
    selectedChannel: AndroidUpdateChannel,
): AppUpdateChannelPresentation {
    val selectorVisible = support.channel in setOf(
        AppDistributionChannel.DirectApk,
        AppDistributionChannel.DirectDesktopPackage,
    )
    val selectorEnabled = selectorVisible && support.canCheckDirectUpdates
    return AppUpdateChannelPresentation(
        selectorVisible = selectorVisible,
        selectorEnabled = selectorEnabled,
        selectedChannel = selectedChannel,
        options = AndroidUpdateChannel.entries.map { channel ->
            AppUpdateChannelOptionPresentation(
                channel = channel,
                label = channel.label(),
                description = channel.description(),
                selected = channel == selectedChannel,
                enabled = selectorEnabled && channel.available,
                availabilityLabel = if (channel.available) null else "Coming later",
            )
        },
    )
}

private fun AndroidUpdateChannel.label(): String = when (this) {
    AndroidUpdateChannel.Alpha -> "Alpha"
    AndroidUpdateChannel.Nightly -> "Nightly"
    AndroidUpdateChannel.Beta -> "Beta"
    AndroidUpdateChannel.Stable -> "Stable"
}

private fun AndroidUpdateChannel.description(): String = when (this) {
    AndroidUpdateChannel.Alpha ->
        "Signed prerelease builds for broader testing. Updates arrive with reviewed alpha releases."
    AndroidUpdateChannel.Nightly ->
        "The latest signed build from main. It changes frequently and may be less stable."
    AndroidUpdateChannel.Beta ->
        "Feature-complete testing builds with fewer changes between updates."
    AndroidUpdateChannel.Stable ->
        "Production-ready releases after prerelease testing is complete."
}
