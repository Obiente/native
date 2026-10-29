package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException

/** What a discovery refresh reports back to the screen that shows the dynamic app. */
internal class DynamicDiscoveryRefreshCallbacks(
    val onProgress: (DynamicDescriptorDiscoveryProgress) -> Unit,
    /** The contract the screen shows now, or null while nothing usable is known. */
    val onDiscovery: (DynamicDescriptorDiscovery?) -> Unit,
    /** A contract worth remembering beyond this screen. */
    val onPublished: (DynamicDescriptorDiscovery) -> Unit,
    val onError: (String?) -> Unit,
)

/**
 * Shows the best known contract for [app] at once, then checks it against the live server and
 * installed app version when the cached result is not fresh.
 *
 * A previously verified contract stays on screen as verified while it is checked again, so its
 * write actions do not disappear and return. [writeGate] holds any write started during the check
 * until the outcome is known and lets it continue only for the same verified contract. A
 * contract restored after process death stays read-only until checked, as before.
 */
internal suspend fun refreshDynamicAppDiscovery(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    app: NextcloudAppEntry,
    cachedDiscovery: DynamicDescriptorDiscovery?,
    serverVersion: String?,
    installedAppVersionHint: String?,
    serverVersionVerified: Boolean,
    discoveryAttempt: Int,
    writeGate: DynamicContractWriteGate,
    callbacks: DynamicDiscoveryRefreshCallbacks,
) {
    writeGate.beginCheck()
    try {
        refreshDynamicAppDiscoveryChecked(
            services, session, app, cachedDiscovery, serverVersion, installedAppVersionHint,
            serverVersionVerified, discoveryAttempt, writeGate, callbacks,
        )
    } finally {
        // A cancelled check never authorizes the writes that were waiting for it.
        writeGate.abandon()
    }
}

private suspend fun refreshDynamicAppDiscoveryChecked(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    app: NextcloudAppEntry,
    cachedDiscovery: DynamicDescriptorDiscovery?,
    serverVersion: String?,
    installedAppVersionHint: String?,
    serverVersionVerified: Boolean,
    discoveryAttempt: Int,
    writeGate: DynamicContractWriteGate,
    callbacks: DynamicDiscoveryRefreshCallbacks,
) {
    val cacheProducer = sharedDynamicNativeMemoryCache.producer(session)
    callbacks.onProgress(
        DynamicDescriptorDiscoveryProgress(
            DynamicDescriptorDiscoveryPhase.CachedWorkspace,
            "Checking the saved workspace",
        ),
    )
    val persistedDiscovery = if (cachedDiscovery == null) {
        services.loadCachedDynamicAppDiscovery(session, app.id)
    } else {
        null
    }
    val retainedDiscovery = (cachedDiscovery ?: persistedDiscovery)?.takeIf { candidate ->
        cachedDynamicDiscoveryMatchesInstalledVersion(candidate, app.id, installedAppVersionHint)
    }
    if (retainedDiscovery != null) {
        callbacks.onDiscovery(retainedDiscovery)
        callbacks.onPublished(retainedDiscovery)
        sharedDynamicNativeMemoryCache.storeDiscovery(session, app.id, retainedDiscovery, cacheProducer)
    }
    val shouldRetry = discoveryAttempt > 0 || sharedDynamicNativeMemoryCache.shouldRetryDiscovery(session, app.id) ||
        !sharedDynamicNativeMemoryCache.isDiscoveryFresh(session, app.id)
    if (!shouldRetry && retainedDiscovery != null) {
        callbacks.onError(null)
        writeGate.settle(retainedDiscovery.writeAuthority())
        return
    }
    if (!shouldRetry && retainedDiscovery == null) callbacks.onDiscovery(null)
    callbacks.onError(null)
    val candidate = try {
        discoverDynamicAppDescriptor(
            services = services,
            session = session,
            app = app,
            serverVersion = serverVersion,
            installedAppVersionHint = installedAppVersionHint,
            serverVersionVerified = serverVersionVerified,
            onProgress = callbacks.onProgress,
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        val retainedReadOnly = retainedDynamicContractAfterDiscoveryFailure(retainedDiscovery)
        if (retainedReadOnly != null) {
            callbacks.onPublished(retainedReadOnly)
            callbacks.onDiscovery(retainedReadOnly)
            sharedDynamicNativeMemoryCache.storeDiscovery(session, app.id, retainedReadOnly, cacheProducer)
        }
        writeGate.settle(null)
        sharedDynamicNativeMemoryCache.markDiscoveryFailure(session, app.id, cacheProducer)
        callbacks.onError(
            if (retainedDiscovery == null) {
                failure.message ?: "Could not discover this app's native API."
            } else {
                DynamicRetainedReadOnlyMessage
            },
        )
        return
    }
    val resolvedDiscovery = resolveDynamicContractRediscovery(retainedDiscovery, candidate)
    callbacks.onPublished(resolvedDiscovery)
    callbacks.onDiscovery(resolvedDiscovery)
    writeGate.settle(resolvedDiscovery.writeAuthority())
    sharedDynamicNativeMemoryCache.storeDiscovery(session, app.id, resolvedDiscovery, cacheProducer)
    try {
        services.saveCachedDynamicAppDiscovery(session, resolvedDiscovery, cacheProducer)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        // The saved copy only speeds up the next launch; the verified contract is already shown.
    }
    callbacks.onError(if (resolvedDiscovery !== candidate) DynamicRetainedReadOnlyMessage else null)
}

private const val DynamicRetainedReadOnlyMessage =
    "Could not verify the current server and app versions. " +
        "The last verified contract remains available in read-only mode."
