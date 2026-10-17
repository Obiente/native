package dev.obiente.nextcloudnative.app

import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinNT
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
 * Windows keeps content that became dirty, pinned or opened by another application after
 * [windowsCloudDehydratable] accepted it. The content stays local and nothing is freed.
 */
internal fun isWindowsCloudDehydrationRefusedResult(result: Int): Boolean =
    result == 0x80070020.toInt() || // HRESULT_FROM_WIN32(ERROR_SHARING_VIOLATION): no exclusive oplock
        result == 0x80070179.toInt() || // HRESULT_FROM_WIN32(ERROR_CLOUD_FILE_NOT_IN_SYNC)
        result == 0x80070187.toInt() || // HRESULT_FROM_WIN32(ERROR_CLOUD_FILE_IN_USE)
        result == 0x80070188.toInt() || // HRESULT_FROM_WIN32(ERROR_CLOUD_FILE_PINNED)
        result == 0x80070323.toInt() // HRESULT_FROM_WIN32(ERROR_OPLOCK_HANDLE_CLOSED)

/**
 * CfDehydratePlaceholder requires an exclusive handle, and Microsoft recommends an exclusive
 * oplock so another application's open breaks it instead of failing. Returns false when Windows
 * keeps the content, including when the oplock cannot be taken or breaks before dehydration.
 */
internal fun CldApi.dehydrateExclusively(path: Path): Boolean {
    val protectedHandle = WinNT.HANDLEByReference()
    val flags = windowsCloudOpenFileFlags(write = false, exclusive = true)
    val opened = CfOpenFileWithOplock(WString(path.toWindowsWin32Path()), flags, protectedHandle)
    if (isWindowsCloudDehydrationRefusedResult(opened)) return false
    checkWindowsCloudHResult(opened, "open a Windows Cloud Files placeholder for dehydration")
    try {
        // A reference fails once another application's open has broken the oplock.
        if (CfReferenceProtectedHandle(protectedHandle.value).toInt() == 0) return false
        try {
            val handle = CfGetWin32HandleFromProtectedHandle(protectedHandle.value)
            val result = CfDehydratePlaceholder(handle, 0L, -1L, 0, null)
            if (isWindowsCloudDehydrationRefusedResult(result)) return false
            checkWindowsCloudHResult(result, "dehydrate a Windows Cloud Files placeholder")
            return true
        } finally {
            CfReleaseProtectedHandle(protectedHandle.value)
        }
    } finally {
        CfCloseHandle(protectedHandle.value)
    }
}

/**
 * Dehydrates the files an eviction plan selected, rechecking each at execution time. A file that
 * another revision replaced, or that was pinned, edited or resized since planning, is kept.
 * Returns the bytes actually released.
 */
internal fun WindowsCloudFilesApi.dehydratePlannedEvictions(
    evictions: List<VirtualFileEviction>,
    currentFile: (relativePath: String) -> Pair<WindowsCloudFileIdentity, Path>?,
): Long = evictions.sumOf { eviction ->
    val (identity, path) = currentFile(eviction.key.relativePath) ?: return@sumOf 0L
    val unchanged = identity.remoteRevision == eviction.expectedLocalRevision &&
        !isPinned(path) &&
        placeholderState(path) == WindowsCloudPlaceholderState.InSync &&
        allocatedBytes(path) == eviction.sizeBytes
    if (unchanged) dehydrate(path) else 0L
}

/** Bytes allocated on disk; a dehydrated placeholder keeps its logical size but allocates none. */
internal fun KernelFileApi.allocatedFileBytes(path: Path): Long {
    val high = IntByReference()
    val low = GetCompressedFileSizeW(WString(path.toWindowsWin32Path()), high)
    if (low == -1 && Native.getLastError() != 0) return 0L
    return ((high.value.toLong() and 0xffff_ffffL) shl 32) or (low.toLong() and 0xffff_ffffL)
}
