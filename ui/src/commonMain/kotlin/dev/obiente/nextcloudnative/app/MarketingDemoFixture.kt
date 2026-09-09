package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.app.design.NextcloudDesktopIdentity
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopSidebarApp

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
        version = "31.0.8",
        themeName = cloudName,
        themeColor = null,
        apps = apps,
    )
}

val nextcloudNativeMarketingFixture = MarketingDemoFixture(
    displayName = "Mara Lind",
    cloudName = "cloud.example.com",
    apps = listOf(
        NextcloudAppEntry("dashboard", "Dashboard", null),
        NextcloudAppEntry("files", "Files", null),
        NextcloudAppEntry("photos", "Photos", null),
        NextcloudAppEntry("memories", "Memories", null),
        NextcloudAppEntry("talk", "Talk", null),
        NextcloudAppEntry("mail", "Mail", null),
        NextcloudAppEntry("calendar", "Calendar", null),
        NextcloudAppEntry("contacts", "Contacts", null),
        NextcloudAppEntry("notes", "Notes", null),
        NextcloudAppEntry("deck", "Deck", null),
        NextcloudAppEntry("tasks", "Tasks", null),
        NextcloudAppEntry("tables", "Tables", null),
        NextcloudAppEntry("forms", "Forms", null),
        NextcloudAppEntry("collectives", "Collectives", null),
        NextcloudAppEntry("cookbook", "Cookbook", null),
        NextcloudAppEntry("music", "Music", null),
        NextcloudAppEntry("maps", "Maps", null),
        NextcloudAppEntry("news", "News", null),
        NextcloudAppEntry("bookmarks", "Bookmarks", null),
        NextcloudAppEntry("passwords", "Passwords", null),
        NextcloudAppEntry("cospend", "Cospend", null),
        NextcloudAppEntry("polls", "Polls", null),
        NextcloudAppEntry("activity", "Activity", null),
        NextcloudAppEntry("user_status", "Status", null),
    ),
)

internal fun marketingDesktopIdentity(
    fixture: MarketingDemoFixture = nextcloudNativeMarketingFixture,
    avatar: androidx.compose.ui.graphics.ImageBitmap? = null,
) = NextcloudDesktopIdentity(
    accountScopeKey = "marketing-fixture",
    displayName = fixture.displayName,
    cloudName = fixture.cloudName,
    avatar = avatar,
    connectionLabel = "Connected",
    serverVersion = "31.0.8",
    availableApps = fixture.apps.map { NextcloudDesktopSidebarApp(it.id, it.name) },
    shortcuts = listOf(
        NextcloudDesktopSidebarApp("files", "Files"),
        NextcloudDesktopSidebarApp("photos", "Photos", "12"),
        NextcloudDesktopSidebarApp("talk", "Talk", "5"),
        NextcloudDesktopSidebarApp("calendar", "Calendar", "3"),
    ),
    recentApp = NextcloudDesktopSidebarApp("deck", "Deck"),
    syncSummary = "4 folders syncing",
    storageLabel = "34.2 GB of 100 GB used",
    storageProgress = .342f,
)

internal const val marketingHomepageTalkUserId = "obiente"

internal val marketingHomepageSession = NextcloudSession(
    serverUrl = "https://capture.invalid",
    loginName = marketingHomepageTalkUserId,
    appPassword = "capture-only",
)

internal val marketingHomepageTalkRoom = TalkRoom(
    token = "homepage-conversation",
    displayName = "nati.ve",
    lastMessage = "The updated brief is ready.",
    unreadMessages = 5,
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
        marketingHomepageTalkMessage(
            id = 4,
            actorId = "kai",
            actorDisplayName = "Kai",
            message = "Folder sync QA is green on Linux and Windows.",
        ),
        marketingHomepageTalkMessage(
            id = 5,
            actorId = marketingHomepageTalkUserId,
            actorDisplayName = "Mara Lind",
            message = "I will review the active transfers and publish the visual QA set.",
        ),
        marketingHomepageTalkMessage(
            id = 6,
            actorId = "mara",
            actorDisplayName = "Mara",
            message = "Great. The design review is at 14:30 in the product room.",
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
        favorite = true,
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
        path = "Clients",
        isDirectory = true,
        mimeType = "httpd/unix-directory",
        size = null,
        fileId = 5_003,
        hasPreview = false,
        favorite = true,
    ),
    marketingHomepageFile(
        path = "Design system",
        isDirectory = true,
        mimeType = "httpd/unix-directory",
        size = null,
        fileId = 5_004,
        hasPreview = false,
    ),
    marketingHomepageFile(
        path = "Archive 2025",
        isDirectory = true,
        mimeType = "httpd/unix-directory",
        size = null,
        fileId = 5_005,
        hasPreview = false,
    ),
    marketingHomepageFile(
        path = "Research",
        isDirectory = true,
        mimeType = "httpd/unix-directory",
        size = null,
        fileId = 5_006,
        hasPreview = false,
        favorite = true,
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
        favorite = true,
        unreadComments = 3,
    ),
    marketingHomepageFile(
        path = "Release checklist.pdf",
        isDirectory = false,
        mimeType = "application/pdf",
        size = 862_208,
        fileId = 5_202,
        hasPreview = true,
    ),
    marketingHomepageFile(
        path = "Q3 roadmap.xlsx",
        isDirectory = false,
        mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        size = 1_248_960,
        fileId = 5_203,
        hasPreview = false,
    ),
    marketingHomepageFile(
        path = "Brand presentation.pptx",
        isDirectory = false,
        mimeType = "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        size = 8_744_960,
        fileId = 5_204,
        hasPreview = true,
    ),
    marketingHomepageFile(
        path = "Launch interview.mp4",
        isDirectory = false,
        mimeType = "video/mp4",
        size = 184_549_376,
        fileId = 5_205,
        hasPreview = true,
    ),
    marketingHomepageFile(
        path = "Team offsite.ogg",
        isDirectory = false,
        mimeType = "audio/ogg",
        size = 23_592_960,
        fileId = 5_206,
        hasPreview = false,
    ),
    marketingHomepageFile(
        path = "Invoices 2026.zip",
        isDirectory = false,
        mimeType = "application/zip",
        size = 42_991_616,
        fileId = 5_207,
        hasPreview = false,
    ),
    marketingHomepageFile(
        path = "Campaign brief.docx",
        isDirectory = false,
        mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        size = 384_000,
        fileId = 5_208,
        hasPreview = true,
        unreadComments = 2,
    ),
    marketingHomepageFile(
        path = "Customer research.pdf",
        isDirectory = false,
        mimeType = "application/pdf",
        size = 3_821_568,
        fileId = 5_209,
        hasPreview = true,
        favorite = true,
    ),
    marketingHomepageFile(
        path = "Meeting notes.md",
        isDirectory = false,
        mimeType = "text/markdown",
        size = 28_672,
        fileId = 5_210,
        hasPreview = false,
    ),
    marketingHomepageFile(
        path = "Brand assets.zip",
        isDirectory = false,
        mimeType = "application/zip",
        size = 96_468_992,
        fileId = 5_211,
        hasPreview = false,
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
    favorite: Boolean = false,
    unreadComments: Int = 0,
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
    favorite = favorite,
    ownerId = "morgan",
    ownerDisplayName = "Morgan Lee",
    unreadComments = unreadComments,
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
