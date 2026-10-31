package dev.obiente.nextcloudnative.app

import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

internal fun <T> invokeOnSwingEventThread(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    val outcome = AtomicReference<Result<T>>()
    SwingUtilities.invokeAndWait { outcome.set(runCatching(action)) }
    return outcome.get().getOrThrow()
}
