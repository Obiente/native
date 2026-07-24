package dev.obiente.nextcloudnative

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.obiente.nextcloudnative.app.PlatformCapability
import dev.obiente.nextcloudnative.app.PlatformCapabilityState
import dev.obiente.nextcloudnative.app.PlatformCapabilityStatus

internal class AndroidPlatformCapabilities(
    private val context: Context,
    private val activity: Activity?,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun statuses(): List<PlatformCapabilityStatus> = PlatformCapability.entries.map { capability ->
        PlatformCapabilityStatus(
            capability = capability,
            label = capability.label(),
            description = capability.description(),
            state = state(capability),
        )
    }

    fun request(capability: PlatformCapability): Boolean {
        if (capability == PlatformCapability.AllFilesAccess && Build.VERSION.SDK_INT >= 30) {
            val host = activity ?: return false
            host.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                },
            )
            return true
        }
        val permissions = capability.permissions(Build.VERSION.SDK_INT)
        if (permissions.isEmpty()) return false
        val host = activity ?: return false
        if (state(capability) == PlatformCapabilityState.Blocked) {
            host.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                },
            )
            return true
        }
        preferences.edit().putBoolean(capability.requestedKey(), true).apply()
        ActivityCompat.requestPermissions(host, permissions.toTypedArray(), capability.requestCode())
        return true
    }

    private fun state(capability: PlatformCapability): PlatformCapabilityState {
        if (capability == PlatformCapability.FilesAndMedia) {
            return PlatformCapabilityState.AvailableWithoutPermission
        }
        if (capability == PlatformCapability.AllFilesAccess && Build.VERSION.SDK_INT >= 30) {
            return if (Environment.isExternalStorageManager()) PlatformCapabilityState.Granted
            else PlatformCapabilityState.NeedsPermission
        }
        if (capability == PlatformCapability.BackgroundSync) return PlatformCapabilityState.Granted
        val permissions = capability.permissions(Build.VERSION.SDK_INT)
        if (permissions.isEmpty()) return PlatformCapabilityState.Granted
        val hasPermission = if (capability == PlatformCapability.MediaLibrary) {
            hasMediaLibraryAccess(Build.VERSION.SDK_INT) { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
        } else {
            permissions.all { permission ->
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
        }
        if (hasPermission) {
            return PlatformCapabilityState.Granted
        }
        val wasRequested = preferences.getBoolean(capability.requestedKey(), false)
        val canExplain = activity?.let { host ->
            permissions.any { permission -> ActivityCompat.shouldShowRequestPermissionRationale(host, permission) }
        } == true
        return if (wasRequested && !canExplain) PlatformCapabilityState.Blocked
        else PlatformCapabilityState.NeedsPermission
    }
}

internal fun PlatformCapability.permissions(sdk: Int): List<String> = when (this) {
    PlatformCapability.Notifications -> if (sdk >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
    PlatformCapability.Camera -> listOf(Manifest.permission.CAMERA)
    PlatformCapability.Microphone -> listOf(Manifest.permission.RECORD_AUDIO)
    PlatformCapability.NearbyAudio -> if (sdk >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
    PlatformCapability.BackgroundSync,
    PlatformCapability.FilesAndMedia,
    -> emptyList()
    PlatformCapability.MediaLibrary -> when {
        sdk >= 34 -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        sdk >= 33 -> listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        sdk >= 23 -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> emptyList()
    }
    PlatformCapability.AllFilesAccess -> when {
        sdk >= 30 -> emptyList()
        sdk >= 23 -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else -> emptyList()
    }
}

internal fun hasMediaLibraryAccess(
    sdk: Int,
    permissionGranted: (String) -> Boolean,
): Boolean = PlatformCapability.MediaLibrary.permissions(sdk).any(permissionGranted)

private fun PlatformCapability.label(): String = when (this) {
    PlatformCapability.Notifications -> "Notifications"
    PlatformCapability.Camera -> "Camera for calls and capture"
    PlatformCapability.Microphone -> "Microphone for calls"
    PlatformCapability.NearbyAudio -> "Bluetooth call audio"
    PlatformCapability.BackgroundSync -> "Background file sync"
    PlatformCapability.FilesAndMedia -> "Files and media"
    PlatformCapability.MediaLibrary -> "Photos and videos"
    PlatformCapability.AllFilesAccess -> "All files access for advanced sync"
}

private fun PlatformCapability.description(): String = when (this) {
    PlatformCapability.Notifications -> "Messages, calls, transfers, conflicts, reminders, and media jobs."
    PlatformCapability.Camera -> "Requested when starting video or capturing media."
    PlatformCapability.Microphone -> "Requested when joining or starting an audio/video call."
    PlatformCapability.NearbyAudio -> "Routes Talk call audio to paired Bluetooth devices."
    PlatformCapability.BackgroundSync -> "Uses a foreground data-sync service only while active work is running."
    PlatformCapability.FilesAndMedia -> "Uses Android's system picker and cloud DocumentsProvider without broad storage access."
    PlatformCapability.MediaLibrary -> "Finds camera, screenshot, image, and video folders for automatic upload."
    PlatformCapability.AllFilesAccess -> "Optional access for syncing arbitrary folders such as an Obsidian vault."
}

private fun PlatformCapability.requestCode(): Int = 8400 + ordinal
private fun PlatformCapability.requestedKey(): String = "capability_requested_${name.lowercase()}"

private const val PREFERENCES = "platform_capabilities"
