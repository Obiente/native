package dev.obiente.nextcloudnative

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        )
        try {
            assertEquals(listOf("one.txt"), single.files.map(AndroidIncomingShareFile::displayName))
            assertArrayEquals(
                IncomingShareFixtureProvider.payload("one.txt"),
                store.stagedFile(single.id, single.files.single()).readBytes(),
            )
            assertEquals(single, AndroidIncomingShareStore(context).load(single.id))
        } finally {
            assertTrue(store.remove(single.id))
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
