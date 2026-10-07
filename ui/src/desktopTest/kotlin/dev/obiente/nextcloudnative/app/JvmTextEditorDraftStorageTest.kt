package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.IOException
import java.nio.file.Files
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmTextEditorDraftStorageTest {
    private val account = "a".repeat(64)
    private val otherAccount = "b".repeat(64)
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val draft = TextEditorDraft("original private text", "changed private text", "revision", true)

    @Test
    fun `recovery survives a new store and keeps private content encrypted`() = runBlocking {
        withRoot { root ->
            JvmTextEditorDraftStorage(root, { key }).bind(account, "/private.txt").save(draft)
            val file = root.listFiles()!!.single()
            assertFalse(file.name.contains("private"))
            assertFalse(file.readBytes().decodeToString().contains(draft.text))
            assertEquals(draft, JvmTextEditorDraftStorage(root, { key }).bind(account, "/private.txt").load())
            assertNull(JvmTextEditorDraftStorage(root, { key }).bind(otherAccount, "/private.txt").load())
        }
    }

    @Test
    fun `ciphertext is bound to account and resource identity`() = runBlocking {
        withRoot { root ->
            val storage = JvmTextEditorDraftStorage(root, { key })
            storage.bind(account, "/one.txt").save(draft)
            val original = root.listFiles()!!.single().readBytes()
            for ((targetAccount, targetPath) in listOf(otherAccount to "/one.txt", account to "/two.txt")) {
                val existing = root.listFiles()!!.toSet()
                val target = storage.bind(targetAccount, targetPath)
                target.save(draft)
                root.listFiles()!!.single { it !in existing }.writeBytes(original)
                assertFailsWith<AEADBadTagException> { target.load() }
            }
        }
    }

    @Test
    fun `tamper truncation and unknown versions preserve unreadable recovery`() = runBlocking {
        withRoot { root ->
            val storage = JvmTextEditorDraftStorage(root, { key })
            val bound = storage.bind(account, "/one.txt")
            bound.save(draft)
            val file = root.listFiles()!!.single()
            val original = file.readBytes()
            val tampered = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            file.writeBytes(tampered)
            assertFailsWith<AEADBadTagException> { bound.load() }
            assertFailsWith<AEADBadTagException> { bound.save(draft.copy(text = "replacement")) }
            assertTrue(file.readBytes().contentEquals(tampered))
            file.writeBytes(original.copyOf(8))
            assertFailsWith<IllegalStateException> { bound.load() }
            file.writeBytes(original.copyOf().also { it[0] = 2 })
            assertFailsWith<IllegalStateException> { bound.load() }
        }
    }

    @Test
    fun `oversized edits and publication failure preserve previous recovery`() = runBlocking {
        withRoot { root ->
            val bound = JvmTextEditorDraftStorage(root, { key }).bind(account, "/one.txt")
            bound.save(draft)
            assertFailsWith<IllegalArgumentException> {
                bound.save(draft.copy(text = "x".repeat(MAX_EDITABLE_TEXT_BYTES.toInt() + 1)))
            }
            assertFailsWith<IllegalArgumentException> { bound.save(draft.copy(etag = "x".repeat(8193))) }
            val failing = JvmTextEditorDraftStorage(root, { key }, publish = { _, _ -> throw IOException("synthetic") })
            assertFailsWith<IOException> { failing.bind(account, "/one.txt").save(draft.copy(text = "replacement")) }
            assertEquals(draft, bound.load())
            assertEquals(1, root.listFiles()!!.size)
        }
    }

    @Test
    fun `account retirement fences old bindings across store instances and keeps other accounts`() = runBlocking {
        withRoot { root ->
            val storage = JvmTextEditorDraftStorage(root, { key })
            val stale = storage.bind(account, "/one.txt")
            stale.save(draft)
            val other = storage.bind(otherAccount, "/one.txt")
            other.save(draft)
            JvmTextEditorDraftStorage(root, { key }).removeAccount(account)
            assertFailsWith<IllegalStateException> { stale.load() }
            assertFailsWith<IllegalStateException> { stale.save(draft) }
            assertFailsWith<IllegalStateException> { stale.remove() }
            assertNull(storage.bind(account, "/one.txt").load())
            assertEquals(draft, other.load())
            val fresh = storage.bind(account, "/one.txt")
            fresh.save(draft)
            assertFailsWith<IllegalStateException> { stale.remove() }
            assertEquals(draft, fresh.load())
        }
    }

    @Test
    fun `account cleanup removes interrupted publication files without touching another account`() = runBlocking {
        withRoot { root ->
            val ownTemporary = File(root, "${account}_interrupted.tmp").apply { writeBytes(byteArrayOf(1, 2)) }
            val otherTemporary = File(root, "${otherAccount}_interrupted.tmp").apply { writeBytes(byteArrayOf(3, 4)) }
            JvmTextEditorDraftStorage(root, { key }).removeAccount(account)
            assertFalse(ownTemporary.exists())
            assertTrue(otherTemporary.readBytes().contentEquals(byteArrayOf(3, 4)))
        }
    }

    @Test
    fun `capacity refuses a new draft without evicting recovery`() = runBlocking {
        withRoot { root ->
            val storage = JvmTextEditorDraftStorage(root, { key })
            repeat(32) { storage.bind(account, "/$it.txt").save(draft) }
            assertFailsWith<IllegalStateException> { storage.bind(account, "/overflow.txt").save(draft) }
            storage.bind(account, "/0.txt").save(draft.copy(text = "updated"))
            assertEquals(32, root.listFiles()!!.size)
            assertEquals("updated", storage.bind(account, "/0.txt").load()!!.text)
        }
    }

    @Test
    fun `directory symlinks and Windows junctions cannot redirect draft storage or cleanup`() = runBlocking {
        withRoot { parent ->
            val target = File(parent, "target").apply { mkdir() }
            val link = File(parent, "alias")
            if (System.getProperty("os.name").startsWith("Windows")) {
                val process = ProcessBuilder("cmd", "/c", "mklink", "/J", link.path, target.path)
                    .redirectErrorStream(true).start()
                process.inputStream.use { it.readBytes() }
                check(process.waitFor() == 0) { "Could not prepare junction fixture." }
            } else {
                Files.createSymbolicLink(link.toPath(), target.toPath())
            }
            try {
                val storage = JvmTextEditorDraftStorage(link, { key })
                val bound = storage.bind(account, "/one.txt")
                assertFailsWith<IllegalStateException> { bound.save(draft) }
                assertFailsWith<IllegalStateException> { bound.load() }
                assertFailsWith<IllegalStateException> { storage.removeAccount(account) }
                assertTrue(target.listFiles()!!.isEmpty())
            } finally {
                Files.deleteIfExists(link.toPath())
            }
        }
    }

    private suspend fun withRoot(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("text-draft-contract-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
