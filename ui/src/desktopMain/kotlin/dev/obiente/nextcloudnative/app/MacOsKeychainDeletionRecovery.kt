package dev.obiente.nextcloudnative.app

import java.security.MessageDigest
import java.util.prefs.Preferences

internal interface MacOsKeychainDeletionRecovery {
    fun pendingTargetNames(): Set<String>

    fun markPending(targetName: String)

    fun markComplete(targetName: String)
}

internal class MacOsKeychainDeletionCoordinator(
    private val recovery: MacOsKeychainDeletionRecovery,
    private val deleteTarget: (String) -> Unit,
) {
    fun clear(targetName: String) {
        try {
            recovery.markPending(targetName)
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (failure: Exception) {
            throw DesktopSecretDeletionRecoveryUnavailableException(failure)
        }
        deleteTarget(targetName)
        markComplete(targetName)
    }

    fun retryAllBestEffort() {
        val targetNames = try {
            recovery.pendingTargetNames()
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (_: Exception) {
            return
        }
        targetNames.forEach { targetName ->
            try {
                deleteTarget(targetName)
                markComplete(targetName)
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (_: Exception) {
                // The durable record remains pending for the next operation or process start.
            }
        }
    }

    fun retry(targetName: String) {
        val pending = try {
            targetName in recovery.pendingTargetNames()
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (failure: Exception) {
            throw DesktopSecretStoreUnavailableException(
                "macOS Keychain cleanup recovery is unavailable.",
                cause = failure,
            )
        }
        if (!pending) return
        deleteTarget(targetName)
        markComplete(targetName)
    }

    private fun markComplete(targetName: String) {
        try {
            recovery.markComplete(targetName)
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (failure: Exception) {
            throw DesktopSecretStoreUnavailableException(
                "macOS Keychain cleanup could not record completion.",
                cause = failure,
            )
        }
    }
}

internal class PreferencesMacOsKeychainDeletionRecovery(
    private val preferences: Preferences = Preferences.userRoot()
        .node("dev/obiente/nextcloudnative/macos-keychain-deletion-v1"),
    private val flush: (Preferences) -> Unit = { it.flush() },
) : MacOsKeychainDeletionRecovery {
    override fun pendingTargetNames(): Set<String> = preferences.keys()
        .mapNotNullTo(linkedSetOf()) { key ->
            preferences.get(key, null)
                ?.takeIf { value -> value.startsWith(PENDING_PREFIX) }
                ?.removePrefix(PENDING_PREFIX)
                ?.takeIf(::isValidTargetName)
        }

    override fun markPending(targetName: String) {
        require(isValidTargetName(targetName))
        val key = targetName.recoveryKey()
        val previous = preferences.get(key, null)
        val existingEntries = pendingTargetNames().size
        check(previous != null || existingEntries < MAX_RECOVERY_ENTRIES) {
            "Too many pending macOS Keychain cleanup records."
        }
        preferences.put(key, PENDING_PREFIX + targetName)
        try {
            flush(preferences)
        } catch (failure: Exception) {
            if (previous == null) preferences.remove(key) else preferences.put(key, previous)
            throw failure
        }
    }

    override fun markComplete(targetName: String) {
        require(isValidTargetName(targetName))
        val key = targetName.recoveryKey()
        preferences.put(key, COMPLETE_PREFIX + targetName)
        try {
            flush(preferences)
        } catch (failure: Exception) {
            // Keep this process conservative when durable completion is ambiguous.
            preferences.put(key, PENDING_PREFIX + targetName)
            throw failure
        }
        preferences.remove(key)
        runCatching { flush(preferences) }
    }

    private fun String.recoveryKey(): String = MessageDigest.getInstance("SHA-256")
        .digest(encodeToByteArray())
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private fun isValidTargetName(targetName: String): Boolean =
        targetName.isNotBlank() &&
            targetName.length <= MAX_TARGET_NAME_CHARACTERS &&
            targetName.none(Char::isISOControl)

    private companion object {
        const val PENDING_PREFIX = "pending:"
        const val COMPLETE_PREFIX = "complete:"
        const val MAX_TARGET_NAME_CHARACTERS = 512
        const val MAX_RECOVERY_ENTRIES = 128
    }
}
