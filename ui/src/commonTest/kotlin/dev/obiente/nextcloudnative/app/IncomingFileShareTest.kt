package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IncomingFileShareTest {
    @Test
    fun providerNamesAreReducedToOneSafePathSegment() {
        assertEquals("report_final.pdf", safeIncomingShareFileName(" ../report/final.pdf ", 0))
        assertEquals("invoice_gpj.exe", safeIncomingShareFileName("invoice\u202egpj.exe", 0))
        assertEquals("zero_width.txt", safeIncomingShareFileName("zero\u200bwidth.txt", 0))
        assertEquals("shared-file-3", safeIncomingShareFileName("..", 2))
        assertTrue(safeIncomingShareFileName("a".repeat(400), 0).length <= MAX_INCOMING_SHARE_FILE_NAME_LENGTH)
        assertTrue(safeIncomingShareFileName("😀".repeat(100), 0).encodeToByteArray().size <= 255)
        assertEquals(
            "a".repeat(235) + ".txt",
            safeIncomingShareFileName("a".repeat(235) + "😀.txt", 0),
        )
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
                .all {
                    it.length <= MAX_INCOMING_SHARE_FILE_NAME_LENGTH &&
                        it.encodeToByteArray().size <= MAX_INCOMING_SHARE_FILE_NAME_BYTES &&
                        it.endsWith(".txt")
                },
        )
        assertTrue(
            incomingShareUploadNameCandidates("a".repeat(MAX_INCOMING_SHARE_FILE_NAME_LENGTH), limit = 1_000)
                .all {
                    it.length <= MAX_INCOMING_SHARE_FILE_NAME_LENGTH &&
                        it.encodeToByteArray().size <= MAX_INCOMING_SHARE_FILE_NAME_BYTES
                },
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

    @Test
    fun recoveryPrioritizesAResultThatNeedsReview() {
        val active = recovery("active", IncomingShareUploadState.Uploading)
        val uncertain = recovery("uncertain", IncomingShareUploadState.OutcomeUnknown)

        assertEquals(uncertain, listOf(active, uncertain).primaryIncomingShareRecovery())
        assertEquals(active, listOf(active).primaryIncomingShareRecovery())
        assertEquals(null, emptyList<IncomingShareUploadPresentation>().primaryIncomingShareRecovery())
    }

    private fun recovery(id: String, state: IncomingShareUploadState) = IncomingShareUploadPresentation(
        id = id,
        files = listOf(IncomingShareUploadFilePresentation("file-$id", "$id.txt", 1L)),
        state = state,
        destinationPath = "Shared",
        completedFiles = 0,
        message = null,
    )
}
