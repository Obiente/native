package dev.obiente.nextcloudnative

import org.json.JSONArray
import org.json.JSONObject

internal fun AndroidIncomingShareRequest.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("state", state.name)
    .put("accountId", accountId)
    .put("userId", userId)
    .put("destinationPath", destinationPath)
    .put("completedFiles", completedFiles)
    .put("uploadedNames", JSONArray(uploadedNames))
    .put("automaticTransferAttempts", automaticTransferAttempts)
    .put("retryNotBeforeEpochMillis", retryNotBeforeEpochMillis)
    .put("visibleMutationInFlight", visibleMutationInFlight)
    .put("visibleMutationTargetName", visibleMutationTargetName)
    .put("chunkSession", chunkSession?.let { session ->
        JSONObject()
            .put("fileIndex", session.fileIndex)
            .put("targetName", session.targetName)
            .put("uploadId", session.uploadId)
            .put("uploadedChunks", session.uploadedChunks)
            .put("commitInFlight", session.commitInFlight)
            .put("cleanupPending", session.cleanupPending)
    })
    .put("message", message)
    .put("discardRequested", discardRequested)
    .put("files", JSONArray().also { array ->
        files.forEach { file ->
            array.put(
                JSONObject()
                    .put("id", file.id)
                    .put("displayName", file.displayName)
                    .put("mimeType", file.mimeType)
                    .put("sizeBytes", file.sizeBytes)
                    .put("stagedName", file.stagedName),
            )
        }
    })

internal fun JSONObject.toIncomingShareRequest(): AndroidIncomingShareRequest = AndroidIncomingShareRequest(
    id = getString("id"),
    state = AndroidIncomingShareState.valueOf(getString("state")),
    accountId = optString("accountId").takeIf(String::isNotBlank),
    userId = optString("userId").takeIf(String::isNotBlank),
    destinationPath = optString("destinationPath").takeIf(String::isNotBlank)
        ?: if (has("destinationPath")) "" else null,
    completedFiles = optInt("completedFiles"),
    uploadedNames = getJSONArray("uploadedNames").let { array ->
        List(array.length()) { index -> array.getString(index) }
    },
    automaticTransferAttempts = optInt("automaticTransferAttempts"),
    retryNotBeforeEpochMillis = optLong("retryNotBeforeEpochMillis")
        .takeIf { has("retryNotBeforeEpochMillis") && !isNull("retryNotBeforeEpochMillis") && it >= 0L },
    visibleMutationInFlight = optBoolean("visibleMutationInFlight"),
    visibleMutationTargetName = optString("visibleMutationTargetName").takeIf(String::isNotBlank),
    chunkSession = optJSONObject("chunkSession")?.let { session ->
        AndroidIncomingShareChunkSession(
            fileIndex = session.getInt("fileIndex"),
            targetName = session.getString("targetName"),
            uploadId = session.getString("uploadId"),
            uploadedChunks = session.optInt("uploadedChunks"),
            commitInFlight = session.optBoolean("commitInFlight"),
            cleanupPending = session.optBoolean("cleanupPending"),
        )
    },
    message = optString("message").takeIf(String::isNotBlank),
    discardRequested = optBoolean("discardRequested"),
    files = getJSONArray("files").let { array ->
        List(array.length()) { index ->
            array.getJSONObject(index).let { file ->
                AndroidIncomingShareFile(
                    id = file.getString("id"),
                    displayName = file.getString("displayName"),
                    mimeType = file.optString("mimeType").takeIf(String::isNotBlank),
                    sizeBytes = file.getLong("sizeBytes"),
                    stagedName = file.getString("stagedName"),
                )
            }
        }
    },
)
