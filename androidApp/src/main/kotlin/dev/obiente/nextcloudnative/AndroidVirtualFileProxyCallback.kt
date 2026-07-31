package dev.obiente.nextcloudnative

import android.os.OperationCanceledException
import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import dev.obiente.nextcloudnative.app.NextcloudFileRangeSession
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Seekable, generation-pinned read-through descriptor for Android's DocumentsProvider.
 *
 * Reads are expanded to bounded blocks so media applications can seek without downloading the
 * entire object. Files small enough for the managed cache are assembled into a sparse staging file
 * and published only after every block belongs to the same ETag-pinned range session.
 */
internal class AndroidVirtualFileProxyCallback(
    private val source: NextcloudFileRangeSession,
    private val staging: File?,
    private val publishCompleteHydration: (File) -> Unit,
    private val blockSizeBytes: Int = DEFAULT_BLOCK_SIZE_BYTES,
) : ProxyFileDescriptorCallback() {
    private val cancelled = AtomicBoolean(false)
    private val size = source.size
    private val effectiveBlockSize = blockSizeBytes.also { require(it > 0) }
    private val blockCount = staging?.let {
        val count = (size + effectiveBlockSize - 1L) / effectiveBlockSize
        require(count <= Int.MAX_VALUE.toLong())
        count.toInt()
    } ?: 0
    private val hydratedBlocks = staging?.let { BooleanArray(blockCount) }
    private val stagedContent = staging?.let { file ->
        RandomAccessFile(file, "rw").also { random -> random.setLength(size) }
    }
    private var hydratedBlockCount = 0
    private var published = false
    private var released = false

    override fun onGetSize(): Long = size

    @Synchronized
    override fun onRead(offset: Long, requestedSize: Int, data: ByteArray): Int {
        if (released || cancelled.get()) throw OperationCanceledException("Virtual file read cancelled")
        if (offset < 0L || requestedSize < 0 || requestedSize > data.size) {
            throw ErrnoException("virtual file read", OsConstants.EINVAL)
        }
        if (offset >= size || requestedSize == 0) return 0
        val readLength = minOf(requestedSize.toLong(), size - offset).toInt()
        return try {
            val random = stagedContent
            val blocks = hydratedBlocks
            if (random == null || blocks == null) {
                val bytes = readRange(offset, readLength)
                bytes.copyInto(data)
                bytes.size
            } else {
                hydrateBlocks(offset, readLength, random, blocks)
                random.seek(offset)
                random.readFully(data, 0, readLength)
                publishIfComplete(random)
                readLength
            }
        } catch (cancelled: OperationCanceledException) {
            throw cancelled
        } catch (failure: ErrnoException) {
            throw failure
        } catch (failure: Throwable) {
            throw ErrnoException("virtual file read", OsConstants.EIO, failure)
        }
    }

    @Synchronized
    override fun onRelease() {
        if (released) return
        released = true
        cancelled.set(true)
        source.close()
        runCatching { stagedContent?.close() }
        if (!published) staging?.delete()
    }

    fun cancel() {
        cancelled.set(true)
        source.close()
    }

    private fun hydrateBlocks(
        offset: Long,
        length: Int,
        random: RandomAccessFile,
        blocks: BooleanArray,
    ) {
        val firstBlock = (offset / effectiveBlockSize).toInt()
        val lastBlock = ((offset + length - 1L) / effectiveBlockSize).toInt()
        for (block in firstBlock..lastBlock) {
            if (blocks[block]) continue
            if (cancelled.get()) throw OperationCanceledException("Virtual file read cancelled")
            val blockOffset = block.toLong() * effectiveBlockSize
            val blockLength = minOf(effectiveBlockSize.toLong(), size - blockOffset).toInt()
            val bytes = readRange(blockOffset, blockLength)
            random.seek(blockOffset)
            random.write(bytes)
            blocks[block] = true
            hydratedBlockCount += 1
        }
    }

    private fun readRange(offset: Long, length: Int): ByteArray =
        runBlocking(Dispatchers.IO) { source.read(offset, length) }.also { bytes ->
            check(bytes.size == length) { "The virtual file range was incomplete." }
        }

    private fun publishIfComplete(random: RandomAccessFile) {
        if (published || hydratedBlockCount != blockCount) return
        random.fd.sync()
        val target = staging ?: return
        publishCompleteHydration(target)
        published = true
    }

    private companion object {
        const val DEFAULT_BLOCK_SIZE_BYTES = 1024 * 1024
    }
}
