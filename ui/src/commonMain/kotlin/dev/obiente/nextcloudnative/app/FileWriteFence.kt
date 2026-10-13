package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.mutableStateMapOf

/**
 * The account-scoped fence every surface that changes account files consults before it writes,
 * so no surface can create, move, or change an item while a delete that covers it is unverified
 * or needs a refresh. Surfaces receive only this interface.
 */
fun interface FileWriteFence {
    /** Why a write that creates or changes [path] must wait, or null when it may proceed. */
    fun writeBlock(path: String): FileWriteBlock?

    /**
     * Why creating a new item directly inside [folder] must wait: a delete of the folder or one of
     * its parents is unverified or needs a refresh. A delete of an existing child does not block a
     * new sibling. The default is the stricter [writeBlock] of the folder itself.
     */
    fun newChildBlock(folder: String): FileWriteBlock? = writeBlock(folder)
}

/** A write refused by a [FileWriteFence]; its message is the block's typed reason. */
class FileWriteBlockedException(val block: FileWriteBlock) : IllegalStateException(block.message)

/** Why writes to a Files item must wait. User-facing copy derives only from this type. */
sealed interface FileWriteBlock {
    val message: String

    /** A delete of the item, one of its folders, or its contents is not verified yet. */
    data object DeleteRunning : FileWriteBlock {
        override val message = "Wait until the delete of this item or its folder finishes."
    }

    /** A finished delete of [blockedPath] needs a server listing of its folder first. */
    data class RefreshRequired(val blockedPath: String) : FileWriteBlock {
        val folder: String get() = blockedPath.substringBeforeLast('/', missingDelimiterValue = "")

        override val message: String
            get() = "Open or refresh ${folder.ifEmpty { "your Nextcloud root" }} before changing " +
                "${blockedPath.substringAfterLast('/')} or anything inside it."
    }
}

/** One remote resource in one account. */
internal data class FileDeleteResourceKey(val account: NextcloudAccountId, val path: String) {
    val parentPath: String get() = path.substringBeforeLast('/', missingDelimiterValue = "")

    fun overlaps(other: FileDeleteResourceKey): Boolean = account == other.account && (
        path == other.path || path.startsWith("${other.path}/") || other.path.startsWith("$path/")
        )

    /** True when [other] is this resource or lies inside it. */
    fun contains(other: FileDeleteResourceKey): Boolean =
        account == other.account && (path == other.path || other.path.startsWith("$path/"))
}

/** Account-scoped Files deletes that have not reached a verified outcome. */
internal class FileDeleteInFlight {
    private val pending = mutableStateMapOf<FileDeleteResourceKey, Long>()
    private var nextToken = 0L

    operator fun contains(key: FileDeleteResourceKey): Boolean = key in pending

    /** True when an unverified delete targets [key], one of its folders, or one of its children. */
    fun blocks(key: FileDeleteResourceKey): Boolean = pending.keys.any { it.overlaps(key) }

    /** True when an unverified delete targets [key] itself or one of its folders. */
    fun covers(key: FileDeleteResourceKey): Boolean = pending.keys.any { it.contains(key) }

    fun claim(key: FileDeleteResourceKey): Long? {
        if (blocks(key)) return null
        return (++nextToken).also { pending[key] = it }
    }

    fun release(key: FileDeleteResourceKey, token: Long) {
        if (pending[key] == token) pending.remove(key)
    }
}

/**
 * The write blocks of one account in this process: unverified deletes, and finished deletes whose
 * outcome needs a server listing of the item's folder. Every surface of the account reads the same
 * instance, including ones outside the composition that started the delete, such as another
 * activity. State is held in memory only: a new process starts without blocks.
 */
class AccountFileWriteFence internal constructor(val account: NextcloudAccountId) : FileWriteFence {
    private class RetryBlock(val message: String, val recordedAt: Long)

    internal val inFlight = FileDeleteInFlight()
    private val retryBlocks = mutableStateMapOf<FileDeleteResourceKey, RetryBlock>()
    private var sequence = 0L

    override fun writeBlock(path: String): FileWriteBlock? =
        FileWriteBlock.DeleteRunning.takeIf { inFlight.blocks(FileDeleteResourceKey(account, path)) } ?: refreshBlockFor(path)

    /**
     * Why a write to [path] must wait for a folder refresh after a finished delete of it, a parent,
     * or a child, or null. The old path may now name a replacement item, so path-based writes wait too.
     */
    internal fun refreshBlockFor(path: String): FileWriteBlock.RefreshRequired? {
        val key = FileDeleteResourceKey(account, path)
        return retryBlocks.keys.firstOrNull { it.overlaps(key) }?.let { FileWriteBlock.RefreshRequired(it.path) }
    }

    override fun newChildBlock(folder: String): FileWriteBlock? {
        val key = FileDeleteResourceKey(account, folder)
        if (inFlight.covers(key)) return FileWriteBlock.DeleteRunning
        return retryBlocks.keys.firstOrNull { it.contains(key) }?.let { FileWriteBlock.RefreshRequired(it.path) }
    }

    internal fun retryBlockMessage(key: FileDeleteResourceKey): String? = retryBlocks[key]?.message

    internal fun hasRetryBlock(key: FileDeleteResourceKey): Boolean = key in retryBlocks

    /** Records [key]'s finished delete: [message] keeps it blocked until a later listing, null lifts it. */
    internal fun recordOutcome(key: FileDeleteResourceKey, message: String?) {
        if (message == null) retryBlocks.remove(key) else retryBlocks[key] = RetryBlock(message, ++sequence)
    }

    /**
     * Reads the folder at [path]. A server listing lifts the retry blocks of its items that were
     * recorded before the read started, never ones recorded while it ran.
     */
    internal suspend fun readFolder(path: String, read: suspend () -> NextcloudFileListing): NextcloudFileListing {
        val startedAfter = sequence
        val listing = read()
        if (listing.source == NextcloudFileListingSource.Network) {
            retryBlocks.filter { (key, block) -> key.parentPath == path && block.recordedAt <= startedAfter }
                .keys.forEach(retryBlocks::remove)
        }
        return listing
    }
}

/** Exposes the process-level fence owner; implementations return the one shared registry of their process. */
interface FileWriteFencePlatformServices {
    val fileWriteFences: FileWriteFenceRegistry
}

/**
 * Process-level owner of [AccountFileWriteFence]s, keyed by the non-secret local account identity.
 * Each platform's process-scoped service owner holds one registry, so the main window, other
 * activities, and pickers of the same account share blocks. Sign-out and account removal call
 * [removeAccount], which drops that account's blocks with its other private state.
 */
class FileWriteFenceRegistry {
    private val lock = DynamicNativeMemoryCacheLock()
    private val accounts = mutableMapOf<String, AccountFileWriteFence>()

    fun account(account: NextcloudAccountId): AccountFileWriteFence = lock.withLock {
        accounts.getOrPut(account.storageKey) { AccountFileWriteFence(account) }
    }

    fun removeAccount(accountStorageKey: String) {
        lock.withLock { accounts.remove(accountStorageKey) }
    }
}
