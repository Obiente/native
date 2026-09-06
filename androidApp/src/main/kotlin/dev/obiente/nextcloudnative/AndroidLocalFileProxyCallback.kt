package dev.obiente.nextcloudnative

import android.os.OperationCanceledException
import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/** Revocable, seekable access to an exact local cache generation. */
internal class AndroidLocalFileProxyCallback(
    content: File,
    private val accessAllowed: () -> Boolean,
    private val onReleased: () -> Unit,
) : ProxyFileDescriptorCallback() {
    private val source = RandomAccessFile(content, "r")
    private val cancelled = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val size = source.length()

    override fun onGetSize(): Long {
        requireAccess()
        return size
    }

    @Synchronized
    override fun onRead(offset: Long, requestedSize: Int, data: ByteArray): Int {
        requireAccess()
        if (offset < 0L || requestedSize < 0 || requestedSize > data.size) {
            throw ErrnoException("local handoff read", OsConstants.EINVAL)
        }
        if (offset >= size || requestedSize == 0) return 0
        return try {
            val readLength = minOf(requestedSize.toLong(), size - offset).toInt()
            source.seek(offset)
            source.readFully(data, 0, readLength)
            readLength
        } catch (cancelled: OperationCanceledException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (cancelled.get() || !accessAllowed()) {
                throw OperationCanceledException("External file handoff revoked")
            }
            throw ErrnoException("local handoff read", OsConstants.EIO, failure)
        }
    }

    @Synchronized
    override fun onRelease() {
        if (!released.compareAndSet(false, true)) return
        cancelled.set(true)
        try {
            runCatching(source::close)
        } finally {
            onReleased()
        }
    }

    fun cancel() {
        cancelled.set(true)
        runCatching(source::close)
    }

    private fun requireAccess() {
        if (released.get() || cancelled.get() || !accessAllowed()) {
            throw OperationCanceledException("External file handoff revoked")
        }
    }
}
