package dev.obiente.nextcloudnative.app

internal data class DetachedDesktopLinuxProvider(
    val fileSystem: LinuxNextcloudVirtualFileSystem,
    val metadataBackend: CachingLinuxVirtualFileBackend?,
    val accountId: String?,
)

internal fun detachedDesktopLinuxProvider(
    fileSystem: LinuxNextcloudVirtualFileSystem?,
    metadataBackend: CachingLinuxVirtualFileBackend?,
    accountId: String?,
): DetachedDesktopLinuxProvider? = fileSystem?.let {
    DetachedDesktopLinuxProvider(it, metadataBackend, accountId)
}

internal class DesktopLinuxProviderCleanupSlot {
    private val lock = Any()
    private var pending: DetachedDesktopLinuxProvider? = null

    fun unmountOrRetain(provider: DetachedDesktopLinuxProvider) {
        try {
            provider.fileSystem.unmount()
        } catch (failure: Throwable) {
            synchronized(lock) {
                check(pending == null)
                pending = provider
            }
            throw failure
        }
    }

    fun retry() {
        val provider = synchronized(lock) { pending.also { pending = null } } ?: return
        unmountOrRetain(provider)
    }

    fun pendingForTest(): DetachedDesktopLinuxProvider? = synchronized(lock) { pending }
}
