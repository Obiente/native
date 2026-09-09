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
    !appUpdateChannelSelectionLocked &&
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

internal fun unhandledAppUpdateReviewRequest(
    requested: Long,
    handled: Long,
): Long? = requested.takeIf { it > 0L && it > handled }

fun appUpdateChannelPresentation(
    support: AppUpdateSupport,
    selectedChannel: AndroidUpdateChannel,
): AppUpdateChannelPresentation {
    val selectorVisible = support.channel in setOf(
        AppDistributionChannel.DirectApk,
        AppDistributionChannel.DirectDesktopPackage,
    )
    val selectorEnabled = selectorVisible &&
        support.canCheckDirectUpdates &&
        !appUpdateChannelSelectionLocked
    val effectiveSelectedChannel = if (appUpdateChannelSelectionLocked) {
        enforcedAppUpdateChannel
    } else {
        selectedChannel
    }
    val presentedChannels = if (appUpdateChannelSelectionLocked) {
        listOf(enforcedAppUpdateChannel)
    } else {
        AndroidUpdateChannel.entries
    }
    return AppUpdateChannelPresentation(
        selectorVisible = selectorVisible,
        selectorEnabled = selectorEnabled,
        selectedChannel = effectiveSelectedChannel,
        options = presentedChannels.map { channel ->
            AppUpdateChannelOptionPresentation(
                channel = channel,
                label = channel.label(),
                description = channel.description(support.channel),
                selected = channel == effectiveSelectedChannel,
                enabled = selectorEnabled && channel.available,
                availabilityLabel = when {
                    appUpdateChannelSelectionLocked -> "Locked for now"
                    channel.available -> null
                    else -> "Coming later"
                },
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

private fun AndroidUpdateChannel.description(distribution: AppDistributionChannel): String = when (this) {
    AndroidUpdateChannel.Alpha ->
        if (distribution == AppDistributionChannel.DirectApk) {
            "Signed prerelease APKs for broader testing. Updates arrive with reviewed alpha releases."
        } else {
            "Prerelease builds for broader testing. Updates arrive with reviewed alpha releases."
        }
    AndroidUpdateChannel.Nightly ->
        if (distribution == AppDistributionChannel.DirectApk) {
            "The latest signed APK from main. It changes frequently and may be less stable."
        } else {
            "The latest build from main. It changes frequently and may be less stable."
        }
    AndroidUpdateChannel.Beta ->
        "Feature-complete testing builds with fewer changes between updates."
    AndroidUpdateChannel.Stable ->
        "Production-ready releases after prerelease testing is complete."
}
