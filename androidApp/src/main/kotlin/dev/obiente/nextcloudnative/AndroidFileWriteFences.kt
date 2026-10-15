package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.FileWriteFenceRegistry

/**
 * The process's write-fence owner. The Application holds it, so the main activity, the share
 * activity, and workers of this process see the same account blocks even though each creates its
 * own services instance. Blocks are in memory only; a new process starts without any.
 */
internal fun androidFileWriteFences(context: Context): FileWriteFenceRegistry =
    (context.applicationContext as NextcloudNativeApplication).fileWriteFences

/**
 * Drops [accountStorageKey]'s write blocks with its other private state at sign-out or removal.
 * A context that is not this process's Application never registered blocks, so nothing is dropped.
 */
internal fun dropAndroidFileWriteFences(context: Context, accountStorageKey: String) {
    (context.applicationContext as? NextcloudNativeApplication)?.fileWriteFences?.removeAccount(accountStorageKey)
}
