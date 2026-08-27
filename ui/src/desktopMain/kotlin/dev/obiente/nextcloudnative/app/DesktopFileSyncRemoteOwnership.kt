package dev.obiente.nextcloudnative.app

internal fun isDesktopOwnedUploadStage(relativePath: String): Boolean =
    isJvmOwnedUploadStagePath(relativePath)

internal fun desktopOwnedBackupRecoveryPlan(
    relativePaths: Collection<String>,
    ownedUploadPaths: Map<String, String>,
    maximumRecoveryItems: Int,
): List<Pair<String, String>> {
    require(maximumRecoveryItems >= 0)
    require(ownedUploadPaths.keys.all(::isValidNextcloudChunkUploadId))
    val listedPaths = relativePaths.toHashSet()
    val recoveries = relativePaths.mapNotNull { source ->
        val destination = jvmOwnedReplacementBackupDestination(source, ownedUploadPaths)?.first
            ?: return@mapNotNull null
        source to destination
    }.filterNot { (_, destination) -> destination in listedPaths }
    require(recoveries.size <= maximumRecoveryItems) { "A Nextcloud folder contains too many recovery items." }
    return recoveries
}

internal fun projectDesktopOwnedReplacementBackups(
    documents: List<DesktopRemoteSyncDocument>,
    ownedUploadPaths: Map<String, String>,
    ownedStageEtags: Map<String, String>,
    maximumActiveBackups: Int,
): List<DesktopRemoteSyncDocument> {
    require(maximumActiveBackups >= 0)
    val currentByPath = documents.associateBy { it.entry.relativePath }
    val ownedBackups = documents.mapNotNull { backup ->
        val parsed = jvmOwnedReplacementBackupDestination(backup.entry.relativePath, ownedUploadPaths)
            ?: return@mapNotNull null
        parsed to backup
    }
    require(ownedBackups.map { it.first.first }.distinct().size == ownedBackups.size) {
        "A Nextcloud folder contains duplicate owned replacement backups."
    }
    val projectedBackups = ownedBackups.mapNotNull { (parsed, backup) ->
        val (destination, uploadId) = parsed
        val destinationDocument = currentByPath[destination] ?: return@mapNotNull null
        (destination to backup).takeIf {
            shouldProjectJvmOwnedReplacementBackup(uploadId, destinationDocument.entry, ownedStageEtags)
        }
    }.toMap()
    require(projectedBackups.size <= maximumActiveBackups) {
        "A Nextcloud folder contains too many active replacement backups."
    }
    return documents.mapNotNull { document ->
        if (jvmOwnedUploadId(document.entry.relativePath) in ownedUploadPaths) return@mapNotNull null
        if (jvmOwnedReplacementBackupDestination(document.entry.relativePath, ownedUploadPaths) != null) {
            return@mapNotNull null
        }
        val projected = projectedBackups[document.entry.relativePath]
        projected?.copy(entry = projected.entry.copy(relativePath = document.entry.relativePath)) ?: document
    }
}
