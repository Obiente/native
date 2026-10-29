package dev.obiente.nextcloudnative

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.obiente.nextcloudnative.app.design.nextcloudReducedMotionForAnimatorScale

/**
 * Follows the system "Remove animations" switch, which Android stores as an animator duration
 * scale of zero, and updates while the activity is composed when the person changes it.
 */
@Composable
internal fun rememberAndroidReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var scale by remember(resolver) { mutableFloatStateOf(readAnimatorDurationScale(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scale = readAnimatorDurationScale(resolver)
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        // The setting may have changed between the first read and registration.
        scale = readAnimatorDurationScale(resolver)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return nextcloudReducedMotionForAnimatorScale(scale)
}

private fun readAnimatorDurationScale(resolver: ContentResolver): Float =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
