package dev.obiente.nextcloudnative.app

internal enum class AppWorkspaceCategory(val title: String) {
    All("All apps"),
    FilesAndMedia("Files & media"),
    Communication("Communication"),
    Planning("Planning"),
    Productivity("Productivity"),
    More("More"),
}

internal data class AppWorkspaceEntry(
    val app: NextcloudAppEntry,
    val category: AppWorkspaceCategory,
    val description: String,
    val nativeWorkspace: Boolean,
    val pinned: Boolean,
    val recent: Boolean,
)

internal data class AppWorkspacePresentation(
    val entries: List<AppWorkspaceEntry>,
    val recentEntries: List<AppWorkspaceEntry>,
    val pinnedEntries: List<AppWorkspaceEntry>,
    val visibleCategories: List<AppWorkspaceCategory>,
    val totalCount: Int,
    val nativeWorkspaceCount: Int,
)

internal fun buildAppWorkspacePresentation(
    apps: List<NextcloudAppEntry>,
    lastOpenedAppId: String?,
    query: String = "",
    category: AppWorkspaceCategory = AppWorkspaceCategory.All,
): AppWorkspacePresentation {
    val installed = apps
        .asSequence()
        .filterNot { it.id == "dashboard" }
        .distinctBy(NextcloudAppEntry::id)
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        .toList()
    val installedIds = installed.mapTo(linkedSetOf(), NextcloudAppEntry::id)
    val pinnedIds = APP_WORKSPACE_PINNED_IDS.filterTo(linkedSetOf()) { it in installedIds }
    val recentIds = buildList {
        lastOpenedAppId?.takeIf(installedIds::contains)?.let(::add)
        APP_WORKSPACE_RECENT_FALLBACK_IDS.forEach { id ->
            if (id in installedIds && id !in this) add(id)
        }
    }.take(APP_WORKSPACE_RECENT_LIMIT).toSet()
    val allEntries = installed.map { app ->
        AppWorkspaceEntry(
            app = app,
            category = appWorkspaceCategory(app.id),
            description = appWorkspaceDescription(app.id),
            nativeWorkspace = app.id in APP_WORKSPACE_NATIVE_IDS,
            pinned = app.id in pinnedIds,
            recent = app.id in recentIds,
        )
    }
    val normalizedQuery = query.trim()
    val filtered = allEntries.filter { entry ->
        (category == AppWorkspaceCategory.All || entry.category == category) &&
            (
                normalizedQuery.isEmpty() ||
                    entry.app.name.contains(normalizedQuery, ignoreCase = true) ||
                    entry.description.contains(normalizedQuery, ignoreCase = true) ||
                    entry.category.title.contains(normalizedQuery, ignoreCase = true)
                )
    }
    return AppWorkspacePresentation(
        entries = filtered,
        recentEntries = allEntries.filter(AppWorkspaceEntry::recent)
            .sortedBy { entry -> recentIds.indexOf(entry.app.id) },
        pinnedEntries = allEntries.filter(AppWorkspaceEntry::pinned),
        visibleCategories = AppWorkspaceCategory.entries.filter { candidate ->
            candidate == AppWorkspaceCategory.All || allEntries.any { it.category == candidate }
        },
        totalCount = allEntries.size,
        nativeWorkspaceCount = allEntries.count(AppWorkspaceEntry::nativeWorkspace),
    )
}

internal fun appWorkspaceCategory(appId: String): AppWorkspaceCategory = when (appId.lowercase()) {
    "files", "photos", "memories", "music", "audioplayer", "maps" ->
        AppWorkspaceCategory.FilesAndMedia
    "talk", "spreed", "mail", "contacts", "polls" -> AppWorkspaceCategory.Communication
    "calendar", "deck", "tasks", "chores" -> AppWorkspaceCategory.Planning
    "notes", "office", "richdocuments", "collectives", "tables", "forms", "cookbook" ->
        AppWorkspaceCategory.Productivity
    else -> AppWorkspaceCategory.More
}

internal fun appWorkspaceDescription(appId: String): String = when (appId.lowercase()) {
    "files" -> "Browse, share, pin, and organize your cloud files"
    "photos" -> "Browse albums, people, places, and recent uploads"
    "memories" -> "Explore your photo timeline, RAW files, and memories"
    "talk", "spreed" -> "Continue conversations, calls, and shared work"
    "mail" -> "Read and organize mail across connected accounts"
    "calendar" -> "Plan your day across personal and shared calendars"
    "contacts" -> "Find people, groups, addresses, and contact details"
    "notes" -> "Write and organize synced Markdown notes"
    "deck" -> "Plan work with boards, stacks, cards, and due dates"
    "tasks" -> "Track personal and shared task lists"
    "tables" -> "Work with typed records, views, filters, and forms"
    "forms" -> "Create forms and review incoming responses"
    "collectives" -> "Build shared knowledge with your teams"
    "music", "audioplayer" -> "Play your cloud music library and queues"
    "cookbook" -> "Save, plan, and cook from your recipe collection"
    "cospend" -> "Track shared budgets, bills, and balances"
    "activity" -> "Review changes across files, shares, and apps"
    "user_status" -> "Set your availability and status message"
    "maps" -> "Explore saved places, favorites, and shared locations"
    "news" -> "Read your subscribed feeds in one place"
    "bookmarks" -> "Organize saved links, folders, and tags"
    "passwords" -> "Access your encrypted password vault"
    else -> "Open its adaptive native workspace"
}

private fun Set<String>.indexOf(value: String): Int = indexOfFirst { it == value }.let { index ->
    if (index < 0) Int.MAX_VALUE else index
}

private val APP_WORKSPACE_PINNED_IDS = listOf("files", "photos", "talk", "calendar")
private val APP_WORKSPACE_RECENT_FALLBACK_IDS = listOf("files", "talk", "calendar")
private val APP_WORKSPACE_NATIVE_IDS = setOf(
    "files",
    "photos",
    "memories",
    "talk",
    "spreed",
    "mail",
    "calendar",
    "contacts",
    "notes",
    "music",
    "audioplayer",
    "deck",
    "activity",
    "user_status",
)
private const val APP_WORKSPACE_RECENT_LIMIT = 3
