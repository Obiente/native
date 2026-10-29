package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences

@Composable
internal actual fun rememberSupportRetentionNoticeStore(): SupportRetentionNoticeStore =
    remember { DesktopSupportRetentionNoticeStore(desktopAppNoticePreferences()) }

/**
 * Records once whether this install ran a build with direct Obiente Support before. Call it before
 * the services create [supportIntakeRoot] for this process.
 */
internal fun recordDesktopSupportRetentionInstallHistory(supportIntakeRoot: File) {
    DesktopSupportRetentionNoticeStore(desktopAppNoticePreferences())
        .recordSupportRetentionInstallHistory(supportIntakeRoot.isDirectory)
}

internal class DesktopSupportRetentionNoticeStore(private val preferences: Preferences) : SupportRetentionNoticeStore {
    override fun read(): String? = preferences.get(SUPPORT_RETENTION_NOTICE_KEY, null)

    override fun write(value: String): Boolean = try {
        preferences.put(SUPPORT_RETENTION_NOTICE_KEY, value)
        preferences.flush()
        true
    } catch (_: BackingStoreException) {
        false
    } catch (_: IllegalStateException) {
        false
    }
}

private fun desktopAppNoticePreferences(): Preferences =
    Preferences.userRoot().node("dev/obiente/nextcloudnative/app-notices")
