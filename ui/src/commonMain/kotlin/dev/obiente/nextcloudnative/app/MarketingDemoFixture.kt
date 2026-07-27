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

data class MarketingFileShareFixture(
    val file: NextcloudFile,
    val capabilities: NextcloudFileSharingCapabilities,
    val userResults: List<FileShareRecipient>,
    val groupResults: List<FileShareRecipient>,
    val existingUserShare: NextcloudFileShare,
    val existingGroupShare: NextcloudFileShare,
)

val nextcloudNativeMarketingFileShareFixture = MarketingFileShareFixture(
    file = NextcloudFile(
        path = "Projects/Product brief.pdf",
        name = "Product brief.pdf",
        isDirectory = false,
        mimeType = "application/pdf",
        size = 248_320,
        lastModified = null,
        fileId = 4_242,
        hasPreview = true,
        etag = "fixture-product-brief",
    ),
    capabilities = NextcloudFileSharingCapabilities(
        apiEnabled = true,
        userShares = true,
        groupShares = true,
        userExpirationSupported = true,
        groupExpirationSupported = true,
        defaultPermissions = 1,
    ),
    userResults = listOf(
        FileShareRecipient(
            id = "demo-account",
            displayName = "Demo account",
            target = FileShareTarget.User,
            exact = true,
        ),
        FileShareRecipient(
            id = "example-collaborator",
            displayName = "Example collaborator",
            target = FileShareTarget.User,
        ),
    ),
    groupResults = listOf(
        FileShareRecipient(
            id = "design-team",
            displayName = "Design team",
            target = FileShareTarget.Group,
            exact = true,
        ),
        FileShareRecipient(
            id = "development-team",
            displayName = "Development team",
            target = FileShareTarget.Group,
        ),
    ),
    existingUserShare = NextcloudFileShare(
        id = "fixture-share-user",
        url = null,
        token = null,
        shareType = FileShareTarget.User.wireValue,
        shareWith = "demo-collaborator",
        displayName = "Demo collaborator",
        permissions = 3,
    ),
    existingGroupShare = NextcloudFileShare(
        id = "fixture-share-group",
        url = null,
        token = null,
        shareType = FileShareTarget.Group.wireValue,
        shareWith = "project-team",
        displayName = "Project team",
        permissions = 1,
    ),
)
