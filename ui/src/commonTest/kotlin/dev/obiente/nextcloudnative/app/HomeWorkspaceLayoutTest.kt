package dev.obiente.nextcloudnative.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HomeWorkspaceLayoutTest {
    @Test
    fun `defaults are useful and distinct for each form factor`() {
        val phone = defaultHomeWorkspaceLayout(scope(HomeFormFactor.Phone))
        val tablet = defaultHomeWorkspaceLayout(scope(HomeFormFactor.Tablet))
        val desktop = defaultHomeWorkspaceLayout(scope(HomeFormFactor.Desktop))

        assertEquals(HomeSectionIds.RecentFiles, phone.sections[1].id)
        assertEquals(HomeSectionIds.Upcoming, tablet.sections[1].id)
        assertEquals(HomeSectionIds.Activity, desktop.sections[1].id)
        assertFalse(phone.sections.single { it.id == HomeSectionIds.Storage }.visible)
        assertTrue(desktop.sections.single { it.id == HomeSectionIds.Storage }.visible)
        assertEquals(
            HomeSectionSize.Dense,
            desktop.sections.single { it.id == HomeSectionIds.Activity }.size,
        )
    }

    @Test
    fun `move keeps visibility and size while clamping drag positions`() {
        val original = defaultHomeWorkspaceLayout(scope(HomeFormFactor.Phone))
        val recent = original.sections.single { it.id == HomeSectionIds.RecentFiles }

        val movedToEnd = original.move(HomeSectionIds.RecentFiles, Int.MAX_VALUE)
        val movedToStart = movedToEnd.move(HomeSectionIds.RecentFiles, -100)

        assertEquals(recent, movedToEnd.sections.last())
        assertEquals(recent, movedToStart.sections.first())
    }

    @Test
    fun `grid drag targets the card under both pointer axes`() {
        val original = defaultHomeWorkspaceLayout(scope(HomeFormFactor.Desktop))
        val sourceId = original.visibleSections.first().id
        val leftTarget = original.visibleSections[1].id
        val rightTarget = original.visibleSections[2].id
        val bounds = mapOf(
            sourceId to Rect(0f, 0f, 100f, 100f),
            leftTarget to Rect(0f, 120f, 100f, 220f),
            rightTarget to Rect(120f, 120f, 220f, 220f),
        )

        val moved = homeWorkspaceLayoutAtDragPosition(
            layout = original,
            sourceId = sourceId,
            position = Offset(170f, 170f),
            sectionBounds = bounds,
        )

        assertEquals(
            original.sections.indexOfFirst { section -> section.id == rightTarget },
            moved.sections.indexOfFirst { section -> section.id == sourceId },
        )
        assertSame(
            original,
            homeWorkspaceLayoutAtDragPosition(
                layout = original,
                sourceId = sourceId,
                position = Offset(110f, 170f),
                sectionBounds = bounds,
            ),
        )
    }

    @Test
    fun `stale section operations are harmless`() {
        val original = defaultHomeWorkspaceLayout(scope(HomeFormFactor.Tablet))
        val unavailable = HomeSectionId("app:unavailable-widget")

        assertSame(original, original.move(unavailable, 0))
        assertSame(original, original.show(unavailable))
        assertSame(original, original.hide(unavailable))
        assertSame(original, original.resize(unavailable, HomeSectionSize.Dense))
    }

    @Test
    fun `show hide and resize preserve ordered sections`() {
        val original = defaultHomeWorkspaceLayout(scope(HomeFormFactor.Phone))
        val storageIndex = original.sections.indexOfFirst { it.id == HomeSectionIds.Storage }

        val changed = original
            .show(HomeSectionIds.Storage)
            .resize(HomeSectionIds.Storage, HomeSectionSize.Dense)
            .hide(HomeSectionIds.PhotoBackup)

        assertEquals(storageIndex, changed.sections.indexOfFirst { it.id == HomeSectionIds.Storage })
        assertTrue(changed.sections[storageIndex].visible)
        assertEquals(HomeSectionSize.Dense, changed.sections[storageIndex].size)
        assertFalse(changed.sections.single { it.id == HomeSectionIds.PhotoBackup }.visible)
    }

    @Test
    fun `the final visible section cannot be hidden`() {
        val only = HomeWorkspaceLayout(
            scope = scope(HomeFormFactor.Phone),
            sections = listOf(
                HomeWorkspaceSection(
                    id = HomeSectionIds.RecentFiles,
                    visible = true,
                    size = HomeSectionSize.Comfortable,
                ),
            ),
        )

        assertSame(only, only.hide(HomeSectionIds.RecentFiles))
    }

    @Test
    fun `restore accepts only matching current scoped snapshots`() {
        val phoneScope = scope(HomeFormFactor.Phone)
        val customized = defaultHomeWorkspaceLayout(phoneScope)
            .move(HomeSectionIds.Activity, 0)
            .hide(HomeSectionIds.PhotoBackup)
            .resize(HomeSectionIds.RecentFiles, HomeSectionSize.Dense)
        val restored = restoreHomeWorkspaceLayout(phoneScope, customized.snapshot())

        assertEquals(customized, restored)
        assertEquals(
            defaultHomeWorkspaceLayout(phoneScope),
            restoreHomeWorkspaceLayout(phoneScope, null),
        )
        assertEquals(
            defaultHomeWorkspaceLayout(scope(HomeFormFactor.Desktop)),
            restoreHomeWorkspaceLayout(scope(HomeFormFactor.Desktop), customized.snapshot()),
        )
        assertEquals(
            defaultHomeWorkspaceLayout(phoneScope),
            restoreHomeWorkspaceLayout(
                phoneScope,
                customized.snapshot().copy(schemaVersion = HOME_WORKSPACE_SCHEMA_VERSION + 1),
            ),
        )
        assertEquals(defaultHomeWorkspaceLayout(phoneScope), customized.restoreDefaults())
    }

    @Test
    fun `restore replaces structurally invalid snapshot content with defaults`() {
        val validScope = scope(HomeFormFactor.Desktop)
        val invalidSnapshot = HomeWorkspaceLayoutSnapshot(
            schemaVersion = HOME_WORKSPACE_SCHEMA_VERSION,
            scope = validScope,
            sections = listOf(
                HomeWorkspaceSection(
                    id = HomeSectionId("app:summary"),
                    visible = false,
                    size = HomeSectionSize.Dense,
                ),
            ),
        )

        assertEquals(
            defaultHomeWorkspaceLayout(validScope),
            restoreHomeWorkspaceLayout(validScope, invalidSnapshot),
        )
    }

    @Test
    fun `account and form factor scopes use digests without raw account details`() {
        val first = scope(HomeFormFactor.Phone, digit = 'a')
        val second = scope(HomeFormFactor.Phone, digit = 'b')
        val desktop = scope(HomeFormFactor.Desktop, digit = 'a')

        assertTrue(first.persistenceKey.endsWith(first.accountScopeDigest))
        assertFalse("https://" in first.persistenceKey)
        assertFalse("@" in first.persistenceKey)
        assertTrue(first.persistenceKey != second.persistenceKey)
        assertTrue(first.persistenceKey != desktop.persistenceKey)
        assertFailsWith<IllegalArgumentException> {
            HomeWorkspaceScope("https://cloud.example.test/person", HomeFormFactor.Phone)
        }
        assertFailsWith<IllegalArgumentException> {
            HomeWorkspaceScope("A".repeat(64), HomeFormFactor.Phone)
        }
    }

    @Test
    fun `layout validation is bounded and rejects duplicate or empty state`() {
        val validScope = scope(HomeFormFactor.Phone)
        val repeated = HomeWorkspaceSection(
            id = HomeSectionId("native:one"),
            visible = true,
            size = HomeSectionSize.Compact,
        )

        assertFailsWith<IllegalArgumentException> {
            HomeWorkspaceLayout(validScope, emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            HomeWorkspaceLayout(validScope, listOf(repeated, repeated))
        }
        assertFailsWith<IllegalArgumentException> {
            HomeWorkspaceLayout(
                validScope,
                (0..MAX_HOME_WORKSPACE_SECTIONS).map { index ->
                    HomeWorkspaceSection(
                        id = HomeSectionId("app:item-$index"),
                        visible = true,
                        size = HomeSectionSize.Compact,
                    )
                },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            HomeWorkspaceLayout(
                validScope,
                listOf(repeated.copy(visible = false)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            HomeSectionId("../unsafe")
        }
        assertFailsWith<IllegalArgumentException> {
            HomeSectionId("app:" + "x".repeat(100))
        }
    }

    @Test
    fun `available sections retain customization and append newly discovered widgets`() {
        val original = defaultHomeWorkspaceLayout(scope(HomeFormFactor.Phone))
            .hide(HomeSectionIds.PhotoBackup)
            .resize(HomeSectionIds.Activity, HomeSectionSize.Dense)
            .move(HomeSectionIds.Activity, 0)
        val discovered = HomeSectionId("dashboard:weather:12345678")

        val reconciled = original.reconcileAvailableSections(
            listOf(HomeSectionIds.QuickActions, HomeSectionIds.Activity, discovered),
        )

        assertEquals(
            listOf(HomeSectionIds.Activity, HomeSectionIds.QuickActions, discovered),
            reconciled.sections.map(HomeWorkspaceSection::id),
        )
        assertEquals(HomeSectionSize.Dense, reconciled.sections.first().size)
        assertTrue(reconciled.sections.last().visible)
        assertEquals(HomeSectionSize.Comfortable, reconciled.sections.last().size)
    }

    @Test
    fun `reconciliation cannot leave an empty or entirely hidden workspace`() {
        val original = defaultHomeWorkspaceLayout(scope(HomeFormFactor.Phone))
            .reconcileAvailableSections(
                listOf(HomeSectionIds.QuickActions, HomeSectionIds.PhotoBackup),
            )
            .hide(HomeSectionIds.PhotoBackup)

        assertTrue(
            original.reconcileAvailableSections(listOf(HomeSectionIds.PhotoBackup))
                .sections
                .single()
                .visible,
        )
        assertFailsWith<IllegalArgumentException> {
            original.reconcileAvailableSections(emptyList())
        }
    }

    @Test
    fun `repository round trip is scoped and contains no raw account identity`() {
        val storage = RecordingHomeWorkspaceStorage()
        val repository = HomeWorkspaceLayoutRepository(storage)
        val phoneScope = scope(HomeFormFactor.Phone)
        val customized = defaultHomeWorkspaceLayout(phoneScope)
            .hide(HomeSectionIds.PhotoBackup)
            .resize(HomeSectionIds.Activity, HomeSectionSize.Dense)

        assertTrue(repository.save(customized))
        assertEquals(customized, repository.load(phoneScope))
        assertTrue(storage.lastKey?.startsWith("home:") == true)
        assertTrue(storage.lastKey.orEmpty().length <= 80)
        assertFalse(storage.lastKey.orEmpty().contains("https://"))
        assertFalse(storage.lastValue.orEmpty().contains("example.test"))
        assertEquals(
            defaultHomeWorkspaceLayout(scope(HomeFormFactor.Desktop)),
            repository.load(scope(HomeFormFactor.Desktop)),
        )
    }

    @Test
    fun `repository reports snapshot encoding failures without touching storage`() {
        val storage = RecordingHomeWorkspaceStorage()
        val repository = HomeWorkspaceLayoutRepository(
            storage = storage,
            encodeSnapshot = { error("The encoded snapshot exceeds its safe bound.") },
        )

        assertFalse(repository.save(defaultHomeWorkspaceLayout(scope(HomeFormFactor.Phone))))
        assertEquals(null, storage.lastKey)
        assertEquals(null, storage.lastValue)
    }

    @Test
    fun `corrupt oversized and cross account snapshots restore safe defaults`() {
        val validScope = scope(HomeFormFactor.Phone)
        val otherScope = scope(HomeFormFactor.Phone, digit = 'b')
        val valid = defaultHomeWorkspaceLayout(validScope)
        val encoded = encodeHomeWorkspaceLayoutSnapshot(valid)

        assertEquals(valid, decodeHomeWorkspaceLayoutSnapshot(validScope, encoded))
        assertEquals(
            defaultHomeWorkspaceLayout(validScope),
            decodeHomeWorkspaceLayoutSnapshot(validScope, "{not-json"),
        )
        assertEquals(
            defaultHomeWorkspaceLayout(validScope),
            decodeHomeWorkspaceLayoutSnapshot(
                validScope,
                "x".repeat(MAX_HOME_WORKSPACE_SNAPSHOT_CHARACTERS + 1),
            ),
        )
        assertNotEquals(
            valid,
            decodeHomeWorkspaceLayoutSnapshot(otherScope, encoded),
        )
        assertEquals(
            defaultHomeWorkspaceLayout(otherScope),
            decodeHomeWorkspaceLayoutSnapshot(otherScope, encoded),
        )
    }

    private fun scope(
        formFactor: HomeFormFactor,
        digit: Char = 'a',
    ): HomeWorkspaceScope = HomeWorkspaceScope(
        accountScopeDigest = digit.toString().repeat(64),
        formFactor = formFactor,
    )

    private class RecordingHomeWorkspaceStorage : HomeWorkspaceLayoutStorage {
        private val values = mutableMapOf<String, String>()
        var lastKey: String? = null
        var lastValue: String? = null

        override fun read(persistenceKey: String): String? = values[persistenceKey]

        override fun write(persistenceKey: String, encodedSnapshot: String) {
            lastKey = persistenceKey
            lastValue = encodedSnapshot
            values[persistenceKey] = encodedSnapshot
        }
    }
}
