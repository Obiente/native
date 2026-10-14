package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A deleted file leaves the viewer's route; a stack keeps its surviving members. */
class MediaViewerRouteRemovalTest {
    private val alice = NextcloudSession("https://route-removal.invalid", "alice", "synthetic").accountId
    private val bob = NextcloudSession("https://route-removal.invalid", "bob", "synthetic").accountId
    private val first = photo("first.jpg", 7L)
    private val second = photo("second.jpg", 8L)
    private val third = photo("third.jpg", 9L)
    private val stackJpeg = photo("stack.jpg", 21L)
    private val stackRaw = raw("stack.dng", 22L)
    private val otherJpeg = photo("other.jpg", 31L)
    private val otherRaw = raw("other.dng", 32L)

    @Test
    fun aRemovedItemLeavesAndTheNextOrPreviousItemIsShownUntilNoneIsLeft() {
        fun removeFrom(removed: NextcloudFile, shown: NextcloudFile): Pair<String, Int> {
            val repository = MediaViewerNavigationRepository()
            val route = assertNotNull(repository.register(alice, listOf(first, second, third), shown))
            val snapshot = repository.after(repository.remove(alice, route, removed))
            assertEquals(null, snapshot.sourceMembers.firstOrNull { it.path == removed.path }, "The removed item is not navigable.")
            return snapshot.selected.name to snapshot.media.size
        }

        assertEquals("first.jpg" to 2, removeFrom(third, first), "Another item only leaves the sequence.")
        assertEquals("third.jpg" to 2, removeFrom(second, second), "The next item replaces the shown one.")
        assertEquals("second.jpg" to 2, removeFrom(third, third), "The previous item replaces the last one.")
        val repository = MediaViewerNavigationRepository()
        val only = assertNotNull(repository.register(alice, listOf(first), first))
        assertNull(repository.remove(alice, only, first), "Removing the last item ends the route.")
        assertNull(repository.resolve(alice, only), "The ended route cannot be restored.")
        val other = assertNotNull(repository.register(alice, listOf(first, second), first))
        assertNull(repository.remove(bob, other, first), "Another account cannot change the route.")
        assertEquals(2, assertNotNull(repository.resolve(alice, other)).media.size)
        val kept = assertNotNull(repository.remove(alice, other, first))
        assertNotEquals(other, kept, "A removal changes the route even when the next item takes the same index.")
        assertNull(repository.resolve(alice, other), "A stale copy of the old route no longer resolves.")
        assertEquals(listOf(first), mediaViewerItems(listOf(second, third), first), "A shown item outside the list stays shown.")
    }

    @Test
    fun deletingAStackCoverPromotesTheSurvivingMemberInTheSamePosition() {
        val repository = MediaViewerNavigationRepository()
        val route = repository.registerStacks(listOf(first, stackJpeg, stackRaw, third), stackJpeg)

        val snapshot = repository.after(repository.remove(alice, route, stackJpeg))

        assertEquals(listOf(first, stackRaw, third), snapshot.media, "The RAW member becomes the cover in place.")
        assertEquals(stackRaw, snapshot.selected, "The viewer stays open on the surviving member.")
        assertEquals(setOf(first, stackRaw, third), snapshot.sourceMembers.toSet(), "Only the deleted cover leaves the sources.")
    }

    @Test
    fun deletingAMemberThatIsNotTheCoverKeepsTheItemAndItsCover() {
        val repository = MediaViewerNavigationRepository()
        val shownCover = repository.registerStacks(listOf(stackJpeg, stackRaw, third), stackJpeg)
        val coverKept = repository.after(repository.remove(alice, shownCover, stackRaw))

        assertEquals(listOf(stackJpeg, third), coverKept.media)
        assertEquals(setOf(stackJpeg, third), coverKept.sourceMembers.toSet(), "Only the RAW member leaves.")
        assertEquals(stackJpeg, coverKept.selected)

        val shownRaw = repository.registerStacks(listOf(stackJpeg, stackRaw, third), stackRaw)
        val rawRemoved = repository.after(repository.remove(alice, shownRaw, stackRaw))
        assertEquals(stackJpeg, rawRemoved.selected, "A removed shown member moves to its stack's cover.")
        assertEquals(0, rawRemoved.selectedIndex)
    }

    @Test
    fun deletingEveryMemberRemovesTheItem() {
        val repository = MediaViewerNavigationRepository()
        val route = repository.registerStacks(listOf(stackJpeg, stackRaw, third), stackJpeg)
        val afterCover = assertNotNull(repository.remove(alice, route, stackJpeg))
        val afterRaw = repository.after(repository.remove(alice, afterCover, stackRaw))

        assertEquals(listOf(third), afterRaw.media)
        assertEquals(third, afterRaw.selected, "The next item is shown once the whole stack is gone.")

        val single = repository.registerStacks(listOf(stackJpeg, stackRaw), stackRaw)
        val rawOnly = assertNotNull(repository.remove(alice, single, stackJpeg))
        assertNull(repository.remove(alice, rawOnly, stackRaw), "The viewer closes when no member is left.")
    }

    @Test
    fun siblingsMappedToADeletedCoverFollowTheirPromotedCover() {
        val repository = MediaViewerNavigationRepository()
        val route = repository.registerStacks(listOf(stackJpeg, stackRaw, otherJpeg, otherRaw), otherJpeg)

        val next = assertNotNull(repository.remove(alice, route, stackJpeg))
        val snapshot = repository.after(next)

        assertEquals(listOf(stackRaw, otherJpeg), snapshot.media)
        assertEquals(otherJpeg, snapshot.selected, "The shown item in another stack stays shown.")
        assertEquals(1, snapshot.selectedIndex)
        val promoted = repository.after(repository.select(alice, next, stackRaw))
        assertEquals(0, promoted.selectedIndex, "The surviving RAW member is its own navigation item.")
        val otherMember = repository.after(repository.select(alice, next, otherRaw))
        assertEquals(1, otherMember.selectedIndex, "Another stack keeps its mapping.")
        assertEquals(otherRaw, otherMember.selected)
    }

    private fun MediaViewerNavigationRepository.registerStacks(
        files: List<NextcloudFile>,
        selected: NextcloudFile,
    ): MediaViewerNavigationRoute {
        val sequence = mediaStackViewerSequence(stackMediaFiles(files))
        val route = assertNotNull(
            register(
                alice,
                sequence.navigationItems,
                sequence.navigationItems.first { it.sharesMediaStackWith(selected) },
                sourceMembers = sequence.sourceMembers,
                navigationIdentityBySourceIdentity = sequence.navigationIdentityBySourceIdentity,
            ),
        )
        return assertNotNull(select(alice, route, selected)).also { assertEquals(selected, after(it).selected) }
    }

    private fun MediaViewerNavigationRepository.after(route: MediaViewerNavigationRoute?): MediaViewerNavigationSnapshot =
        assertNotNull(resolve(alice, assertNotNull(route)))

    private fun photo(name: String, id: Long) = NextcloudFile(
        path = "Photos/$name",
        name = name,
        isDirectory = false,
        mimeType = "image/jpeg",
        size = 1,
        lastModified = null,
        fileId = id,
        hasPreview = true,
        etag = "v1",
    )

    private fun raw(name: String, id: Long) = photo(name, id).copy(mimeType = "image/x-adobe-dng")
}
