package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** One labelled fact from the serverinfo app; values are display text only. */
data class ServerInfoFact(val label: String, val value: String)

data class ServerInfoSection(val title: String, val facts: List<ServerInfoFact>)

/** Read-only server monitoring from the official serverinfo app's `api/v1/info` OCS route. */
fun serverInfoRequest(): NextcloudApiRequest = NextcloudApiRequest(
    method = NextcloudApiMethod.GET,
    relativePath = "/ocs/v2.php/apps/serverinfo/api/v1/info",
    queryParameters = mapOf("format" to "json", "skipApps" to "true", "skipUpdate" to "false"),
    ocsApiRequest = true,
    maximumResponseBytes = 1L * 1024L * 1024L,
    cachePolicy = NextcloudApiCachePolicy.ForceNetwork,
)

fun parseServerInfo(response: NextcloudApiResponse): AdminReadResult<List<ServerInfoSection>> {
    when (response.status) {
        401, 403 -> return AdminReadResult.Forbidden
        404 -> return AdminReadResult.NotFound
        !in 200..299 -> return AdminReadResult.Unavailable
    }
    val data = response.successfulOcsData() as? JsonObject ?: return AdminReadResult.Malformed
    val nextcloud = data["nextcloud"] as? JsonObject
    val system = nextcloud?.get("system") as? JsonObject
    val storage = nextcloud?.get("storage") as? JsonObject
    val shares = nextcloud?.get("shares") as? JsonObject
    val server = data["server"] as? JsonObject
    val php = server?.get("php") as? JsonObject
    val database = server?.get("database") as? JsonObject
    val activeUsers = data["activeUsers"] as? JsonObject
    val sections = listOf(
        ServerInfoSection(
            "System",
            listOfNotNull(
                system.fact("version", "Nextcloud version"),
                system.fact("freespace", "Free space", ::byteText),
                system.fact("mem_total", "Memory", ::kibibyteText),
                system.fact("mem_free", "Memory available", ::kibibyteText),
                system.fact("cpunum", "CPU threads"),
                system.loadFact(),
                system.fact("memcache.local", "Local cache"),
                system.fact("memcache.locking", "Locking cache"),
                system.fact("debug", "Debug mode"),
                (system?.get("update") as? JsonObject)?.let { update ->
                    update.fact("available", "Update available", ::yesNoText)
                },
            ),
        ),
        ServerInfoSection(
            "Usage",
            listOfNotNull(
                storage.fact("num_users", "Accounts"),
                activeUsers.fact("last5minutes", "Active in the last 5 minutes"),
                activeUsers.fact("last1hour", "Active in the last hour"),
                activeUsers.fact("last24hours", "Active in the last 24 hours"),
                storage.fact("num_files", "Files"),
                storage.fact("num_storages", "Storages"),
                shares.fact("num_shares", "Shares"),
                shares.fact("num_shares_link", "Public links"),
            ),
        ),
        ServerInfoSection(
            "Software",
            listOfNotNull(
                server.fact("webserver", "Web server"),
                php.fact("version", "PHP"),
                php.fact("memory_limit", "PHP memory limit", ::byteText),
                php.fact("upload_max_filesize", "PHP upload limit", ::byteText),
                database.fact("type", "Database"),
                database.fact("version", "Database version"),
                database.fact("size", "Database size", ::byteText),
            ),
        ),
    ).filter { it.facts.isNotEmpty() }
    return if (sections.isEmpty()) AdminReadResult.Malformed else AdminReadResult.Loaded(sections)
}

@Composable
internal fun AdminServerInfoPane(
    execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
    modifier: Modifier = Modifier,
) {
    val currentExecute by rememberUpdatedState(execute)
    var attempt by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf<AdminReadResult<List<ServerInfoSection>>?>(null) }
    LaunchedEffect(attempt) {
        result = try {
            parseServerInfo(currentExecute(serverInfoRequest()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AdminReadResult.Unavailable
        }
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        when (val current = result) {
            null -> Text("Loading server information...", style = MaterialTheme.typography.bodyMedium)
            is AdminReadResult.Loaded -> current.value.forEach { section -> ServerInfoCard(section) }
            AdminReadResult.NotFound -> Text(
                "Server information needs the Monitoring (serverinfo) app. Enable it under Apps to see system details here.",
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> {
                Text(current.failureMessage("server information"), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { attempt += 1 }) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun ServerInfoCard(section: ServerInfoSection) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(NextcloudRadii.Card)) {
        Column(Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(section.title, style = MaterialTheme.typography.titleSmall)
            section.facts.forEach { fact ->
                Row(Modifier.fillMaxWidth()) {
                    Text(fact.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(fact.value, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private fun JsonObject?.fact(key: String, label: String, format: (JsonPrimitive) -> String? = ::plainText): ServerInfoFact? {
    val primitive = this?.get(key) as? JsonPrimitive ?: return null
    val value = format(primitive)?.filterNot(Char::isISOControl)?.take(120)?.takeIf(String::isNotBlank) ?: return null
    return ServerInfoFact(label, value)
}

private fun JsonObject?.loadFact(): ServerInfoFact? {
    val load = (this?.get("cpuload") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull } ?: return null
    if (load.isEmpty()) return null
    return ServerInfoFact("Load average", load.take(3).joinToString(" / ") { ((it * 100).toLong() / 100.0).toString() })
}

private fun plainText(value: JsonPrimitive): String? = value.contentOrNull

private fun yesNoText(value: JsonPrimitive): String? = when (value.contentOrNull) {
    "true", "1", "yes" -> "Yes"
    "false", "0", "no" -> "No"
    else -> null
}

private fun byteText(value: JsonPrimitive): String? = value.longOrNull?.takeIf { it >= 0L }?.let(::formatByteSize)
    ?: value.contentOrNull

private fun kibibyteText(value: JsonPrimitive): String? =
    value.longOrNull?.takeIf { it >= 0L }?.let { formatByteSize(it * 1_024L) }
