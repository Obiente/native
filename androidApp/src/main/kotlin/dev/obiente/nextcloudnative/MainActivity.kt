package dev.obiente.nextcloudnative

import android.os.Bundle
import android.content.Intent
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import dev.obiente.nextcloudnative.app.NextcloudNativeApp
import dev.obiente.nextcloudnative.app.NextcloudNativeLinkRequest
import dev.obiente.nextcloudnative.app.ThemePreference

class MainActivity : ComponentActivity() {
    private var appUpdateReviewRequest by mutableLongStateOf(0L)
    private var lastAppUpdateReviewEventId: Long? = null
    private var platformCapabilityRefreshRequest by mutableLongStateOf(0L)
    private var incomingLinkSequence = 0L
    private var incomingLinkRequest by mutableStateOf<NextcloudNativeLinkRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val restoredAppUpdateReviewRequest = savedInstanceState
            ?.takeIf { it.containsKey(KEY_APP_UPDATE_REVIEW_REQUEST) }
            ?.getLong(KEY_APP_UPDATE_REVIEW_REQUEST)
        val restoredAppUpdateReviewEventId = savedInstanceState
            ?.takeIf { it.containsKey(KEY_APP_UPDATE_REVIEW_EVENT_ID) }
            ?.getLong(KEY_APP_UPDATE_REVIEW_EVENT_ID)
        applyAppUpdateReviewState(nextAppUpdateReviewState(
            restoredRequest = restoredAppUpdateReviewRequest,
            restoredEventId = restoredAppUpdateReviewEventId,
            intentAction = intent?.action,
            intentEventId = intent.appUpdateReviewEventId(),
        ))
        incomingLinkSequence = savedInstanceState?.getLong(KEY_INCOMING_LINK_SEQUENCE) ?: 0L
        incomingLinkRequest = savedInstanceState
            ?.getString(KEY_PENDING_INCOMING_LINK)
            ?.let { savedUrl ->
                runCatching { NextcloudNativeLinkRequest(incomingLinkSequence, savedUrl) }.getOrNull()
            }
        if (savedInstanceState == null) receiveIncomingLinkIntent(intent)
        SessionTestBootstrap.importIfPresent(applicationContext)
        AndroidNotificationCoordinator(applicationContext).ensureChannels()
        AndroidAppUpdateWork.schedule(
            applicationContext,
            AndroidProjectContentClient(applicationContext, null).updatePreferences(),
        )
        val fileSyncRootPicker = AndroidFileSyncRootPicker(this)
        fileSyncRootPicker.attach(
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                fileSyncRootPicker.complete(uri)
            },
        )
        val localUploadPicker = AndroidLocalUploadPicker(this)
        localUploadPicker.attach(
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                localUploadPicker.complete(uri)
            },
        )
        val platformPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            platformCapabilityRefreshRequest += 1
        }
        setContent {
            // Keep the composition and its loaded screen state alive across rotations while still
            // observing the new window configuration so adaptive layouts recompose immediately.
            val configuration = LocalConfiguration.current
            val themePreference = remember { mutableStateOf(ThemePreference.System) }
            val services = remember {
                AndroidNextcloudServices(
                    context = this,
                    fileSyncRootPicker = fileSyncRootPicker,
                    localUploadPicker = localUploadPicker,
                    requestPlatformPermissions = { permissions ->
                        platformPermissionLauncher.launch(permissions)
                        true
                    },
                    onThemePreferenceChanged = { preference ->
                        themePreference.value = preference
                    },
                ).also { themePreference.value = it.loadThemePreference() }
            }
            val darkTheme = when (themePreference.value) {
                ThemePreference.System ->
                    configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                        Configuration.UI_MODE_NIGHT_YES
                ThemePreference.Light -> false
                ThemePreference.Dark -> true
            }
            val background = if (darkTheme) DarkWindowBackground else LightWindowBackground

            SideEffect {
                val transparent = android.graphics.Color.TRANSPARENT
                val systemBarStyle = if (darkTheme) {
                    SystemBarStyle.dark(transparent)
                } else {
                    SystemBarStyle.light(transparent, transparent)
                }
                enableEdgeToEdge(
                    statusBarStyle = systemBarStyle,
                    navigationBarStyle = systemBarStyle,
                )
                window.decorView.setBackgroundColor(background.toArgb())
            }

            NextcloudNativeApp(
                services = services,
                appUpdateReviewRequest = appUpdateReviewRequest,
                platformCapabilityRefreshRequest = platformCapabilityRefreshRequest,
                linkRequest = incomingLinkRequest,
                onLinkRequestHandled = { sequence ->
                    if (incomingLinkRequest?.sequence == sequence) incomingLinkRequest = null
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        platformCapabilityRefreshRequest += 1
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveNotificationIntent(intent)
        receiveIncomingLinkIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(KEY_APP_UPDATE_REVIEW_REQUEST, appUpdateReviewRequest)
        lastAppUpdateReviewEventId?.let { eventId ->
            outState.putLong(KEY_APP_UPDATE_REVIEW_EVENT_ID, eventId)
        }
        outState.putLong(KEY_INCOMING_LINK_SEQUENCE, incomingLinkSequence)
        incomingLinkRequest?.url?.let { url ->
            outState.putString(KEY_PENDING_INCOMING_LINK, url)
        }
        super.onSaveInstanceState(outState)
    }

    private fun receiveNotificationIntent(intent: Intent?) {
        applyAppUpdateReviewState(nextAppUpdateReviewState(
            restoredRequest = appUpdateReviewRequest,
            restoredEventId = lastAppUpdateReviewEventId,
            intentAction = intent?.action,
            intentEventId = intent.appUpdateReviewEventId(),
        ))
    }

    private fun receiveIncomingLinkIntent(intent: Intent?) {
        val state = nextAndroidIncomingLinkState(
            previousSequence = incomingLinkSequence,
            action = intent?.action,
            dataUrl = intent?.dataString,
            sharedText = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
        )
        incomingLinkSequence = state.sequence
        if (state.request != null) incomingLinkRequest = state.request
    }

    private fun applyAppUpdateReviewState(state: AppUpdateReviewState) {
        appUpdateReviewRequest = state.requestCount
        lastAppUpdateReviewEventId = state.lastEventId
    }

    private companion object {
        const val KEY_APP_UPDATE_REVIEW_REQUEST = "app-update-review-request"
        const val KEY_APP_UPDATE_REVIEW_EVENT_ID = "app-update-review-event-id"
        const val KEY_INCOMING_LINK_SEQUENCE = "incoming-link-sequence"
        const val KEY_PENDING_INCOMING_LINK = "pending-incoming-link"
        val DarkWindowBackground = Color(0xFF0D0F13)
        val LightWindowBackground = Color(0xFFF7F6FA)
    }
}

internal fun isAppUpdateReviewIntentAction(action: String?): Boolean =
    action == "dev.obiente.nextcloudnative.notification.$ACTION_REVIEW_APP_UPDATE"

internal data class AppUpdateReviewState(
    val requestCount: Long,
    val lastEventId: Long?,
)

internal fun nextAppUpdateReviewState(
    restoredRequest: Long?,
    restoredEventId: Long?,
    intentAction: String?,
    intentEventId: Long?,
): AppUpdateReviewState {
    val isReview = isAppUpdateReviewIntentAction(intentAction)
    val isNewReview = isReview && when {
        intentEventId != null -> intentEventId != restoredEventId
        else -> restoredRequest == null
    }
    return AppUpdateReviewState(
        requestCount = (restoredRequest ?: 0L) + if (isNewReview) 1L else 0L,
        lastEventId = if (isNewReview && intentEventId != null) intentEventId else restoredEventId,
    )
}

private fun Intent?.appUpdateReviewEventId(): Long? =
    this?.takeIf { it.hasExtra(EXTRA_APP_UPDATE_REVIEW_EVENT_ID) }
        ?.getLongExtra(EXTRA_APP_UPDATE_REVIEW_EVENT_ID, 0L)
