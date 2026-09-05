package dev.obiente.nextcloudnative

import android.os.Handler
import android.os.ParcelFileDescriptor
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.io.File
import java.io.FileNotFoundException

internal fun acquireAndroidDocumentProviderReadLease(
    expectedSession: NextcloudSession,
    expectedIncarnation: NextcloudDocumentIncarnation,
    loadCurrentSession: () -> NextcloudSession?,
    loadCurrentIncarnation: (String) -> NextcloudDocumentIncarnation,
    guard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
): AndroidAccountOperationLease {
    val accountIdentity = NextcloudDocumentIds.accountKey(expectedSession)
    val lease = guard.acquireBlocking(accountIdentity)
    return try {
        checkAndroidDocumentProviderReadAccess(
            expectedSession,
            expectedIncarnation,
            loadCurrentSession,
            loadCurrentIncarnation,
        )
        lease
    } catch (failure: Throwable) {
        lease.close()
        throw failure
    }
}

internal inline fun <Result> withAndroidDocumentProviderReadAccess(
    expectedSession: NextcloudSession,
    expectedIncarnation: NextcloudDocumentIncarnation,
    noinline loadCurrentSession: () -> NextcloudSession?,
    noinline loadCurrentIncarnation: (String) -> NextcloudDocumentIncarnation,
    guard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
    action: (NextcloudSession) -> Result,
): Result {
    val lease = acquireAndroidDocumentProviderReadLease(
        expectedSession,
        expectedIncarnation,
        loadCurrentSession,
        loadCurrentIncarnation,
        guard,
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
    val accountIdentity = NextcloudDocumentIds.accountKey(expectedSession)
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
    handler: Handler,
): ParcelFileDescriptor = try {
    ParcelFileDescriptor.open(content, ParcelFileDescriptor.MODE_READ_ONLY, handler) { accountLease.close() }
} catch (failure: Throwable) {
    accountLease.close()
    throw failure
}

internal fun openAndroidDocumentVirtualFileLease(
    lease: AndroidVirtualFileLease,
    accountLease: AndroidAccountOperationLease,
    handler: Handler,
): ParcelFileDescriptor = try {
    ParcelFileDescriptor.open(lease.content, ParcelFileDescriptor.MODE_READ_ONLY, handler) {
        try { lease.release() } finally { accountLease.close() }
    }
} catch (failure: Throwable) {
    lease.release()
    accountLease.close()
    throw failure
}
