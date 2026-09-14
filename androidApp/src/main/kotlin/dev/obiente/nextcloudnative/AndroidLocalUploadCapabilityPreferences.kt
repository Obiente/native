package dev.obiente.nextcloudnative

import android.content.Context
import java.io.File

internal class AndroidLocalUploadCapabilityPreferences(
    context: Context,
    private val preferenceName: String,
    private val preferencePrefix: String,
    private val maximumFileBytes: Long,
) {
    private val preferenceFile = File(context.dataDir, "shared_prefs/$preferenceName.xml")
    private val preferenceBackupFile = File("${preferenceFile.path}.bak")
    private val preferences by lazy {
        requireBoundedStorage()
        context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    }

    fun requireBoundedStorage() {
        if (
            durableUploadCapabilityPreferenceStorageIsOversized(
                primaryFileBytes = preferenceFile.length(),
                backupFileBytes = preferenceBackupFile.length(),
                maximumFileBytes = maximumFileBytes,
            )
        ) throw DurableUploadCapabilityOverflowException()
    }

    fun selectionIds(maximumRows: Int?): List<String> = boundedDurableUploadCapabilitySelectionIds(
        primaryFileBytes = preferenceFile.length(),
        backupFileBytes = preferenceBackupFile.length(),
        maximumFileBytes = maximumFileBytes,
        maximumRows = maximumRows,
        preferencePrefix = preferencePrefix,
        preferenceKeys = { preferences.all.keys },
    )

    fun getString(key: String): String? = preferences.getString(key, null)

    fun putString(key: String, value: String): Boolean = preferences.edit().putString(key, value).commit()

    fun remove(key: String): Boolean = preferences.edit().remove(key).commit()
}
