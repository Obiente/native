package dev.obiente.nextcloudnative.app

import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import java.nio.file.Path

private const val FILE_ATTRIBUTE_PINNED = 0x0008_0000

/**
 * Only an unpinned, in-sync file placeholder may release its local content. Dirty content is the
 * only copy of a local edit and pinned content is kept on request, so neither is a candidate.
 *
 * The decision uses native find data: a connected file placeholder is a cloud reparse point that
 * Java NIO reports as "other", never as a regular file.
 */
internal fun windowsCloudDehydratable(entry: WindowsCloudLocalEntry, fileAttributes: Int): Boolean =
    entry.file &&
        fileAttributes and FILE_ATTRIBUTE_PINNED == 0 &&
        entry.inspection.state == WindowsCloudPlaceholderEntryState.InSync

/**
 * Windows refuses to dehydrate content that became dirty, pinned or opened by another application
 * after [windowsCloudDehydratable] accepted it. The content stays local and nothing is freed.
 */
internal fun isWindowsCloudDehydrationRefusedResult(result: Int): Boolean =
    result == 0x80070179.toInt() || // HRESULT_FROM_WIN32(ERROR_CLOUD_FILE_NOT_IN_SYNC)
        result == 0x80070187.toInt() || // HRESULT_FROM_WIN32(ERROR_CLOUD_FILE_IN_USE)
        result == 0x80070188.toInt() // HRESULT_FROM_WIN32(ERROR_CLOUD_FILE_PINNED)

/** Bytes allocated on disk; a dehydrated placeholder keeps its logical size but allocates none. */
internal fun KernelFileApi.allocatedFileBytes(path: Path): Long {
    val high = IntByReference()
    val low = GetCompressedFileSizeW(WString(path.toWindowsWin32Path()), high)
    if (low == -1 && Native.getLastError() != 0) return 0L
    return ((high.value.toLong() and 0xffff_ffffL) shl 32) or (low.toLong() and 0xffff_ffffL)
}
