package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IncomingFileShareTest {
    @Test
    fun providerNamesAreReducedToOneSafePathSegment() {
        assertEquals("report_final.pdf", safeIncomingShareFileName(" ../report/final.pdf ", 0))
        assertEquals("shared-file-3", safeIncomingShareFileName("..", 2))
        assertTrue(safeIncomingShareFileName("a".repeat(400), 0).length <= MAX_INCOMING_SHARE_FILE_NAME_LENGTH)
        assertTrue(safeIncomingShareFileName("😀".repeat(100), 0).encodeToByteArray().size <= 255)
    }

    @Test
    fun keepBothCandidatesPreserveTheFinalExtension() {
        assertEquals(
            listOf("archive.tar.gz", "archive.tar (1).gz", "archive.tar (2).gz"),
            incomingShareUploadNameCandidates("archive.tar.gz", limit = 3),
        )
        assertEquals(
            listOf("README", "README (1)"),
            incomingShareUploadNameCandidates("README", limit = 2),
        )
        assertTrue(
            incomingShareUploadNameCandidates("😀".repeat(100) + ".txt", limit = 100)
                .all { it.encodeToByteArray().size <= 255 && it.endsWith(".txt") },
        )
    }

    @Test
    fun destinationPathsStayInsideTheSelectedFolder() {
        assertEquals("photo.jpg", incomingShareRemotePath("", "photo.jpg"))
        assertEquals("Shared/Phone/photo.jpg", incomingShareRemotePath("Shared/Phone", "photo.jpg"))
        assertFailsWith<IllegalArgumentException> {
            incomingShareRemotePath("../Shared", "photo.jpg")
        }
    }
}
