package dev.obiente.nextcloudnative.app

interface FileSyncPlatformServices {
    val supportsFileSyncSettingsEditing: Boolean get() = false

    suspend fun loadFileSyncCenter(
        session: NextcloudSession,
        userId: String,
    ): FileSyncCenterSnapshot = FileSyncCenterSnapshot(
        support = FileSyncCenterSupport.Unsupported,
        pairs = emptyList(),
        limitation = "Bidirectional folder synchronization is not available on this platform.",
    )

    suspend fun addFileSyncPair(
        session: NextcloudSession,
        userId: String,
        localRoot: FileSyncLocalRoot,
        remoteRootPath: String,
        configuration: FileSyncConfiguration,
    ): FileSyncCenterActionResult = FileSyncCenterActionResult.Unsupported(
        "Bidirectional folder synchronization is not available on this platform.",
    )

    /** Scans both roots and executes all conflict-free work with revision guards. */
    suspend fun runFileSyncPair(
        session: NextcloudSession,
        userId: String,
        pairId: String,
    ): FileSyncCenterActionResult = FileSyncCenterActionResult.Unsupported(
        "Bidirectional folder synchronization is not available on this platform.",
    )

    suspend fun resolveFileSyncConflict(
        session: NextcloudSession,
        userId: String,
        pairId: String,
        workId: Long,
        choice: FileSyncDecisionChoice,
    ): FileSyncCenterActionResult = FileSyncCenterActionResult.Unsupported(
        "Folder sync conflict review is not available on this platform.",
    )

    /** Resolves one validated conflict batch and runs the resulting guarded work once. */
    suspend fun resolveFileSyncConflicts(
        session: NextcloudSession,
        userId: String,
        pairId: String,
        resolutions: List<FileSyncConflictResolution>,
    ): FileSyncCenterActionResult {
        require(resolutions.isNotEmpty() && resolutions.size <= MAX_FILE_SYNC_CONFLICT_BATCH)
        if (resolutions.size == 1) {
            val resolution = resolutions.single()
            return resolveFileSyncConflict(
                session,
                userId,
                pairId,
                resolution.workId,
                resolution.choice,
            )
        }
        return FileSyncCenterActionResult.Unsupported(
            "Atomic folder sync conflict batches are not available on this platform.",
        )
    }

    suspend fun removeFileSyncPair(
        session: NextcloudSession,
        userId: String,
        pairId: String,
    ): FileSyncCenterActionResult = FileSyncCenterActionResult.Unsupported(
        "Bidirectional folder synchronization is not available on this platform.",
    )

    /** Compare the reviewed configuration before atomically applying settings and rescheduling. */
    suspend fun updateFileSyncPair(
        session: NextcloudSession,
        userId: String,
        pairId: String,
        expectedConfiguration: FileSyncConfiguration,
        configuration: FileSyncConfiguration,
    ): FileSyncCenterActionResult = FileSyncCenterActionResult.Unsupported("Sync settings cannot be edited on this platform.")
}
