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

internal const val marketingHomepageTalkUserId = "obiente"

internal val marketingHomepageSession = NextcloudSession(
    serverUrl = "https://capture.invalid",
    loginName = marketingHomepageTalkUserId,
    appPassword = "capture-only",
)

internal val marketingHomepageTalkRoom = TalkRoom(
    token = "homepage-conversation",
    displayName = "Nextcloud Native",
    lastMessage = "The updated brief is ready.",
    unreadMessages = 0,
)

internal val marketingHomepageTalkPage = TalkMessagePage(
    messages = listOf(
        marketingHomepageTalkMessage(
            id = 1,
            actorId = "mara",
            actorDisplayName = "Mara",
            message = "The product direction is ready for review.",
        ),
        marketingHomepageTalkMessage(
            id = 2,
            actorId = marketingHomepageTalkUserId,
            actorDisplayName = "Obiente",
            message = "Great. I will review it from the shared project folder.",
        ),
        marketingHomepageTalkMessage(
            id = 3,
            actorId = "mara",
            actorDisplayName = "Mara",
            message = "Perfect. I also added the light and dark interface notes.",
        ),
    ),
    olderCursor = null,
    hasMoreHistory = false,
)

internal val marketingHomepageFiles = listOf(
    marketingHomepageFile(
        path = "Photos",
        isDirectory = true,
        mimeType = "httpd/unix-directory",
        size = null,
        fileId = 5_000,
        hasPreview = false,
    ),
    marketingHomepageFile(
        path = "Projects",
        isDirectory = true,
        mimeType = "httpd/unix-directory",
        size = null,
        fileId = 5_001,
        hasPreview = false,
    ),
    marketingHomepageFile(
        path = "Shared",
        isDirectory = true,
        mimeType = "httpd/unix-directory",
        size = null,
        fileId = 5_002,
        hasPreview = false,
    ),
    marketingHomepageFile(
        path = "Field notes.jpg",
        isDirectory = false,
        mimeType = "image/jpeg",
        size = 4_812_736,
        fileId = 5_101,
        hasPreview = true,
    ),
    marketingHomepageFile(
        path = "Forest trail.jpg",
        isDirectory = false,
        mimeType = "image/jpeg",
        size = 6_238_208,
        fileId = 5_102,
        hasPreview = true,
    ),
    marketingHomepageFile(
        path = "North Sea.jpg",
        isDirectory = false,
        mimeType = "image/jpeg",
        size = 5_704_704,
        fileId = 5_103,
        hasPreview = true,
    ),
    marketingHomepageFile(
        path = "Product direction.md",
        isDirectory = false,
        mimeType = "text/markdown",
        size = 14_336,
        fileId = 5_201,
        hasPreview = false,
    ),
    marketingHomepageFile(
        path = "Release checklist.pdf",
        isDirectory = false,
        mimeType = "application/pdf",
        size = 862_208,
        fileId = 5_202,
        hasPreview = true,
    ),
)

internal val marketingHomepageCachedFileListing = NextcloudFileListing(
    files = marketingHomepageFiles,
    source = NextcloudFileListingSource.Cache,
)

internal val marketingHomepageFileListing = marketingHomepageCachedFileListing.copy(
    source = NextcloudFileListingSource.Network,
)

internal val marketingHomepageFileOfflineAvailability = mapOf(
    "Photos" to FileOfflineAvailability.Available,
    "Projects" to FileOfflineAvailability.Available,
    "Product direction.md" to FileOfflineAvailability.Available,
)

private fun marketingHomepageFile(
    path: String,
    isDirectory: Boolean,
    mimeType: String,
    size: Long?,
    fileId: Long,
    hasPreview: Boolean,
) = NextcloudFile(
    path = path,
    name = path,
    isDirectory = isDirectory,
    mimeType = mimeType,
    size = size,
    lastModified = "Fri, 31 Jul 2026 14:00:00 GMT",
    fileId = fileId,
    hasPreview = hasPreview,
    etag = "homepage-$fileId",
    permissions = "RGDNVW",
)

private fun marketingHomepageTalkMessage(
    id: Long,
    actorId: String,
    actorDisplayName: String,
    message: String,
) = TalkMessage(
    id = id,
    actorDisplayName = actorDisplayName,
    actorId = actorId,
    actorType = "users",
    message = message,
    timestamp = 1_775_000_000L + id,
    messageType = TalkMessageType.Comment,
    systemMessage = TalkSystemMessageType.Unknown,
    systemMessageName = null,
    parameters = emptyMap(),
    content = TalkMessageContent.Text(summary = message, markdown = false),
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
