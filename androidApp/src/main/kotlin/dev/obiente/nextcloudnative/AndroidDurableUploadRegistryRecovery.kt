package dev.obiente.nextcloudnative

import android.content.Context
import kotlinx.coroutines.CancellationException

internal fun durableUploadRegistryNeedsRecovery(context: Context): Boolean {
    val preferences = context.getSharedPreferences(ANDROID_ACCOUNT_PREFERENCES_NAME, Context.MODE_PRIVATE)
    val cipher by lazy { SessionCipher() }
    return durableUploadRegistryNeedsRecovery(
        readRegistry = { preferences.getString(ANDROID_ACCOUNT_REGISTRY_KEY, null) },
        readAggregate = { preferences.getString(ANDROID_ACCOUNT_SESSION_KEY, null) },
        decrypt = { cipher.decrypt(it) },
    )
}

// Registry reconstruction uses the encrypted aggregate. Independent slots cannot
// reconstruct a malformed registry, so a missing/damaged aggregate needs recovery.
internal fun durableUploadRegistryNeedsRecovery(
    readRegistry: () -> String?,
    readAggregate: () -> String?,
    decrypt: (String) -> String,
): Boolean {
    val registryDamaged = try {
        readRegistry()?.let { restoreAndroidCredentialFreeRegistry(it).credentialRecoveryRequired } == true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: ClassCastException) {
        true
    } catch (_: Exception) {
        return false
    }
    if (!registryDamaged) return false
    return durableUploadCredentialNeedsRecovery(
        keys = listOf(ANDROID_ACCOUNT_SESSION_KEY),
        read = { readAggregate() },
        decrypt = decrypt,
        missingIsDamaged = true,
    )
}
