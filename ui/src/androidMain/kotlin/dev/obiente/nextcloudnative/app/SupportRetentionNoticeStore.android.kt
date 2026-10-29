package dev.obiente.nextcloudnative.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberSupportRetentionNoticeStore(): SupportRetentionNoticeStore {
    val applicationContext = LocalContext.current.applicationContext
    return remember(applicationContext) { AndroidSupportRetentionNoticeStore(applicationContext) }
}

/**
 * Records once whether this install ran a build with direct Obiente Support before. Call it before
 * the support-submission directory is created for this process.
 */
fun recordAndroidSupportRetentionInstallHistory(context: Context, supportStorageExistedAtLaunch: Boolean) {
    AndroidSupportRetentionNoticeStore(context.applicationContext ?: context)
        .recordSupportRetentionInstallHistory(supportStorageExistedAtLaunch)
}

private class AndroidSupportRetentionNoticeStore(context: Context) : SupportRetentionNoticeStore {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(ANDROID_APP_NOTICE_PREFERENCES, Context.MODE_PRIVATE)

    override fun read(): String? = preferences.getString(SUPPORT_RETENTION_NOTICE_KEY, null)

    override fun write(value: String): Boolean =
        preferences.edit().putString(SUPPORT_RETENTION_NOTICE_KEY, value).commit()
}

private const val ANDROID_APP_NOTICE_PREFERENCES = "nextcloud_native_app_notices"
