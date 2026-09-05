package dev.obiente.nextcloudnative

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

class NextcloudNativeApplication : Application() {
    private var accountCleanupListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        installAndroidUncaughtDiagnosticHandler(base)
    }

    override fun onCreate() {
        super.onCreate()
        accountCleanupListener = installAndroidAccountRemovalCleanupRecovery(this)
    }
}
