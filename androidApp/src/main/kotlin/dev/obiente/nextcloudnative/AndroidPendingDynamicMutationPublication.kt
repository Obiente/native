package dev.obiente.nextcloudnative

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Publishes a pre-synced mutation marker before its non-idempotent request may start. */
internal fun publishAndroidPendingMutation(
    temporary: File, target: File,
    syncDirectory: (File) -> Unit = ::syncAndroidPendingMutationDirectory,
) {
    require(temporary.isFile)
    require(temporary.parentFile == target.parentFile)
    try {
        Files.move(
            temporary.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        copyAndSyncAndroidPendingMutation(temporary, target, syncDirectory)
        return
    }
    syncDirectory(requireNotNull(target.parentFile))
}

internal fun copyAndSyncAndroidPendingMutation(
    temporary: File, target: File,
    syncDirectory: (File) -> Unit = ::syncAndroidPendingMutationDirectory,
) {
    require(temporary.isFile)
    require(temporary.parentFile == target.parentFile)
    FileInputStream(temporary).use { input ->
        FileOutputStream(target).use { output ->
            input.copyTo(output)
            output.fd.sync()
        }
    }
    syncDirectory(requireNotNull(target.parentFile))
    check(temporary.delete()) { "Could not clear the published pending mutation staging file." }
}

private fun syncAndroidPendingMutationDirectory(directory: File) {
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
}
