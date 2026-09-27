package dev.obiente.nextcloudnative.app

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Enumerates resident directory entries without asking Cloud Files to populate remote folders. */
internal fun windowsCloudLocalChildren(directory: Path): List<Path> {
    val data = WinBase.WIN32_FIND_DATA()
    val handle = LocalDirectoryApi.instance.FindFirstFileExW(
        WString(directory.toAbsolutePath().toString().trimEnd('\\', '/') + "\\*"),
        1, data.pointer, 0, null, 4, // FindExInfoBasic, FindExSearchNameMatch, ON_DISK_ENTRIES_ONLY
    )
    if (handle == WinBase.INVALID_HANDLE_VALUE) {
        val error = Native.getLastError()
        if (error == 2) return emptyList() // Empty directory, not an inaccessible or missing root.
        throw WindowsCloudLocalScanException(error)
    }
    return try {
        buildList {
            do {
                data.read()
                val name = Native.toString(data.cFileName)
                if (name != "." && name != "..") add(directory.resolve(name))
            } while (Kernel32.INSTANCE.FindNextFile(handle, data.pointer))
            val error = Native.getLastError()
            if (error != 18) throw WindowsCloudLocalScanException(error)
        }
    } finally {
        Kernel32.INSTANCE.FindClose(handle)
    }
}

/** Lazy traversal closes each native enumeration before visiting children and never follows symlinks. */
internal fun WindowsCloudFilesApi.localTree(root: Path): Sequence<Path> = sequence {
    val pending = ArrayDeque<Path>()
    pending.add(root)
    while (pending.isNotEmpty()) {
        check(!Thread.currentThread().isInterrupted) { "Local Cloud Files scan interrupted." }
        val path = pending.removeLast()
        if (Files.isSymbolicLink(path)) continue
        yield(path)
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            localChildren(path).asReversed().forEach { child ->
                require(child.toAbsolutePath().normalize().parent == path.toAbsolutePath().normalize())
                pending.add(child)
            }
        }
    }
}

private interface LocalDirectoryApi : StdCallLibrary {
    fun FindFirstFileExW(
        pattern: WString, informationLevel: Int, data: Pointer,
        searchOperation: Int, filter: Pointer?, flags: Int,
    ): WinNT.HANDLE

    companion object {
        val instance: LocalDirectoryApi by lazy { Native.load("kernel32", LocalDirectoryApi::class.java) }
    }
}

internal class WindowsCloudLocalScanException(error: Int) : WindowsCloudFilesStartupRecoveryException(
    "Could not enumerate local Cloud Files entries (Windows error $error).",
)
