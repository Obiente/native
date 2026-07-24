package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable

/**
 * The broad device class used to scope a home layout.
 *
 * This is intentionally independent from a transient window width. A phone layout should not be
 * overwritten when the same account is opened on desktop or when a device is rotated.
 */
@Serializable
enum class HomeFormFactor {
    Phone,
    Tablet,
    Desktop,
}

@Serializable
enum class HomeSectionSize {
    Compact,
    Comfortable,
    Dense,
}

/**
 * A stable, non-secret section identifier.
 *
 * Identifiers are deliberately restricted to a small URL-safe alphabet so they can be used in
 * future local persistence without escaping or accepting unbounded server-provided values.
 */
@Serializable
@JvmInline
value class HomeSectionId(val value: String) {
    init {
        require(value.length in 1..MAX_HOME_SECTION_ID_LENGTH) {
            "The home section ID length is invalid."
        }
        require(value.first().isLowerAsciiLetter()) {
            "The home section ID must start with a lowercase ASCII letter."
        }
        require(value.all(Char::isHomeSectionIdCharacter)) {
            "The home section ID contains unsupported characters."
        }
    }
}

/**
 * Persistence scope for one account and one form factor.
 *
 * Callers must derive [accountScopeDigest] from the canonical account identity before constructing
 * this value. A server URL, login name, display name, or other raw account detail is rejected.
 */
@Serializable
data class HomeWorkspaceScope(
    val accountScopeDigest: String,
    val formFactor: HomeFormFactor,
) {
    init {
        require(accountScopeDigest.isCanonicalSha256Digest()) {
            "The home workspace account scope must be a canonical SHA-256 digest."
        }
    }

    /**
     * Safe key material for platform persistence. It contains only a schema marker, form factor,
     * and the one-way account scope digest. The compact prefix stays below the Java Preferences
     * key limit used by the desktop implementation.
     */
    val persistenceKey: String
        get() = "home:$HOME_WORKSPACE_SCHEMA_VERSION:${formFactor.persistenceCode}:$accountScopeDigest"
}

@Serializable
data class HomeWorkspaceSection(
    val id: HomeSectionId,
    val visible: Boolean,
    val size: HomeSectionSize,
)

/**
 * Validated, ordered home workspace state.
 *
 * Hidden sections remain in the ordered list so showing one again does not unexpectedly move it.
 */
data class HomeWorkspaceLayout(
    val scope: HomeWorkspaceScope,
    val sections: List<HomeWorkspaceSection>,
) {
    init {
        require(sections.isNotEmpty()) { "The home workspace must contain at least one section." }
        require(sections.size <= MAX_HOME_WORKSPACE_SECTIONS) {
            "The home workspace contains too many sections."
        }
        require(sections.map(HomeWorkspaceSection::id).distinct().size == sections.size) {
            "The home workspace contains duplicate section IDs."
        }
        require(sections.any(HomeWorkspaceSection::visible)) {
            "The home workspace must keep at least one section visible."
        }
    }

    val visibleSections: List<HomeWorkspaceSection>
        get() = sections.filter(HomeWorkspaceSection::visible)

    val hiddenSections: List<HomeWorkspaceSection>
        get() = sections.filterNot(HomeWorkspaceSection::visible)

    /**
     * Reorders a section using its position in the complete list, including hidden sections.
     *
     * Stale section IDs are ignored and drag positions outside the current list are clamped. This
     * makes the operation safe to call from asynchronous drag-and-drop UI.
     */
    fun move(sectionId: HomeSectionId, destinationIndex: Int): HomeWorkspaceLayout {
        val sourceIndex = sections.indexOfFirst { it.id == sectionId }
        if (sourceIndex < 0) return this
        val boundedDestination = destinationIndex.coerceIn(0, sections.lastIndex)
        if (sourceIndex == boundedDestination) return this

        val reordered = sections.toMutableList()
        val section = reordered.removeAt(sourceIndex)
        reordered.add(boundedDestination, section)
        return copy(sections = reordered)
    }

    fun show(sectionId: HomeSectionId): HomeWorkspaceLayout =
        update(sectionId) { section -> section.copy(visible = true) }

    /**
     * Hides a section while preventing an accidentally empty home workspace.
     */
    fun hide(sectionId: HomeSectionId): HomeWorkspaceLayout {
        val section = sections.firstOrNull { it.id == sectionId } ?: return this
        if (!section.visible || visibleSections.size == 1) return this
        return update(sectionId) { it.copy(visible = false) }
    }

    fun resize(sectionId: HomeSectionId, size: HomeSectionSize): HomeWorkspaceLayout =
        update(sectionId) { section -> section.copy(size = size) }

    fun restoreDefaults(): HomeWorkspaceLayout = defaultHomeWorkspaceLayout(scope)

    fun snapshot(): HomeWorkspaceLayoutSnapshot = HomeWorkspaceLayoutSnapshot(
        schemaVersion = HOME_WORKSPACE_SCHEMA_VERSION,
        scope = scope,
        sections = sections,
    )

    private inline fun update(
        sectionId: HomeSectionId,
        transform: (HomeWorkspaceSection) -> HomeWorkspaceSection,
    ): HomeWorkspaceLayout {
        val index = sections.indexOfFirst { it.id == sectionId }
        if (index < 0) return this
        val updatedSection = transform(sections[index])
        if (updatedSection == sections[index]) return this
        return copy(sections = sections.toMutableList().also { it[index] = updatedSection })
    }
}

