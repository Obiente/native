package dev.obiente.nextcloudnative

import android.content.Context
import android.content.SharedPreferences
import dev.obiente.nextcloudnative.app.DurableMutationRecoveryKind
import dev.obiente.nextcloudnative.app.isSafePendingMutationId
import java.io.File

internal class AndroidAccountMutationRecoveryCleanup(
    private val preferences: SharedPreferences,
    pendingDynamicMutationDirectory: File,
) {
    private val pendingDynamicMutationDirectory = pendingDynamicMutationDirectory.canonicalFile

    constructor(context: Context) : this(
        preferences = context.applicationContext.getSharedPreferences("nextcloud_native", Context.MODE_PRIVATE),
        pendingDynamicMutationDirectory = File(context.applicationContext.filesDir, "mutations/dynamic-v1"),
    )

    fun clearDurableRecoveries(accountScope: String) {
        require(accountScope.isCanonicalAndroidMutationAccountScope()) {
            "The durable mutation account identity is invalid."
        }
        synchronized(androidDurableMutationRecoveryLock) {
            val keys = DurableMutationRecoveryKind.entries.map { kind ->
                androidDurableMutationRecoveryKey(accountScope, kind)
            }
            if (keys.none(preferences::contains)) return@synchronized
            val editor = preferences.edit()
            keys.forEach(editor::remove)
            check(editor.commit() && keys.none(preferences::contains)) {
                "Could not clear this account's durable mutation recovery."
            }
        }
    }

    fun clearPendingDynamicMutations(accountIdentity: String) {
        require(accountIdentity.isCanonicalAndroidMutationAccountScope()) {
            "The pending mutation account identity is invalid."
        }
        if (!pendingDynamicMutationDirectory.exists()) return
        check(pendingDynamicMutationDirectory.isDirectory) {
            "The pending mutation store is not a directory."
        }
        val candidates = pendingDynamicMutationDirectory.listFiles()
            ?: error("The pending mutation store could not be read.")
        candidates
            .filter { candidate -> candidate.isOwnedPendingDynamicMutation(accountIdentity) }
            .forEach { candidate ->
                check(candidate.canonicalFile.parentFile == pendingDynamicMutationDirectory) {
                    "Unsafe pending mutation cleanup path."
                }
                check(!candidate.exists() || candidate.delete() && !candidate.exists()) {
                    "Could not clear this account's pending mutation."
                }
            }
    }
}

internal fun androidDurableMutationRecoveryKey(
    accountScope: String,
    kind: DurableMutationRecoveryKind,
): String = "durable-mutation-${kind.storageKey}-$accountScope"

private fun File.isOwnedPendingDynamicMutation(accountIdentity: String): Boolean {
    val suffix = when {
        name.endsWith(".json.part") -> name.removeSuffix(".json.part")
        name.endsWith(".json") -> name.removeSuffix(".json")
        else -> return false
    }
    val ownedPrefix = "$accountIdentity-"
    if (!suffix.startsWith(ownedPrefix)) return false
    val identity = suffix.removePrefix(ownedPrefix)
    val digestSeparator = identity.lastIndexOf('-')
    if (digestSeparator <= 0) return false
    val appId = identity.substring(0, digestSeparator)
    val digest = identity.substring(digestSeparator + 1)
    return appId.isSafePendingMutationId() && digest.isCanonicalAndroidMutationAccountScope()
}

internal fun String.isCanonicalAndroidMutationAccountScope(): Boolean =
    length == 64 && all { character -> character in '0'..'9' || character in 'a'..'f' }

internal val androidDurableMutationRecoveryLock = Any()
