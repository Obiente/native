package dev.obiente.nextcloudnative.app

private val mediaBackupLedgerInitializationMonitor = Any()

internal actual fun <T> withMediaBackupLedgerInitializationLock(block: () -> T): T =
    synchronized(mediaBackupLedgerInitializationMonitor, block)
