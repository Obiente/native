package dev.obiente.nextcloudnative

import android.app.Application
import android.content.Context

class NextcloudNativeApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        installAndroidUncaughtDiagnosticHandler(base)
    }
}
