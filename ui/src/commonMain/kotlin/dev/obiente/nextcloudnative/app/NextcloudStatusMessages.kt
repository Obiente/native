package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

@Composable
internal fun LoadingMessage(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(message, modifier = Modifier.padding(top = NextcloudSpacing.Large))
    }
}

@Composable
internal fun EmptyMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, modifier = Modifier.padding(NextcloudSpacing.XLarge), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ErrorMessage(message: String, onRetry: (() -> Unit)? = null) {
    Column(modifier = Modifier.padding(NextcloudSpacing.XLarge), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(NextcloudIcons.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Text(message, color = MaterialTheme.colorScheme.error)
        onRetry?.let { retry -> OutlinedButton(onClick = retry) { Text("Try again") } }
    }
}

@Composable
internal fun SecureSessionStorageUnavailable(
    onRetry: () -> Unit,
    onSignInAgain: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(NextcloudIcons.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(
                "Secure session storage is locked or unavailable. Unlock it or allow " +
                    "nati.ve access, then try again, or discard the stored session and sign in again.",
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = onRetry) { Text("Try again") }
            OutlinedButton(onClick = onSignInAgain) { Text("Sign in again") }
        }
    }
}

@Composable
internal fun LegacySessionMigrationUnavailable(
    onRetry: () -> Unit,
    onSignInAgain: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(NextcloudIcons.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(
                "The previous session needs the legacy secure-storage provider. Install the provider " +
                    "and try again, or discard the stored session and sign in again.",
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = onRetry) { Text("Try again") }
            OutlinedButton(onClick = onSignInAgain) { Text("Sign in again") }
        }
    }
}

@Composable
internal fun SessionLoadingRecoveryScreen(
    state: NextcloudSessionLoadState,
    onRetry: () -> Unit,
    onSignInAgain: () -> Unit,
) {
    when (state) {
        NextcloudSessionLoadState.SecureStorageUnavailable -> SecureSessionStorageUnavailable(onRetry, onSignInAgain)
        NextcloudSessionLoadState.LegacyMigrationUnavailable -> LegacySessionMigrationUnavailable(onRetry, onSignInAgain)
        is NextcloudSessionLoadState.AccountCleanupUnavailable -> AccountSessionCleanupUnavailable(state.reason, onRetry)
        is NextcloudSessionLoadState.Loaded -> Unit
    }
}

@Composable
private fun AccountSessionCleanupUnavailable(reason: NextcloudSessionCleanupReason, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (reason == NextcloudSessionCleanupReason.Pending) "Finishing account cleanup"
                else "Account cleanup needs attention",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                if (reason == NextcloudSessionCleanupReason.Pending)
                    "An earlier account change needs to finish before this account can open. " +
                        "Your saved sign-in and local files are retained. Try again after cleanup finishes."
                else "nati.ve cannot safely finish an earlier account change. " +
                    "Your saved sign-in and local files are retained. " +
                        "Contact Obiente support at $DEFAULT_OBIENTE_SUPPORT_URL for help recovering this account.",
            )
            OutlinedButton(onClick = onRetry) { Text("Try again") }
        }
    }
}
