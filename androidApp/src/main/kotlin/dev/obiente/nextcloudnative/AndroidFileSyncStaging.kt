package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.stagedFileTransferLimit
import java.io.File

internal inline fun <T> withAndroidFileSyncStagingFile(
    stagingRoot: File,
    prefix: String,
    block: (File) -> T,
): T {
    check(stagingRoot.isDirectory || stagingRoot.mkdirs()) { "Could not create sync staging storage." }
    val file = File.createTempFile("$prefix-", ".tmp", stagingRoot)
    return try {
        block(file)
    } finally {
        file.delete()
    }
}

internal fun androidFileSyncStagingTransferLimit(stagingRoot: File, declaredByteCount: Long?): Long {
    check(stagingRoot.isDirectory || stagingRoot.mkdirs()) { "Could not create sync staging storage." }
    return stagedFileTransferLimit(
        availableBytes = stagingRoot.usableSpace.coerceAtLeast(0L),
        declaredByteCount = declaredByteCount,
    )
}