/**
 * Storage-neutral snapshot. Platform repositories may encode this without ever receiving raw
 * account details.
 */
@Serializable
data class HomeWorkspaceLayoutSnapshot(
    val schemaVersion: Int,
    val scope: HomeWorkspaceScope,
    val sections: List<HomeWorkspaceSection>,
) {
    init {
        require(schemaVersion in 1..MAX_HOME_WORKSPACE_SCHEMA_VERSION) {
            "The home workspace schema version is invalid."
        }
        require(sections.size <= MAX_HOME_WORKSPACE_SECTIONS) {
            "The home workspace snapshot contains too many sections."
        }
    }
}

/**
 * Restores a snapshot only when both its schema and complete non-secret scope match.
 *
 * Missing, stale, or cross-account snapshots fall back to useful defaults instead of leaking one
 * account's customization into another account or device class.
 */
fun restoreHomeWorkspaceLayout(
    scope: HomeWorkspaceScope,
    snapshot: HomeWorkspaceLayoutSnapshot?,
): HomeWorkspaceLayout {
    if (
        snapshot == null ||
        snapshot.schemaVersion != HOME_WORKSPACE_SCHEMA_VERSION ||
        snapshot.scope != scope
    ) {
        return defaultHomeWorkspaceLayout(scope)
    }
    return try {
        HomeWorkspaceLayout(scope = scope, sections = snapshot.sections)
    } catch (_: IllegalArgumentException) {
        defaultHomeWorkspaceLayout(scope)
    }
}

fun defaultHomeWorkspaceLayout(scope: HomeWorkspaceScope): HomeWorkspaceLayout =
    HomeWorkspaceLayout(
        scope = scope,
        sections = when (scope.formFactor) {
            HomeFormFactor.Phone -> listOf(
                section(HomeSectionIds.QuickActions, visible = true, HomeSectionSize.Compact),
                section(HomeSectionIds.RecentFiles, visible = true, HomeSectionSize.Comfortable),
                section(HomeSectionIds.PhotoBackup, visible = true, HomeSectionSize.Compact),
                section(HomeSectionIds.Upcoming, visible = true, HomeSectionSize.Compact),
                section(HomeSectionIds.Activity, visible = true, HomeSectionSize.Compact),
                section(HomeSectionIds.Favorites, visible = false, HomeSectionSize.Compact),
                section(HomeSectionIds.Storage, visible = false, HomeSectionSize.Compact),
            )

            HomeFormFactor.Tablet -> listOf(
                section(HomeSectionIds.QuickActions, visible = true, HomeSectionSize.Compact),
                section(HomeSectionIds.Upcoming, visible = true, HomeSectionSize.Comfortable),
                section(HomeSectionIds.RecentFiles, visible = true, HomeSectionSize.Comfortable),
                section(HomeSectionIds.Activity, visible = true, HomeSectionSize.Dense),
                section(HomeSectionIds.PhotoBackup, visible = true, HomeSectionSize.Compact),
                section(HomeSectionIds.Favorites, visible = true, HomeSectionSize.Compact),
                section(HomeSectionIds.Storage, visible = false, HomeSectionSize.Compact),
            )

            HomeFormFactor.Desktop -> listOf(
                section(HomeSectionIds.QuickActions, visible = true, HomeSectionSize.Compact),
                section(HomeSectionIds.Activity, visible = true, HomeSectionSize.Dense),
                section(HomeSectionIds.Upcoming, visible = true, HomeSectionSize.Dense),
                section(HomeSectionIds.RecentFiles, visible = true, HomeSectionSize.Comfortable),
                section(HomeSectionIds.Favorites, visible = true, HomeSectionSize.Dense),
                section(HomeSectionIds.PhotoBackup, visible = true, HomeSectionSize.Compact),
                section(HomeSectionIds.Storage, visible = true, HomeSectionSize.Compact),
            )
        },
    )

