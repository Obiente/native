package dev.obiente.nextcloudnative.app

/**
 * Synthetic, network-inert account used only by deterministic product captures.
 *
 * It deliberately contains no credentials, file paths, endpoint belonging to a real service,
 * device state, timestamps, or user media.
 */
data class MarketingDemoFixture(
    val displayName: String,
    val cloudName: String,
    val apps: List<NextcloudAppEntry>,
) {
    fun serverInfo(): NextcloudServerInfo = NextcloudServerInfo(
        serverUrl = "https://fixture.invalid",
        displayName = displayName,
        userId = "demo-user",
        version = "Developer preview",
        themeName = cloudName,
        themeColor = null,
        apps = apps,
    )
}

val nextcloudNativeMarketingFixture = MarketingDemoFixture(
    displayName = "Obiente",
    cloudName = "Nextcloud",
    apps = listOf(
        NextcloudAppEntry("files", "Files", null),
        NextcloudAppEntry("photos", "Photos", null),
        NextcloudAppEntry("memories", "Memories", null),
        NextcloudAppEntry("talk", "Talk", null),
        NextcloudAppEntry("notes", "Notes", null),
        NextcloudAppEntry("calendar", "Calendar", null),
        NextcloudAppEntry("contacts", "Contacts", null),
        NextcloudAppEntry("music", "Music", null),
        NextcloudAppEntry("deck", "Deck", null),
    ),
)
