package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

@Composable
internal actual fun PlatformEmbeddedNextcloudWebApp(
    session: NextcloudSession,
    initialUrl: String,
    onExit: () -> Unit,
    onRetrySession: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(NextcloudSpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Embedded Office is available in the Android app. This desktop build uses the system browser.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onExit, modifier = Modifier.padding(top = NextcloudSpacing.Large)) {
            Text("Back")
        }
    }
}
