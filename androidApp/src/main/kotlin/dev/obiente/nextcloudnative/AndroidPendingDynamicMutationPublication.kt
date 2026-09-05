package dev.obiente.nextcloudnative

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Publishes a pre-synced mutation marker before its non-idempotent request may start. */
internal fun publishAndroidPendingMutation(temporary: File, target: File) {
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
        copyAndSyncAndroidPendingMutation(temporary, target)
    }
}

internal fun copyAndSyncAndroidPendingMutation(temporary: File, target: File) {
    require(temporary.isFile)
    require(temporary.parentFile == target.parentFile)
    FileInputStream(temporary).use { input ->
        FileOutputStream(target).use { output ->
            input.copyTo(output)
            output.fd.sync()
        }
    }
    check(temporary.delete()) { "Could not clear the published pending mutation staging file." }
}
