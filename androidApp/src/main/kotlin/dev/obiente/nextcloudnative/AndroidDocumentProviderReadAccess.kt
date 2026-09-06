package dev.obiente.nextcloudnative

import android.os.CancellationSignal
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.io.File
import java.io.FileNotFoundException

internal fun acquireAndroidDocumentProviderReadLease(
    expectedSession: NextcloudSession,
    expectedIncarnation: NextcloudDocumentIncarnation,
    loadCurrentSession: () -> NextcloudSession?,
    loadCurrentIncarnation: (String) -> NextcloudDocumentIncarnation,
    operationGuard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
    lifetimeGuard: AndroidAccountRemovalLifetimeGuard = ANDROID_ACCOUNT_REMOVAL_LIFETIME_GUARD,
): AndroidAccountOperationLease {
    val lifetimeLease = lifetimeGuard.acquireReadBlocking(
        expectedSession.documentProviderIncarnationAccountIdentity(),
    )
    val operationLease = try {
        operationGuard.acquireBlocking(androidAccountOperationIdentities(expectedSession))
    } catch (failure: Throwable) {
        lifetimeLease.close()
        throw failure
    }
    return try {
        checkAndroidDocumentProviderReadAccess(
            expectedSession,
            expectedIncarnation,
            loadCurrentSession,
            loadCurrentIncarnation,
        )
        operationLease.close()
        lifetimeLease
    } catch (failure: Throwable) {
        operationLease.close()
        lifetimeLease.close()
        throw failure
    }
}

internal inline fun <Result> withAndroidDocumentProviderReadAccess(
    expectedSession: NextcloudSession,
    expectedIncarnation: NextcloudDocumentIncarnation,
    noinline loadCurrentSession: () -> NextcloudSession?,
    noinline loadCurrentIncarnation: (String) -> NextcloudDocumentIncarnation,
    operationGuard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
    lifetimeGuard: AndroidAccountRemovalLifetimeGuard = ANDROID_ACCOUNT_REMOVAL_LIFETIME_GUARD,
    action: (NextcloudSession) -> Result,
): Result {
    val lease = acquireAndroidDocumentProviderReadLease(
        expectedSession,
        expectedIncarnation,
        loadCurrentSession,
        loadCurrentIncarnation,
        operationGuard,
        lifetimeGuard,
    )
    return try {
        action(expectedSession)
    } finally {
        lease.close()
    }
}

private fun checkAndroidDocumentProviderReadAccess(
    expectedSession: NextcloudSession,
    expectedIncarnation: NextcloudDocumentIncarnation,
    loadCurrentSession: () -> NextcloudSession?,
    loadCurrentIncarnation: (String) -> NextcloudDocumentIncarnation,
) {
    val accountIdentity = expectedSession.documentProviderIncarnationAccountIdentity()
    if (
        loadCurrentSession() != expectedSession ||
        loadCurrentIncarnation(accountIdentity) != expectedIncarnation
    ) {
        throw FileNotFoundException("This Nextcloud document belongs to a removed account.")
    }
}

internal fun openAndroidDocumentAccountLeasedContent(
    content: File,
    accountLease: AndroidAccountOperationLease,
    storageManager: StorageManager,
    handler: Handler,
    signal: CancellationSignal? = null,
    onReleased: () -> Unit = {},
): ParcelFileDescriptor {
    val callback = androidDocumentAccountLeasedContentCallback(content, accountLease, onReleased)
    return try {
        signal?.setOnCancelListener(callback::cancel)
        if (signal?.isCanceled == true) throw android.os.OperationCanceledException()
        storageManager.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, handler)
    } catch (failure: Throwable) {
        callback.onRelease()
        throw failure
    }
}

internal fun androidDocumentAccountLeasedContentCallback(
    content: File,
    accountLease: AndroidAccountOperationLease,
    onReleased: () -> Unit = {},
): AndroidLocalFileProxyCallback = try {
    AndroidLocalFileProxyCallback(
        content = content,
        accessAllowed = { true },
        onReleased = {
            try { onReleased() } finally { accountLease.close() }
        },
    )
} catch (failure: Throwable) {
    try {
        onReleased()
    } catch (cleanupFailure: Throwable) {
        failure.addSuppressed(cleanupFailure)
    }
    try {
        accountLease.close()
    } catch (cleanupFailure: Throwable) {
        failure.addSuppressed(cleanupFailure)
    }
    throw failure
}

internal fun openAndroidDocumentVirtualFileLease(
    lease: AndroidVirtualFileLease,
    accountLease: AndroidAccountOperationLease,
    storageManager: StorageManager,
    handler: Handler,
    signal: CancellationSignal? = null,
): ParcelFileDescriptor = openAndroidDocumentAccountLeasedContent(
    content = lease.content,
    accountLease = accountLease,
    storageManager = storageManager,
    handler = handler,
    signal = signal,
    onReleased = lease.release,
)
