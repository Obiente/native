package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class OfficeWorkspaceLocationTest {
    private val session = NextcloudSession("https://cloud.example.test", "person", "private-password")
    private val scope = previewCacheDigest(session)
    private val saver = officeWorkspaceLocationSaver(scope)
    private val location = OfficeWorkspaceLocation("Projects/Plan", 42)

    @Test
    fun savesOnlyBoundedLocationAndIdentityWithAnAccountDigest() {
        val saved = requireNotNull(with(saver) { SaverScope { true }.save(location) })
        assertEquals(listOf("office-location-v1", scope, "Projects/Plan", "42"), saved)
        assertEquals(location, saver.restore(saved))
        listOf(session.serverUrl, session.loginName, session.appPassword).forEach { assertFalse(it in saved.toString()) }
        assertEquals(location, officeWorkspaceLocationSaver(previewCacheDigest(session.copy(appPassword = "rotated"))).restore(saved))
        assertNull(officeWorkspaceLocationSaver(previewCacheDigest(session.copy(loginName = "other"))).restore(saved))
        assertNull(officeWorkspaceLocationSaver(previewCacheDigest(session.copy(serverUrl = "https://other.example.test"))).restore(saved))
        val holderSaver = officeWorkspaceLocationStateSaver(scope)
        assertEquals(location, holderSaver.restore(saved)?.value)
        assertNull(holderSaver.restore(saved.toMutableList().also { it[1] = "0".repeat(64) }))
    }

    @Test
    fun rejectsMalformedOversizedAndCrossAccountSavedState() {
        listOf(
            emptyList(), listOf("office-location-v1", scope, "Projects"),
            listOf("office-location-v2", scope, "Projects", "42"),
            listOf("office-location-v1", scope, "../private", "42"),
            listOf("office-location-v1", scope, "/Projects", "42"),
            listOf("office-location-v1", scope, "a".repeat(8_193), "42"),
            listOf("office-location-v1", scope, "Projects\u0000", "42"),
            listOf("office-location-v1", scope, "Projects", "-1"),
            listOf("office-location-v1", scope, "Projects", "9223372036854775808"),
            listOf("office-location-v1", scope, "Projects", "token"),
        ).forEach { assertNull(saver.restore(it)) }
        assertEquals(OfficeWorkspaceLocation(), saver.restore(listOf("office-location-v1", scope, "", "")))
    }

    @Test
    fun restoresOnlyOneFreshSupportedFileWithTheSameStableIdentity() {
        val refreshed = NextcloudFile("Projects/Plan/Renamed.pdf", "Renamed.pdf", false, "application/pdf", 12, null,
            fileId = 42, hasPreview = false, etag = "fresh", permissions = "R")
        val state = OfficeWorkspaceState("Projects/Plan", listOf(refreshed), loading = false, listingNetworkConfirmed = true)
        assertEquals(refreshed, location.resolveSelection(state))
        assertNull(location.resolveSelection(state.copy(loading = true)))
        assertNull(location.resolveSelection(state.copy(listingNetworkConfirmed = false)))
        assertNull(location.resolveSelection(state.copy(path = "Projects")))
        assertNull(location.resolveSelection(state.copy(files = emptyList())))
        assertNull(location.resolveSelection(state.copy(files = listOf(refreshed.copy(fileId = 43)))))
        assertNull(location.resolveSelection(state.copy(files = listOf(refreshed.copy(isDirectory = true)))))
        assertNull(location.resolveSelection(state.copy(files = listOf(refreshed, refreshed.copy(path = "Projects/Plan/Other.pdf")))))
        assertNull(location.resolveSelection(state.copy(files = listOf(refreshed.copy(path = "Other/Renamed.pdf")))))
    }
}
