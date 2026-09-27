package dev.obiente.nextcloudnative

import java.io.File

internal fun deleteAndroidAccountPrivateCache(root: File, accountId: String) {
    require(accountId.length == 32 && accountId.all { character ->
        character in '0'..'9' || character in 'a'..'f'
    }) { "Private cache account identity is invalid." }
    val canonicalRoot = root.canonicalFile
    val accountDirectory = File(canonicalRoot, accountId).canonicalFile
    check(accountDirectory.parentFile == canonicalRoot) { "Unsafe private account cache path." }
    check(!accountDirectory.exists() || accountDirectory.deleteRecursively() && !accountDirectory.exists()) {
        "Could not remove this account's private cache."
    }
}
