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
import java.nio.file.attribute.BasicFileAttributes

/**
 * One resident entry with the metadata the startup scan needs.
 *
 * The native enumeration already returns attributes, the reparse tag and the placeholder state
 * for every child. Carrying them avoids a second FindFirstFile and several stat calls per entry,
 * which made recovery of a large photo library exceed its startup deadline.
 */
internal class WindowsCloudLocalEntry(
    val path: Path,
    val directory: Boolean,
    val regularFile: Boolean,
    val symbolicLink: Boolean,
    inspect: () -> WindowsCloudPlaceholderInspection,
) {
    constructor(
        path: Path,
        directory: Boolean,
        regularFile: Boolean,
        symbolicLink: Boolean,
        inspection: WindowsCloudPlaceholderInspection,
    ) : this(path, directory, regularFile, symbolicLink, { inspection })

    /**
     * Native entries carry this from the enumeration. The Files-based fallback reads it on first
     * use, so a traversal that only needs directories never inspects placeholders.
     */
    val inspection: WindowsCloudPlaceholderInspection by lazy(inspect)
}

/** Classifies like Java NIO with NOFOLLOW_LINKS so native and Files-based scans agree exactly. */
internal fun windowsCloudLocalEntry(
    path: Path,
    fileAttributes: Int,
    reparseTag: Int,
    inspection: WindowsCloudPlaceholderInspection,
): WindowsCloudLocalEntry {
    val symbolicLink = fileAttributes and WinNT.FILE_ATTRIBUTE_REPARSE_POINT != 0 &&
        reparseTag == WinNT.IO_REPARSE_TAG_SYMLINK
    val directory = !symbolicLink && fileAttributes and WinNT.FILE_ATTRIBUTE_DIRECTORY != 0
    val other = !symbolicLink &&
        fileAttributes and (WinNT.FILE_ATTRIBUTE_DEVICE or WinNT.FILE_ATTRIBUTE_REPARSE_POINT) != 0
    return WindowsCloudLocalEntry(path, directory, !symbolicLink && !directory && !other, symbolicLink, inspection)
}

/** Builds an entry from Java NIO attributes; used for the root and by non-native test doubles. */
internal fun WindowsCloudFilesApi.localEntry(path: Path): WindowsCloudLocalEntry {
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    return WindowsCloudLocalEntry(
        path = path,
        directory = attributes.isDirectory,
        regularFile = attributes.isRegularFile,
        symbolicLink = attributes.isSymbolicLink,
        inspect = { inspectPlaceholder(path) },
    )
}

/** Files-based fallback; containment is checked before any attribute of a child is read. */
internal fun WindowsCloudFilesApi.localChildEntries(directory: Path): List<WindowsCloudLocalEntry> {
    val parent = directory.toAbsolutePath().normalize()
    return localChildren(directory).map { child ->
        require(child.toAbsolutePath().normalize().parent == parent)
        localEntry(child)
    }
}

internal fun WinBase.WIN32_FIND_DATA.windowsCloudInspection(
    placeholderState: (Pointer) -> Int,
): WindowsCloudPlaceholderInspection {
    Native.setLastError(0)
    val stateBits = placeholderState(pointer)
    return windowsCloudPlaceholderInspection(
        findSucceeded = true,
        win32Error = if (stateBits == CF_PLACEHOLDER_STATE_INVALID) Native.getLastError() else null,
        fileAttributes = dwFileAttributes,
        reparseTag = dwReserved0,
        placeholderStateBits = stateBits,
    )
}

/**
 * Enumerates resident children with their find data in one native call, without asking Cloud
 * Files to populate remote folders.
 */
internal fun windowsCloudLocalEntries(
    directory: Path,
    placeholderState: (Pointer) -> Int,
): List<WindowsCloudLocalEntry> {
    val data = WinBase.WIN32_FIND_DATA()
    val handle = LocalDirectoryApi.instance.FindFirstFileExW(
        WString(directory.toWindowsWin32Path().trimEnd('\\') + "\\*"),
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
                if (name != "." && name != "..") {
                    val inspection = data.windowsCloudInspection(placeholderState)
                    add(windowsCloudLocalEntry(directory.resolve(name), data.dwFileAttributes, data.dwReserved0, inspection))
                }
            } while (Kernel32.INSTANCE.FindNextFile(handle, data.pointer))
            val error = Native.getLastError()
            if (error != 18) throw WindowsCloudLocalScanException(error)
        }
    } finally {
        Kernel32.INSTANCE.FindClose(handle)
    }
}

/**
 * Lazy traversal closes each native enumeration before visiting children and never follows
 * symlinks. Enumerated metadata can be stale, so a directory is re-checked without following
 * links immediately before descent; otherwise a folder swapped for a link after its parent was
 * listed would expose the link target through lexically contained paths.
 */
internal fun WindowsCloudFilesApi.localEntryTree(root: Path): Sequence<WindowsCloudLocalEntry> = sequence {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return@sequence
    val rootEntry = localEntry(root)
    if (rootEntry.symbolicLink) return@sequence
    val pending = ArrayDeque<WindowsCloudLocalEntry>()
    pending.add(rootEntry)
    while (pending.isNotEmpty()) {
        check(!Thread.currentThread().isInterrupted) { "Local Cloud Files scan interrupted." }
        val entry = pending.removeLast()
        if (entry.symbolicLink) continue
        if (entry.directory && entry !== rootEntry && !isUnlinkedDirectory(entry.path)) continue
        yield(entry)
        if (entry.directory) {
            val parent = entry.path.toAbsolutePath().normalize()
            localEntries(entry.path).asReversed().forEach { child ->
                require(child.path.toAbsolutePath().normalize().parent == parent)
                pending.add(child)
            }
        }
    }
}

private fun isUnlinkedDirectory(path: Path): Boolean = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)

internal fun WindowsCloudFilesApi.localTree(root: Path): Sequence<Path> = localEntryTree(root).map { it.path }

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
