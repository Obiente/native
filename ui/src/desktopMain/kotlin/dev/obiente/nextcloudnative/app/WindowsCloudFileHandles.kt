package dev.obiente.nextcloudnative.app

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.Path

internal fun checkWindowsCloudHResult(result: Int, operation: String) {
    if (result < 0) throw WindowsCloudFilesOperationException(operation, result)
}

/** Opens a plain Win32 handle to an existing local entry; folders need backup semantics. */
internal inline fun <T> withWindowsFileHandle(
    path: Path,
    write: Boolean,
    exclusive: Boolean = false,
    block: (WinNT.HANDLE) -> T,
): T {
    val handle = Kernel32.INSTANCE.CreateFile(
        path.toWindowsWin32Path(),
        if (write) WinNT.GENERIC_WRITE else WinNT.FILE_READ_ATTRIBUTES,
        if (exclusive) 0 else WinNT.FILE_SHARE_READ or WinNT.FILE_SHARE_WRITE or WinNT.FILE_SHARE_DELETE,
        null,
        WinNT.OPEN_EXISTING,
        WinNT.FILE_FLAG_BACKUP_SEMANTICS,
        null,
    )
    check(handle != WinBase.INVALID_HANDLE_VALUE) { "Could not open the Windows Cloud Files placeholder." }
    return try {
        block(handle)
    } finally {
        Kernel32.INSTANCE.CloseHandle(handle)
    }
}

/**
 * Cloud Files updates use a protected handle so Explorer can break the oplock cleanly instead
 * of racing a background CreateFile call with a transient sharing violation.
 *
 * Placeholder update and in-sync APIs take a Win32 handle and reject the protected handle itself
 * with E_HANDLE. The Win32 handle is borrowed under a reference, which fails once an oplock break
 * has invalidated the protected handle.
 */
internal inline fun <T> CldApi.withCloudFileHandle(
    path: Path,
    write: Boolean,
    exclusive: Boolean = false,
    block: (WinNT.HANDLE) -> T,
): T {
    val protectedHandle = WinNT.HANDLEByReference()
    checkWindowsCloudHResult(
        CfOpenFileWithOplock(WString(path.toWindowsWin32Path()), windowsCloudOpenFileFlags(write, exclusive), protectedHandle),
        "open a Windows Cloud Files placeholder for update",
    )
    try {
        check(CfReferenceProtectedHandle(protectedHandle.value).toInt() != 0) {
            "The Windows Cloud Files placeholder handle was invalidated by an oplock break."
        }
        try {
            return block(CfGetWin32HandleFromProtectedHandle(protectedHandle.value))
        } finally {
            CfReleaseProtectedHandle(protectedHandle.value)
        }
    } finally {
        CfCloseHandle(protectedHandle.value)
    }
}

/** Reads the content stamp from an open handle rather than from possibly stale directory data. */
internal fun windowsCloudLocalContentStamp(handle: WinNT.HANDLE): WindowsCloudLocalContentStamp {
    // BY_HANDLE_FILE_INFORMATION: attributes, three FILETIMEs, volume serial, size, links, index.
    val info = Memory(BY_HANDLE_FILE_INFORMATION_BYTES).apply { clear() }
    check(FileInformationApi.instance.GetFileInformationByHandle(handle, info)) {
        "Could not read Windows file information (Windows error ${Native.getLastError()})."
    }
    fun dwordPair(highOffset: Long, lowOffset: Long): Long =
        info.getInt(highOffset).toLong().shl(32) or (info.getInt(lowOffset).toLong() and 0xffff_ffffL)
    return WindowsCloudLocalContentStamp(
        size = dwordPair(highOffset = 32, lowOffset = 36),
        lastWriteTime = dwordPair(highOffset = 24, lowOffset = 20),
        fileId = dwordPair(highOffset = 44, lowOffset = 48),
    )
}

private const val BY_HANDLE_FILE_INFORMATION_BYTES = 52L

private interface FileInformationApi : StdCallLibrary {
    fun GetFileInformationByHandle(handle: WinNT.HANDLE, information: Pointer): Boolean

    companion object {
        val instance: FileInformationApi by lazy { Native.load("kernel32", FileInformationApi::class.java) }
    }
}
