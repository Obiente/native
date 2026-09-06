package dev.obiente.nextcloudnative.app

import com.sun.jna.Pointer

/** Root enumeration may carry only the registration context, especially with WinRT registration. */
internal fun CfCallbackInfo.windowsCloudCallbackIdentity(allowRootIdentity: Boolean): ByteArray? {
    val itemIdentity = boundedCallbackIdentity(fileIdentity, fileIdentityLength)
    if (itemIdentity != null) return itemIdentity
    if (!allowRootIdentity || fileId == 0L || fileId != syncRootFileId) return null
    val context = boundedCallbackIdentity(syncRootIdentity, syncRootIdentityLength) ?: return null
    val root = WindowsCloudFileIdentityCodec.decode(context)
    require(root.directory && root.path.isEmpty() && root.remoteRevision == "root") {
        "The Cloud Files root callback context is not a root identity."
    }
    // The provider subsequently verifies account ownership and the exact normalized root path.
    return context
}

private fun boundedCallbackIdentity(pointer: Pointer?, length: Int): ByteArray? {
    require(length in 0..4096) { "The Cloud Files callback identity length is invalid." }
    if (length == 0) return null
    return requireNotNull(pointer) { "The Cloud Files callback identity pointer is missing." }
        .getByteArray(0L, length)
}