/**
 * Restricts a layout to currently available sections while retaining compatible customization.
 *
 * New sections are appended and visible by default. Missing sections are removed so stale server
 * widgets do not leave empty spaces. At least one section is made visible after reconciliation.
 */
fun HomeWorkspaceLayout.reconcileAvailableSections(
    availableSectionIds: List<HomeSectionId>,
): HomeWorkspaceLayout {
    require(availableSectionIds.isNotEmpty()) {
        "The home workspace must have at least one available section."
    }
    require(availableSectionIds.size <= MAX_HOME_WORKSPACE_SECTIONS) {
        "The home workspace has too many available sections."
    }
    require(availableSectionIds.distinct().size == availableSectionIds.size) {
        "The home workspace has duplicate available section IDs."
    }

    val available = availableSectionIds.toSet()
    val retained = sections.filter { it.id in available }.toMutableList()
    val retainedIds = retained.mapTo(mutableSetOf(), HomeWorkspaceSection::id)
    availableSectionIds.forEach { id ->
        if (id !in retainedIds) {
            retained += HomeWorkspaceSection(
                id = id,
                visible = true,
                size = defaultHomeSectionSize(id, scope.formFactor),
            )
        }
    }
    if (retained.none(HomeWorkspaceSection::visible)) {
        retained[0] = retained[0].copy(visible = true)
    }
    return copy(sections = retained)
}

object HomeSectionIds {
    val QuickActions = HomeSectionId("native:quick-actions")
    val RecentFiles = HomeSectionId("native:recent-files")
    val PhotoBackup = HomeSectionId("native:photo-backup")
    val Upcoming = HomeSectionId("native:upcoming")
    val Activity = HomeSectionId("native:activity")
    val Favorites = HomeSectionId("native:favorites")
    val Storage = HomeSectionId("native:storage")
}

private fun section(
    id: HomeSectionId,
    visible: Boolean,
    size: HomeSectionSize,
): HomeWorkspaceSection = HomeWorkspaceSection(id = id, visible = visible, size = size)

private fun defaultHomeSectionSize(
    id: HomeSectionId,
    formFactor: HomeFormFactor,
): HomeSectionSize = when (id) {
    HomeSectionIds.Activity -> if (formFactor == HomeFormFactor.Phone) {
        HomeSectionSize.Compact
    } else {
        HomeSectionSize.Dense
    }
    HomeSectionIds.Upcoming -> when (formFactor) {
        HomeFormFactor.Phone -> HomeSectionSize.Compact
        HomeFormFactor.Tablet -> HomeSectionSize.Comfortable
        HomeFormFactor.Desktop -> HomeSectionSize.Dense
    }
    HomeSectionIds.Favorites -> if (formFactor == HomeFormFactor.Desktop) {
        HomeSectionSize.Dense
    } else {
        HomeSectionSize.Compact
    }
    HomeSectionIds.RecentFiles -> HomeSectionSize.Comfortable
    HomeSectionIds.QuickActions,
    HomeSectionIds.PhotoBackup,
    HomeSectionIds.Storage,
    -> HomeSectionSize.Compact
    else -> HomeSectionSize.Comfortable
}

private fun String.isCanonicalSha256Digest(): Boolean =
    length == SHA256_HEX_CHARACTER_COUNT && all { it in '0'..'9' || it in 'a'..'f' }

private fun Char.isLowerAsciiLetter(): Boolean = this in 'a'..'z'

private fun Char.isHomeSectionIdCharacter(): Boolean =
    isLowerAsciiLetter() || this in '0'..'9' || this == '-' || this == '_' || this == ':' || this == '.'

private val HomeFormFactor.persistenceCode: Char
    get() = when (this) {
        HomeFormFactor.Phone -> 'p'
        HomeFormFactor.Tablet -> 't'
        HomeFormFactor.Desktop -> 'd'
    }

const val HOME_WORKSPACE_SCHEMA_VERSION = 1
const val MAX_HOME_WORKSPACE_SECTIONS = 32

private const val MAX_HOME_SECTION_ID_LENGTH = 80
private const val MAX_HOME_WORKSPACE_SCHEMA_VERSION = 1_000
private const val SHA256_HEX_CHARACTER_COUNT = 64
