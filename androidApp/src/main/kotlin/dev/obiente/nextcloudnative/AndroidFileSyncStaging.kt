package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.stagedFileTransferLimit
import java.io.File

internal fun androidFileSyncAccountStagingRoot(stagingRoot: File, accountId: String): File {
    require(accountId.matches(ANDROID_FILE_SYNC_STAGING_ACCOUNT_ID))
    return File(stagingRoot, accountId)
}

internal fun removeAndroidFileSyncAccountStaging(stagingRoot: File, accountId: String) {
    val accountRoot = androidFileSyncAccountStagingRoot(stagingRoot, accountId)
    if (!accountRoot.exists()) return
    check(accountRoot.isDirectory) { "The account sync staging storage is unavailable." }
    accountRoot.listFiles()?.forEach { staged ->
        check(staged.isFile && staged.name.matches(ANDROID_FILE_SYNC_STAGING_FILE)) {
            "The account sync staging storage contains an unexpected entry."
        }
        check(staged.delete() || !staged.exists()) { "Could not clear account sync staging storage." }
    } ?: error("Could not inspect account sync staging storage.")
    check(accountRoot.delete() || !accountRoot.exists()) { "Could not clear account sync staging storage." }
}

internal fun removeLegacyAndroidFileSyncStaging(stagingRoot: File) {
    if (!stagingRoot.exists()) return
    check(stagingRoot.isDirectory) { "The sync staging storage is unavailable." }
    stagingRoot.listFiles()?.filter(File::isFile)?.forEach { staged ->
        check(staged.name.matches(ANDROID_FILE_SYNC_STAGING_FILE)) {
            "The sync staging storage contains an unexpected file."
        }
        check(staged.delete() || !staged.exists()) { "Could not clear legacy sync staging storage." }
    } ?: error("Could not inspect sync staging storage.")
}

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

private val ANDROID_FILE_SYNC_STAGING_ACCOUNT_ID = Regex("[0-9a-f]{32}(?:[0-9a-f]{32})?")
private val ANDROID_FILE_SYNC_STAGING_FILE = Regex("(?:upload|keep-local|keep-remote)-[A-Za-z0-9._-]+\\.tmp")

internal fun androidFileSyncStagingTransferLimit(stagingRoot: File, declaredByteCount: Long?): Long {
    check(stagingRoot.isDirectory || stagingRoot.mkdirs()) { "Could not create sync staging storage." }
    return stagedFileTransferLimit(
        availableBytes = stagingRoot.usableSpace.coerceAtLeast(0L),
        declaredByteCount = declaredByteCount,
    )
}
