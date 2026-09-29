package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun LoginScreen(
    services: NextcloudPlatformServices,
    onLoggedIn: suspend (NextcloudSession) -> Unit,
) {
    var serverUrl by remember { mutableStateOf("") }
    val attempt = remember(services) { LoginAttemptState() }
    val connecting = attempt.phase != null
    val status = attempt.phase?.message
    var certificateJustApproved by remember { mutableStateOf<String?>(null) }
    var attemptedServerUrl by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var certificateReview by remember { mutableStateOf<ServerCertificateReview?>(null) }
    var trustedCertificate by remember { mutableStateOf<TrustedServerCertificate?>(null) }
    var trustingCertificate by remember { mutableStateOf(false) }
    var confirmPlainHttp by remember { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    val supportDrafts = remember { SupportSettingsDraftRegistry.loginState() }
    val scope = rememberCoroutineScope()
    val currentOnLoggedIn by rememberUpdatedState(onLoggedIn)
    DisposableEffect(attempt) {
        onDispose { attempt.cancel() }
    }
    fun startLogin(
        transportSecurity: LoginTransportSecurity = LoginTransportSecurity.Tls,
        approvedCertificate: String? = null,
    ) {
        val address = serverUrl
        attemptedServerUrl = address
        certificateJustApproved = approvedCertificate
        error = null
        attempt.start(
            scope = scope,
            begin = { services.beginLogin(address, transportSecurity) },
            openBrowser = services::openLoginUrl,
            poll = services::pollLogin,
            awaitNetwork = services::awaitLoginNetworkAvailability,
            finish = services::finishLoginPolling,
            onAuthenticated = {
                currentOnLoggedIn(it)
                supportDrafts.clearDrafts()
            },
        )
    }
    LaunchedEffect(attempt.failure) {
        val failure = attempt.failure ?: return@LaunchedEffect
        val reviewResult = if (certificateJustApproved == null) {
            filesRequest { services.inspectServerCertificateFailure(attemptedServerUrl, failure) }
        } else {
            Result.success(null)
        }
        if (attempt.failure !== failure) return@LaunchedEffect
        val review = reviewResult.getOrNull()
        if (review != null) {
            certificateReview = review
            error = null
        } else {
            error = reviewResult.exceptionOrNull()?.message
                ?: failure.message
                ?: "Could not connect to this server."
        }
    }
    PlatformBackHandler(enabled = connecting && attempt.phase != LoginAttemptPhase.Completing) {
        attempt.cancel()
    }

    if (confirmPlainHttp) {
        AlertDialog(
            onDismissRequest = { confirmPlainHttp = false },
            title = { Text("Connect without encryption?") },
            text = {
                Text(
                    "This server uses plain HTTP. Your sign-in token, app password, files, messages, " +
                        "and all other Nextcloud data can be read or changed by anyone able to observe " +
                        "the network path. Continue only for a server you reach through a trusted local " +
                        "network or a VPN such as WireGuard. HTTPS is strongly recommended.",
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmPlainHttp = false }) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmPlainHttp = false
                        startLogin(LoginTransportSecurity.PlainHttp)
                    },
                ) { Text("Connect without encryption") }
            },
        )
    }

    certificateReview?.let { review ->
        ServerCertificateReviewDialog(
            review = review,
            checking = trustingCertificate,
            error = null,
            confirmLabel = "Trust and connect",
            onDismiss = { certificateReview = null },
            onConfirm = {
                trustingCertificate = true
                scope.launch {
                    filesRequest { services.trustServerCertificate(review) }
                        .onSuccess {
                            trustedCertificate = services.trustedServerCertificate(review.serverOrigin)
                            certificateReview = null
                            trustingCertificate = false
                            startLogin(approvedCertificate = review.sha256Fingerprint)
                        }
                        .onFailure { failure ->
                            if (failure is CancellationException) throw failure
                            error = failure.message ?: "The certificate could not be trusted."
                            certificateReview = null
                            trustingCertificate = false
                        }
                }
            },
        )
    }

    if (showDiagnostics) {
        AlertDialog(
            onDismissRequest = { showDiagnostics = false },
            title = { Text("Login diagnostics") },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SupportDiagnosticsSettingsCard(services, supportDrafts)
                }
            },
            confirmButton = {
                TextButton(onClick = { showDiagnostics = false }) { Text("Close") }
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(NextcloudSpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
        ) {
            Surface(
                color = NextcloudTheme.colors.appIconContainer,
                shape = RoundedCornerShape(NextcloudRadii.Medium),
            ) {
                dev.obiente.nextcloudnative.app.design.NativeBrandMark(
                    modifier = Modifier.size(64.dp),
                )
            }
            Text("nati.ve", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Your cloud, natively.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { value ->
                    serverUrl = value
                    trustedCertificate = services.trustedServerCertificate(value)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Server address") },
                placeholder = { Text("https://cloud.example.com") },
                singleLine = true,
                enabled = !connecting,
            )
            trustedCertificate?.let { certificate ->
                TrustedCertificateSettings(
                    certificate = certificate,
                    error = null,
                    onRemove = {
                        if (services.removeTrustedServerCertificate(serverUrl)) {
                            trustedCertificate = null
                        } else {
                            error = "The certificate trust could not be removed."
                        }
                    },
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            status?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Button(
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = serverUrl.isNotBlank() && !connecting,
                onClick = {
                    if (serverAddressUsesPlainHttp(serverUrl)) {
                        confirmPlainHttp = true
                    } else {
                        startLogin()
                    }
                },
            ) {
                if (connecting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(end = 4.dp))
                }
                Text(attempt.phase?.buttonLabel ?: "Connect")
            }
            if (connecting && attempt.phase != LoginAttemptPhase.Completing) {
                TextButton(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    onClick = { attempt.cancel() },
                ) {
                    Text("Cancel sign-in")
                }
            }
            TextButton(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                onClick = { showDiagnostics = true },
            ) {
                Text("Export login diagnostics")
            }
        }
    }
}
