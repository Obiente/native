package dev.obiente.nextcloudnative.app

internal fun isDesktopOwnedUploadStage(relativePath: String): Boolean =
    isJvmOwnedUploadStagePath(relativePath)

internal fun desktopOwnedBackupDestination(relativePath: String): String? =
    jvmOwnedReplacementBackup(relativePath)?.first

internal fun desktopOwnedBackupRecoveryPlan(
    relativePaths: Collection<String>,
    ownedUploadIds: Set<String>,
    maximumRecoveryItems: Int,
): List<Pair<String, String>> {
    require(maximumRecoveryItems >= 0)
    require(ownedUploadIds.all(::isValidNextcloudChunkUploadId))
    val listedPaths = relativePaths.toHashSet()
    val recoveries = relativePaths.mapNotNull { source ->
        val (destination, uploadId) = jvmOwnedReplacementBackup(source) ?: return@mapNotNull null
        (source to destination).takeIf { uploadId in ownedUploadIds }
    }.filterNot { (_, destination) -> destination in listedPaths }
    require(recoveries.size <= maximumRecoveryItems) { "A Nextcloud folder contains too many recovery items." }
    return recoveries
}

internal fun projectDesktopOwnedReplacementBackups(
    documents: List<DesktopRemoteSyncDocument>,
    ownedUploadIds: Set<String>,
    ownedStageEtags: Map<String, String>,
    maximumActiveBackups: Int,
): List<DesktopRemoteSyncDocument> {
    require(maximumActiveBackups >= 0)
    val currentByPath = documents.associateBy { it.entry.relativePath }
    val ownedBackups = documents.mapNotNull { backup ->
        val parsed = jvmOwnedReplacementBackup(backup.entry.relativePath) ?: return@mapNotNull null
        parsed.takeIf { (_, uploadId) -> uploadId in ownedUploadIds }?.let { parsed to backup }
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
        if (jvmOwnedUploadId(document.entry.relativePath) in ownedUploadIds) return@mapNotNull null
        if (jvmOwnedReplacementBackup(document.entry.relativePath)?.second in ownedUploadIds) return@mapNotNull null
        val projected = projectedBackups[document.entry.relativePath]
        projected?.copy(entry = projected.entry.copy(relativePath = document.entry.relativePath)) ?: document
    }
}
