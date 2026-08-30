package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

@Composable
internal fun VerifiedOfficeWebScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    appId: String,
    advertisedHref: String?,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val advertisedChoice = remember(session.serverUrl, appId, advertisedHref) {
        verifiedEmbeddedWebAppUrl(
            serverUrl = session.serverUrl,
            appId = appId,
            advertisedHref = advertisedHref,
            allowConventionalFallback = false,
        )?.let { url -> OfficeDashboardChoice(appId.officeDashboardDisplayName(), url) }
    }
    val fixedChoice = remember(session.serverUrl, appId) {
        if (appId == OFFICE_NAVIGATION_APP_ID) {
            null
        } else {
            verifiedEmbeddedWebAppUrl(session.serverUrl, appId, null)
                ?.let { url -> OfficeDashboardChoice(appId.officeDashboardDisplayName(), url) }
        }
    }
    val cachedCapabilities = remember(session.serverUrl, session.loginName) {
        sharedDocumentEditingCapabilitiesCache.get(session)
    }
    var attempt by remember(session.serverUrl, session.loginName, appId) { mutableIntStateOf(0) }
    var choices by remember(session.serverUrl, session.loginName, appId, advertisedHref) {
        mutableStateOf(
            listOfNotNull(advertisedChoice ?: fixedChoice).ifEmpty {
                cachedCapabilities?.capabilities
                    ?.let { capabilities -> officeDashboardChoices(session.serverUrl, capabilities) }
                    .orEmpty()
            },
        )
    }
    var discoveryComplete by remember(session.serverUrl, session.loginName, appId, advertisedHref) {
        mutableStateOf(advertisedChoice != null || fixedChoice != null || cachedCapabilities != null)
    }
    var discoveryFailure by remember(session.serverUrl, session.loginName, appId) {
        mutableStateOf<String?>(null)
    }
    var selectedUrl by remember(session.serverUrl, session.loginName, appId) { mutableStateOf<String?>(null) }

    LaunchedEffect(services, session, appId, advertisedHref, attempt) {
        if (advertisedChoice != null || fixedChoice != null) return@LaunchedEffect
        discoveryComplete = false
        discoveryFailure = null
        runCatchingPreservingCancellation {
            services.loadDocumentEditingCapabilities(
                session = session,
                expectedEtag = cachedCapabilities?.etag,
                cachedCapabilities = cachedCapabilities?.capabilities,
            )
        }.onSuccess { result ->
            val capabilities = when (result) {
                is NextcloudConditionalRead.Modified -> result.value.also { value ->
                    sharedDocumentEditingCapabilitiesCache.store(session, value, result.responseEtag)
                }
                NextcloudConditionalRead.NotModified -> cachedCapabilities?.capabilities
            }
            choices = capabilities?.let { officeDashboardChoices(session.serverUrl, it) }.orEmpty()
            discoveryComplete = true
            if (choices.isEmpty()) {
                discoveryFailure = "This server did not advertise a supported Office dashboard."
            }
        }.onFailure { failure ->
            discoveryComplete = true
            discoveryFailure = failure.message ?: "Could not discover this server's Office suite."
        }
    }

    val initialUrl = selectedUrl ?: choices.singleOrNull()?.url
    if (initialUrl == null) {
        Column(
            modifier = modifier.fillMaxSize().padding(NextcloudSpacing.XLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when {
                !discoveryComplete -> {
                    CircularProgressIndicator()
                    Text(
                        "Discovering this server's Office suite...",
                        modifier = Modifier.padding(top = NextcloudSpacing.Large),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                choices.isNotEmpty() -> {
                    Text("Choose an Office suite", style = MaterialTheme.typography.titleLarge)
                    choices.forEach { choice ->
                        Button(
                            onClick = { selectedUrl = choice.url },
                            modifier = Modifier.padding(top = NextcloudSpacing.Medium),
                        ) {
                            Text("Open ${choice.displayName}")
                        }
                    }
                    TextButton(onClick = onExit) { Text("Back") }
                }
                else -> {
                    Text("Office could not be opened", style = MaterialTheme.typography.titleLarge)
                    Text(
                        discoveryFailure ?: "No compatible Office suite is available.",
                        modifier = Modifier.padding(top = NextcloudSpacing.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Button(
                        onClick = { attempt += 1 },
                        modifier = Modifier.padding(top = NextcloudSpacing.Large),
                    ) {
                        Text("Retry discovery")
                    }
                    TextButton(onClick = onExit) { Text("Back") }
                }
            }
        }
        return
    }

    if (services.supportsEmbeddedNextcloudWebApp) {
        PlatformEmbeddedNextcloudWebApp(
            session = session,
            initialUrl = initialUrl,
            authenticateWithSession = true,
            onExit = onExit,
            modifier = modifier,
        )
        return
    }

    var openAttempt by remember(initialUrl) { mutableIntStateOf(0) }
    var failure by remember(initialUrl) { mutableStateOf<String?>(null) }
    LaunchedEffect(services, initialUrl, openAttempt) {
        failure = null
        runCatchingPreservingCancellation { services.openExternalUrl(initialUrl) }
            .onSuccess { onExit() }
            .onFailure { failure = it.message ?: "Could not open the Office web app." }
    }
    Column(
        modifier = modifier.fillMaxSize().padding(NextcloudSpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val message = failure
        if (message == null) {
            CircularProgressIndicator()
            Text(
                "Opening Office in your browser...",
                modifier = Modifier.padding(top = NextcloudSpacing.Large),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("Office could not be opened", style = MaterialTheme.typography.titleLarge)
            Text(
                message,
                modifier = Modifier.padding(top = NextcloudSpacing.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = { openAttempt += 1 },
                modifier = Modifier.padding(top = NextcloudSpacing.Large),
            ) {
                Text("Retry")
            }
            TextButton(onClick = onExit) { Text("Back") }
        }
    }
}

internal data class OfficeDashboardChoice(
    val displayName: String,
    val url: String,
)

internal fun officeDashboardChoices(
    serverUrl: String,
    capabilities: NextcloudDocumentEditingCapabilities,
): List<OfficeDashboardChoice> = capabilities.editors.values
    .asSequence()
    .filter { editor ->
        editor.secure && editor.id.isSafeDocumentCapabilityId() &&
            editor.id in OFFICE_DASHBOARD_EDITOR_IDS
    }
    .mapNotNull { editor ->
        verifiedEmbeddedWebAppUrl(serverUrl, editor.id, null)?.let { url ->
            OfficeDashboardChoice(editor.displayName.ifBlank { editor.id.officeDashboardDisplayName() }, url)
        }
    }
    .distinctBy(OfficeDashboardChoice::url)
    .sortedBy(OfficeDashboardChoice::displayName)
    .toList()

private fun String.officeDashboardDisplayName(): String = when (this) {
    "onlyoffice" -> "ONLYOFFICE"
    "collabora", "nextcloud_office", "office", "richdocuments" -> "Nextcloud Office"
    else -> this
}

private val OFFICE_DASHBOARD_EDITOR_IDS = setOf("onlyoffice", "richdocuments")
