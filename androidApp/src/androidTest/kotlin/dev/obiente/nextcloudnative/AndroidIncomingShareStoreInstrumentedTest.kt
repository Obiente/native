package dev.obiente.nextcloudnative

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidIncomingShareStoreInstrumentedTest {
    private val context: android.content.Context = ApplicationProvider.getApplicationContext()

    @Test
    fun singleAndMultipleContentSharesBecomeDurablePrivateSnapshots() = runBlocking {
        val store = AndroidIncomingShareStore(context)
        val single = store.stage(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, fixtureUri("one.txt"))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "account-1",
        )
        try {
            assertEquals(listOf("one.txt"), single.files.map(AndroidIncomingShareFile::displayName))
            assertTrue(store.listRecoverablePage("account-1", cursor = null).requests.any { it.id == single.id })
            assertArrayEquals(
                IncomingShareFixtureProvider.payload("one.txt"),
                store.stagedFile(single.id, single.files.single()).readBytes(),
            )
            assertEquals(single, AndroidIncomingShareStore(context).load(single.id))
            store.save(single.copy(state = AndroidIncomingShareState.Completed))
            assertTrue(store.listRecoverablePage("account-1", cursor = null).requests.none { it.id == single.id })
        } finally {
            assertTrue(store.remove(single.id))
        }

        val sharedText = store.stage(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "A URL or selected text shared from another app"),
            "account-1",
        )
        try {
            assertEquals("shared-text.txt", sharedText.files.single().displayName)
            assertEquals("text/plain; charset=utf-8", sharedText.files.single().mimeType)
            assertArrayEquals(
                "A URL or selected text shared from another app".encodeToByteArray(),
                store.stagedFile(sharedText.id, sharedText.files.single()).readBytes(),
            )
        } finally {
            assertTrue(store.remove(sharedText.id))
        }

        val first = fixtureUri("one.txt")
        val large = fixtureUri("large.bin")
        val multiple = store.stage(
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, large))
                .apply {
                    clipData = ClipData.newRawUri("shared files", first).also {
                        it.addItem(ClipData.Item(large))
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            "account-1",
        )
        try {
            assertEquals(listOf("one.txt", "large.bin"), multiple.files.map(AndroidIncomingShareFile::displayName))
            assertEquals(IncomingShareFixtureProvider.payload("large.bin").size.toLong(), multiple.files.last().sizeBytes)
            assertArrayEquals(
                IncomingShareFixtureProvider.payload("large.bin"),
                AndroidIncomingShareStore(context)
                    .stagedFile(multiple.id, multiple.files.last())
                    .readBytes(),
            )
            assertArrayEquals(
                IncomingShareFixtureProvider.payload("one.txt"),
                requireNotNull(context.contentResolver.openInputStream(first)).use { it.readBytes() },
            )
        } finally {
            assertTrue(store.remove(multiple.id))
        }
    }

    @Test
    fun malformedMultipleStreamExtraIsRejectedWithoutStaging() = runBlocking {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("*/*")
            .putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                arrayListOf<Parcelable>(android.os.Bundle()),
            )

        try {
            AndroidIncomingShareStore(context).stage(intent, "account-1")
            fail("Expected the malformed stream extra to be rejected")
        } catch (failure: IllegalArgumentException) {
            assertTrue(failure.message.orEmpty().contains("invalid file reference"))
        }
    }

    @Test
    fun chunkProgressSurvivesRecreationAndCorruptManifestsRemainRecoverable() = runBlocking {
        val store = AndroidIncomingShareStore(context)
        val staged = store.stage(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, fixtureUri("one.txt"))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "account-1",
        )
        try {
            val uploading = staged.copy(
                state = AndroidIncomingShareState.Uploading,
                automaticTransferAttempts = 3,
                retryNotBeforeEpochMillis = 240_000L,
                chunkSession = AndroidIncomingShareChunkSession(
                    fileIndex = 0,
                    targetName = "one.txt",
                    uploadId = "01234567-89ab-cdef-0123-456789abcdef",
                    uploadedChunks = 3,
                ),
            )
            store.save(uploading)
            assertEquals(uploading, AndroidIncomingShareStore(context).requireAvailable(staged.id))
            assertEquals(3, AndroidIncomingShareStore(context).requireAvailable(staged.id).automaticTransferAttempts)
            assertEquals(240_000L, AndroidIncomingShareStore(context).requireAvailable(staged.id).retryNotBeforeEpochMillis)
            val preCommitCleanup = store.markChunkCleanupPending(staged.id)
            assertTrue(preCommitCleanup.chunkSession?.cleanupPending == true)
            store.clearChunkSession(staged.id)
            store.beginChunkSession(
                staged.id,
                fileIndex = 0,
                targetName = "one.txt",
                uploadId = "01234567-89ab-cdef-0123-456789abcdef",
            )
            assertTrue(store.markChunkCommitInFlight(staged.id).chunkSession?.commitInFlight == true)
            val cleanupPending = store.markChunkCleanupPending(staged.id)
            assertTrue(cleanupPending.chunkSession?.cleanupPending == true)
            assertTrue(cleanupPending.chunkSession?.commitInFlight == false)
            assertTrue(
                AndroidIncomingShareStore(context)
                    .requireAvailable(staged.id)
                    .chunkSession
                    ?.cleanupPending == true,
            )
            assertNull(
                store.claimChunkSessionForCleanup(
                    staged.id,
                    "01234567-89ab-cdef-0123-456789abcdef",
                ),
            )
            store.transition(
                staged.id,
                expected = setOf(AndroidIncomingShareState.Uploading),
                target = AndroidIncomingShareState.Failed,
            )
            assertNotNull(
                store.claimChunkSessionForCleanup(
                    staged.id,
                    "01234567-89ab-cdef-0123-456789abcdef",
                ),
            )
            assertTrue(
                store.clearChunkSessionForCleanup(
                    staged.id,
                    "01234567-89ab-cdef-0123-456789abcdef",
                )?.chunkSession == null,
            )
        } finally {
            assertTrue(store.remove(staged.id))
        }

        val releasable = store.stage(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, fixtureUri("release.txt"))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "account-1",
        )
        assertFalse(store.removeIfMatchingReleasable(releasable.id, "not-the-presented-state"))
        assertTrue(store.loadResult(releasable.id) is AndroidIncomingShareLoadResult.Available)
        assertTrue(
            store.removeIfMatchingReleasable(
                releasable.id,
                releasable.incomingShareReleaseFingerprint(),
            ),
        )
        assertTrue(store.loadResult(releasable.id) is AndroidIncomingShareLoadResult.Missing)

        val corruptId = "11111111-2222-3333-4444-555555555555"
        val directory = java.io.File(context.filesDir, "incoming-share/$corruptId")
        assertTrue(directory.mkdirs())
        java.io.File(directory, "request.json").writeText("{not-json")
        try {
            assertTrue(store.loadResult(corruptId) is AndroidIncomingShareLoadResult.Corrupt)
            assertFalse(store.removeIfMatchingReleasable(corruptId, "not-a-fingerprint"))
            try {
                store.requireAvailable(corruptId)
                fail("Expected a typed corrupt-manifest failure")
            } catch (failure: CorruptIncomingShareManifestException) {
                assertEquals(corruptId, failure.requestId)
            }
            assertTrue(store.removeCorruptRecovery(corruptId))
        } finally {
            if (directory.exists()) assertTrue(store.remove(corruptId))
        }
    }

    private fun fixtureUri(name: String): Uri = Uri.Builder()
        .scheme("content")
        .authority(IncomingShareFixtureProvider.AUTHORITY)
        .appendPath(name)
        .build()
}

class IncomingShareFixtureProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val name = fixtureName(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(
                columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> name
                        OpenableColumns.SIZE -> payload(name).size.toLong()
                        else -> null
                    }
                },
            )
        }
    }

    override fun getType(uri: Uri): String =
        if (fixtureName(uri).endsWith(".txt")) "text/plain" else "application/octet-stream"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r")
        val name = fixtureName(uri)
        val pipe = ParcelFileDescriptor.createPipe()
        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                output.write(payload(name))
            }
        }.start()
        return pipe[0]
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun fixtureName(uri: Uri): String = requireNotNull(uri.lastPathSegment)
        .also { name -> require(name in setOf("one.txt", "large.bin")) }

    companion object {
        const val AUTHORITY = "dev.obiente.nextcloudnative.test.incoming-share-fixtures"

        fun payload(name: String): ByteArray = when (name) {
            "one.txt" -> "A shared document\n".encodeToByteArray()
            "large.bin" -> ByteArray(2 * 1024 * 1024) { index -> (index % 251).toByte() }
            else -> error("Unknown incoming-share fixture.")
        }
    }
}
