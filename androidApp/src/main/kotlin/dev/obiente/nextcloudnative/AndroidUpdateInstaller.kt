package dev.obiente.nextcloudnative

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.obiente.nextcloudnative.app.AndroidDirectRelease
import dev.obiente.nextcloudnative.app.AppUpdateInstallResult
import dev.obiente.nextcloudnative.app.AppUpdateInstallState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

internal suspend fun openAndroidUpdateInstaller(
    context: Context,
    activity: Activity,
    release: AndroidDirectRelease,
    staged: File,
    updateState: MutableStateFlow<AppUpdateInstallState>,
): AppUpdateInstallResult {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.sharedfiles", staged)
    withContext(Dispatchers.Main.immediate) {
        activity.startActivity(
            Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_RETURN_RESULT, false)
            },
        )
    }
    updateState.value = AppUpdateInstallState.ConfirmationOpened(
        versionName = release.versionName,
        versionCode = release.versionCode,
    )
    return AppUpdateInstallResult.ConfirmationOpened
}
