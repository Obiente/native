package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequest
import dev.obiente.nextcloudnative.contracts.CachedDynamicApiResponse
import dev.obiente.nextcloudnative.contracts.DynamicApiResponseCache
import dev.obiente.nextcloudnative.contracts.OpenApiContractSourceKind
import dev.obiente.nextcloudnative.contracts.FileAppStoreCatalogCache
import dev.obiente.nextcloudnative.contracts.FileVerifiedContractCache
import dev.obiente.nextcloudnative.contracts.SignedAppStoreContractAcquirer
import dev.obiente.nextcloudnative.contracts.VerifiedContractKind
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal fun talkMessageHistoryPath(
    token: String,
    olderCursor: Long?,
    limit: Int,
): String {
    require(limit in 1..MAX_TALK_MESSAGE_PAGE_SIZE) {
        "Talk message page size must be between 1 and $MAX_TALK_MESSAGE_PAGE_SIZE."
    }
    require(olderCursor == null || olderCursor >= 0L) {
        "Talk history cursor must not be negative."
    }
    val encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8).replace("+", "%20")
    return "/ocs/v2.php/apps/spreed/api/v1/chat/$encodedToken" +
        "?format=json&lookIntoFuture=0&limit=$limit&lastKnownMessageId=${olderCursor ?: 0L}" +
        "&includeLastKnown=0&setReadMarker=0&markNotificationsAsRead=0&noStatusUpdate=1"
}

internal const val NOTES_LIST_RELATIVE_PATH = "/index.php/apps/notes/api/v1/notes?exclude=content"

internal fun notesDetailRelativePath(noteId: Long): String {
    require(noteId >= 0L) { "The note ID is invalid." }
    return "/index.php/apps/notes/api/v1/notes/$noteId"
}

internal fun notesConditionalHeaders(expectedEtag: String?): Map<String, String> =
    expectedEtag?.takeIf(String::isNotBlank)?.let { mapOf("If-None-Match" to it) }.orEmpty()

internal fun resolvedNoteEtag(responseEtag: String?, documentEtag: String?): String? =
    responseEtag?.takeIf(String::isNotBlank) ?: documentEtag?.takeIf(String::isNotBlank)

internal const val DIRECT_EDITING_INFO_RELATIVE_PATH =
    "/ocs/v2.php/apps/files/api/v1/directEditing?format=json"

internal const val NEXTCLOUD_CAPABILITIES_RELATIVE_PATH =
    "/ocs/v1.php/cloud/capabilities?format=json"

internal const val DIRECT_EDITING_OPEN_RELATIVE_PATH =
    "/ocs/v2.php/apps/files/api/v1/directEditing/open?format=json"

private const val MAX_DOCUMENT_TEMPLATE_ID_LENGTH = 256
private const val MAX_DOCUMENT_TEMPLATE_NAME_LENGTH = 512
private const val MAX_DOCUMENT_TEMPLATE_EXTENSION_LENGTH = 32

internal fun documentTemplatesRelativePath(editorId: String, creatorId: String): String {
    require(editorId.isSafeDocumentCapabilityId()) { "The document editor ID is invalid." }
    require(creatorId.isSafeDocumentCapabilityId()) { "The document creator ID is invalid." }
    return "/ocs/v2.php/apps/files/api/v1/directEditing/templates/$editorId/$creatorId?format=json"
}

internal fun legacyRichdocumentsTemplatesRelativePath(creatorId: String): String {
    require(creatorId.isSafeDocumentCapabilityId()) { "The document creator ID is invalid." }
    return "/ocs/v2.php/apps/richdocuments/api/v1/templates/$creatorId?format=json"
}

internal fun documentEditingConditionalHeaders(expectedEtag: String?): Map<String, String> =
    expectedEtag?.takeIf(String::isNotBlank)?.let { mapOf("If-None-Match" to it) }.orEmpty()

internal fun parseDesktopDocumentEditingCapabilities(
    body: String,
    supportsFileId: Boolean = false,
): NextcloudDocumentEditingCapabilities {
    val data = JSONObject(body).getJSONObject("ocs").getJSONObject("data")
    val editorObject = data.optJSONObject("editors") ?: JSONObject()
    val creatorObject = data.optJSONObject("creators") ?: JSONObject()
    val editors = editorObject.keys().asSequence().mapNotNull { key ->
        val item = editorObject.optJSONObject(key) ?: return@mapNotNull null
        val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
        id to NextcloudDocumentEditorCapability(
            id = id,
            displayName = item.optString("name").ifBlank { id },
            mimeTypes = item.optJSONArray("mimetypes").toStringSet(),
            optionalMimeTypes = item.optJSONArray("optionalMimetypes").toStringSet(),
            secure = item.optBoolean("secure", false),
        )
    }.toMap()
    val creators = creatorObject.keys().asSequence().mapNotNull { key ->
        val item = creatorObject.optJSONObject(key) ?: return@mapNotNull null
        val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val editorId = item.optString("editor").takeIf(String::isNotBlank) ?: return@mapNotNull null
        id to NextcloudDocumentCreatorCapability(
            id = id,
            editorId = editorId,
            displayName = item.optString("name").ifBlank { id },
            extension = item.optString("extension"),
            templates = item.optBoolean("templates", false),
            mimeType = item.optString("mimetype").takeIf(String::isNotBlank)
                ?: item.optJSONArray("mimetypes")?.optString(0)?.takeIf(String::isNotBlank),
        )
    }.toMap()
    return NextcloudDocumentEditingCapabilities(editors, creators, supportsFileId)
}

internal fun parseDesktopDirectEditingSupportsFileId(body: String): Boolean =
    JSONObject(body)
        .getJSONObject("ocs")
        .getJSONObject("data")
        .getJSONObject("capabilities")
        .optJSONObject("files")
        ?.optJSONObject("directEditing")
        ?.optBoolean("supportsFileId", false)
        ?: false

internal fun parseDesktopDocumentTemplates(
    body: String,
    creatorId: String,
): List<NextcloudDocumentTemplate> {
    require(creatorId.isSafeDocumentCapabilityId()) { "The document creator ID is invalid." }
    val data = JSONObject(body).getJSONObject("ocs").get("data")
    val templates = if (data is JSONObject && data.has("templates")) data.get("templates") else data
    val items = when (templates) {
        is JSONArray -> buildList {
            for (index in 0 until templates.length()) {
                templates.optJSONObject(index)?.let(::add)
            }
        }
        is JSONObject -> templates.keys().asSequence().mapNotNull { key ->
            templates.optJSONObject(key)?.also { item ->
                if (!item.has("id")) item.put("id", key)
            }
        }.toList()
        else -> emptyList()
    }
    return items.mapNotNull { item ->
        val id = item.opt("id")?.toString()?.takeIf {
            it.isNotBlank() && it.length <= MAX_DOCUMENT_TEMPLATE_ID_LENGTH && it.none(Char::isISOControl)
        } ?: return@mapNotNull null
        val displayName = (item.optString("title").ifBlank { item.optString("name") })
            .takeIf { it.isNotBlank() && it.length <= MAX_DOCUMENT_TEMPLATE_NAME_LENGTH && it.none(Char::isISOControl) }
            ?: "Template"
        val extension = item.optString("extension")
            .trim()
            .trimStart('.')
            .takeIf { it.length <= MAX_DOCUMENT_TEMPLATE_EXTENSION_LENGTH && it.all(Char::isLetterOrDigit) }
            .orEmpty()
        NextcloudDocumentTemplate(
            id = id,
            displayName = displayName,
            extension = extension,
            creatorId = creatorId,
            mimeType = item.optString("mimetype").takeIf(String::isNotBlank)
                ?: item.optString("mimeType").takeIf(String::isNotBlank),
        )
    }
}

internal fun directEditingOpenForm(request: NextcloudDocumentEditSessionRequest): String {
    require(request.path.isSafeDocumentLookupPath()) { "The document path is unsafe." }
    require(request.fileId >= 0L) { "The document ID is invalid." }
    require(request.editorId in TRUSTED_DIRECT_EDITING_EDITOR_IDS) {
        "The document editor is not trusted."
    }
    require(request.expectedEtag.isNotBlank()) { "The document version is missing." }
    return listOf(
        "path" to request.path,
        "editorId" to request.editorId,
        "fileId" to request.fileId.toString(),
    ).joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=" +
            URLEncoder.encode(value, StandardCharsets.UTF_8)
    }
}

private val TRUSTED_DIRECT_EDITING_EDITOR_IDS = setOf(
    OFFICE_DIRECT_EDITOR_ID,
    WHITEBOARD_DIRECT_EDITOR_ID,
)

internal fun validatedDirectEditingHandoffUrl(serverUrl: String, candidate: String): String {
    require(candidate.isNotBlank() && candidate.none(Char::isISOControl)) {
        "Nextcloud returned an invalid direct-editing handoff."
    }
    val server = URI(serverUrl.trimEnd('/') + "/")
    require(server.scheme.equals("https", ignoreCase = true) && !server.host.isNullOrBlank()) {
        "The Nextcloud account origin is invalid."
    }
    val resolved = server.resolve(candidate)
    require(
        resolved.scheme.equals(server.scheme, ignoreCase = true) &&
            resolved.host.equals(server.host, ignoreCase = true) &&
            resolved.effectivePort() == server.effectivePort() &&
            resolved.userInfo == null &&
            resolved.rawQuery == null &&
            resolved.rawFragment == null,
    ) {
        "Nextcloud returned a cross-origin direct-editing handoff."
    }
    val routePrefix = server.rawPath.trimEnd('/') + "/index.php/apps/files/directEditing/"
    val rawPath = resolved.rawPath
    val token = rawPath.removePrefix(routePrefix)
    require(
        rawPath.startsWith(routePrefix) &&
            token.isNotBlank() &&
            '/' !in token &&
            '\\' !in token &&
            !token.contains("%2e", ignoreCase = true) &&
            !token.contains("%2f", ignoreCase = true) &&
            !token.contains("%5c", ignoreCase = true),
    ) {
        "Nextcloud returned an unexpected direct-editing handoff route."
    }
    return resolved.toASCIIString()
}

private fun URI.effectivePort(): Int = if (port >= 0) port else when (scheme.lowercase()) {
    "https" -> 443
    "http" -> 80
    else -> -1
}

private fun JSONArray?.toStringSet(): Set<String> = buildSet {
    val source = this@toStringSet ?: return@buildSet
    for (index in 0 until source.length()) {
        source.optString(index).trim().lowercase().takeIf(String::isNotBlank)?.let(::add)
    }
}

private fun desktopContractCacheDirectory(name: String): File {
    require(name.matches(Regex("[a-z]+"))) { "The contract cache name is invalid." }
    val xdgCache = System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank)
    val cacheRoot = xdgCache?.let(::File)
        ?: File(System.getProperty("user.home"), ".cache")
    return File(cacheRoot, "nextcloud-native/contracts/$name")
}

internal const val DESKTOP_PROJECT_CONTENT_CONNECT_TIMEOUT_SECONDS = 10L
internal const val DESKTOP_PROJECT_CONTENT_READ_TIMEOUT_SECONDS = 30L
internal const val DESKTOP_PROJECT_CONTENT_WRITE_TIMEOUT_SECONDS = 30L
internal const val DESKTOP_PROJECT_CONTENT_CALL_TIMEOUT_SECONDS = 10L * 60L

internal fun buildDesktopProjectContentHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(DESKTOP_PROJECT_CONTENT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DESKTOP_PROJECT_CONTENT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(DESKTOP_PROJECT_CONTENT_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(DESKTOP_PROJECT_CONTENT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

internal fun publishDesktopProjectContentCache(temporary: File, destination: File) {
    require(temporary.isFile)
    destination.parentFile?.mkdirs()
    try {
        Files.move(
            temporary.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(
            temporary.toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

internal suspend fun executeDesktopDynamicApiGet(
    accountId: String,
    requestIdentity: String,
    cachePolicy: NextcloudApiCachePolicy,
    coalescer: DynamicApiRequestCoalescer<NextcloudApiResponse>,
    loadCached: () -> NextcloudApiResponse?,
    invalidateCached: () -> Unit,
    executeNetwork: suspend () -> NextcloudApiResponse,
    commit: (NextcloudApiResponse) -> Unit,
): NextcloudApiResponse {
    if (cachePolicy == NextcloudApiCachePolicy.PreferCache) {
        loadCached()?.let { return it }
    } else {
        coalescer.invalidateRequest(accountId, requestIdentity, invalidateCached)
    }
    return coalescer.execute(
        accountId = accountId,
        requestIdentity = requestIdentity,
        load = {
            if (cachePolicy == NextcloudApiCachePolicy.ForceNetwork) {
                executeNetwork()
            } else {
                loadCached() ?: executeNetwork()
            }
        },
        commit = commit,
    )
}

class DesktopNextcloudServices(
    private val onThemePreferenceChanged: (ThemePreference) -> Unit = {},
) : NextcloudPlatformServices {
    private val preferences = Preferences.userRoot().node("dev/obiente/nextcloudnative")
    private val httpClient = OkHttpClient()
    private val noRedirectHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val projectContentHttpClient = buildDesktopProjectContentHttpClient()
    private val contractAcquirer = SignedAppStoreContractAcquirer(
        catalogCache = FileAppStoreCatalogCache(desktopContractCacheDirectory("catalogs")),
        verifiedContractCache = FileVerifiedContractCache(desktopContractCacheDirectory("verified")),
    )
    private val fileReadCache = defaultDesktopFileReadCache()
    private val dynamicApiReadCache = DynamicApiResponseCache(
        desktopContractCacheDirectory("responses"),
    )
    private val dynamicApiRequestCoalescer = DynamicApiRequestCoalescer<NextcloudApiResponse>()
    private val mediaTimelineCarryoverStore = MediaTimelineDavCarryoverStore()
    private val memoriesTimeline = MemoriesPreferredTimelineReadService { session, request ->
        executeNextcloudApi(session, request)
    }
    private val externalFileHandoff = DesktopExternalFileHandoff()
    private val localUploadPicker = DesktopLocalUploadPicker()
    private val deckCardDrafts = DesktopDeckCardDraftStore()
    private val projectNewsCache = File(
        desktopContractCacheDirectory("responses").parentFile,
        "project-content/news-feed-v1.json",
    )
    private val projectNewsImageDirectory = File(projectNewsCache.parentFile, "news-images")

    override val externalFileHandoffSupport: ExternalFileHandoffSupport = ExternalFileHandoffSupport.Available(
        ExternalFileHandoffCapability(
            supportedActions = setOf(ExternalFileHandoffAction.OpenWith),
            maximumFileBytes = MAX_EXTERNAL_FILE_HANDOFF_BYTES,
        ),
    )

    override fun loadThemePreference(): ThemePreference = runCatching {
        ThemePreference.valueOf(preferences.get(KEY_THEME, ThemePreference.System.name))
    }.getOrDefault(ThemePreference.System)

    override fun saveThemePreference(preference: ThemePreference) {
        preferences.put(KEY_THEME, preference.name)
        onThemePreferenceChanged(preference)
    }

    override suspend fun loadProjectNews(forceRefresh: Boolean): ProjectNewsResult =
        withContext(Dispatchers.IO) {
            val cached = runCatching {
                projectNewsCache
                    .takeIf { it.isFile && it.length() <= MAX_PROJECT_NEWS_FEED_BYTES }
                    ?.readBytes()
                    ?.let(::parseProjectNewsFeed)
            }.getOrNull()
            val cacheAge = System.currentTimeMillis() - projectNewsCache.lastModified()
            if (!forceRefresh && cached != null && cacheAge in 0..6 * 60 * 60 * 1_000L) {
                return@withContext ProjectNewsResult(cached, cached = true)
            }
            runCatching {
                projectContentHttpClient.newCall(
                    Request.Builder().url(PROJECT_NEWS_FEED_URL).get().build(),
                ).execute().use { response ->
                    check(response.isSuccessful) {
                        "Project news request failed (HTTP ${response.code})."
                    }
                    val body = requireNotNull(response.body)
                    check(body.contentLength() in -1..MAX_PROJECT_NEWS_FEED_BYTES.toLong())
                    val bytes = body.byteStream().readBounded(MAX_PROJECT_NEWS_FEED_BYTES.toLong())
                    val feed = parseProjectNewsFeed(bytes)
                    projectNewsCache.parentFile.mkdirs()
                    val temporary = File(projectNewsCache.parentFile, "${projectNewsCache.name}.part")
                    temporary.writeBytes(bytes)
                    publishDesktopProjectContentCache(temporary, projectNewsCache)
                    ProjectNewsResult(feed, cached = false)
                }
            }.getOrElse { failure ->
                cached?.let { ProjectNewsResult(it, cached = true) }
                    ?: throw IllegalStateException(
                        failure.message ?: "Could not load project news.",
                        failure,
                    )
            }
        }

    override suspend fun loadProjectNewsImage(image: ProjectNewsImage): ByteArray =
        withContext(Dispatchers.IO) {
            require(isCanonicalProjectNewsImageUrl(image.url))
            val cached = File(projectNewsImageDirectory, "${image.sha256}.png")
            if (cached.isFile && cached.length() <= MAX_PROJECT_NEWS_IMAGE_BYTES) {
                cached.readBytes().takeIf { publicContentSha256(it) == image.sha256 }
                    ?.let { return@withContext it }
            }
            projectContentHttpClient.newCall(Request.Builder().url(image.url).get().build())
                .execute().use { response ->
                    check(response.isSuccessful) {
                        "Project news image request failed (HTTP ${response.code})."
                    }
                    val body = requireNotNull(response.body)
                    check(body.contentLength() in -1..MAX_PROJECT_NEWS_IMAGE_BYTES.toLong())
                    val bytes = body.byteStream().readBounded(MAX_PROJECT_NEWS_IMAGE_BYTES.toLong())
                    check(publicContentSha256(bytes) == image.sha256) {
                        "Project news image verification failed."
                    }
                    projectNewsImageDirectory.mkdirs()
                    val temporary = File(projectNewsImageDirectory, "${image.sha256}.part")
                    temporary.writeBytes(bytes)
                    publishDesktopProjectContentCache(temporary, cached)
                    bytes
                }
        }

    override fun loadLastOpenedAppId(): String = preferences.get(KEY_LAST_OPENED_APP, "files")

    override fun saveLastOpenedAppId(appId: String) {
        preferences.put(KEY_LAST_OPENED_APP, appId)
    }

    override fun loadSession(): NextcloudSession? {
        val server = preferences.get(KEY_SERVER, null) ?: return null
        val login = preferences.get(KEY_LOGIN, null) ?: return null
        val password = secretTool("lookup", server, login) ?: return null
        return NextcloudSession(server, login, password)
    }

    override fun saveSession(session: NextcloudSession) {
        val process = ProcessBuilder(
            "secret-tool", "store", "--label=Nextcloud Native app password",
            "application", APP_ID, "server", session.serverUrl, "login", session.loginName,
        ).start()
        process.outputStream.bufferedWriter().use { it.write(session.appPassword) }
        check(process.waitFor() == 0) { "Could not store the session in the desktop keyring." }
        preferences.put(KEY_SERVER, session.serverUrl)
        preferences.put(KEY_LOGIN, session.loginName)
    }

    override fun clearSession() {
        val server = preferences.get(KEY_SERVER, null)
        val login = preferences.get(KEY_LOGIN, null)
        if (server != null && login != null) secretTool("clear", server, login)
        preferences.remove(KEY_SERVER)
        preferences.remove(KEY_LOGIN)
    }

    override suspend fun loadDeckCardDraft(
        session: NextcloudSession,
        key: DeckCardDraftKey,
    ): PersistedDeckCardDraft? = withContext(Dispatchers.IO) {
        deckCardDrafts.load(session, key)
    }

    override suspend fun saveDeckCardDraft(
        session: NextcloudSession,
        draft: PersistedDeckCardDraft,
    ) = withContext(Dispatchers.IO) {
        deckCardDrafts.save(session, draft)
    }

    override suspend fun clearDeckCardDraft(
        session: NextcloudSession,
        key: DeckCardDraftKey,
    ) = withContext(Dispatchers.IO) {
        deckCardDrafts.clear(session, key)
    }

    override fun openExternalUrl(url: String) {
        Desktop.getDesktop().browse(URI(url))
    }

    override suspend fun handoffFileToExternalApp(
        session: NextcloudSession,
        userId: String,
        file: NextcloudFile,
        action: ExternalFileHandoffAction,
    ): ExternalFileHandoffResult {
        val capability = (externalFileHandoffSupport as ExternalFileHandoffSupport.Available).capability
        return externalFileHandoff.launch(file, action, capability) { maximumBytes ->
            downloadFile(session, userId, file.path, maximumBytes)
        }
    }

    override suspend fun handoffDeckAttachmentToExternalApp(
        session: NextcloudSession,
        target: DeckAttachmentOpenTarget,
        attachment: DeckAttachment,
        action: ExternalFileHandoffAction,
    ): ExternalFileHandoffResult {
        require(target.method == NextcloudApiMethod.GET) {
            "Deck attachments can only be opened with a read request."
        }
        val requestSpec = NextcloudApiRequest(
            method = target.method,
            relativePath = target.relativePath,
            ocsApiRequest = true,
        ).requireSafe()
        val capability = (externalFileHandoffSupport as ExternalFileHandoffSupport.Available).capability
        return externalFileHandoff.launchDetached(attachment, action, capability) { output, maximumBytes ->
            withContext(Dispatchers.IO) {
                val authorization = Base64.getEncoder().encodeToString(
                    "${session.loginName}:${session.appPassword}".toByteArray(StandardCharsets.UTF_8),
                )
                val request = Request.Builder()
                    .url(buildNextcloudApiUrl(session.serverUrl, requestSpec))
                    .get()
                    .header("Accept", "*/*")
                    .header("OCS-APIRequest", "true")
                    .header("User-Agent", USER_AGENT)
                    .header("Authorization", "Basic $authorization")
                    .build()
                noRedirectHttpClient.newCall(request).execute().use { response ->
                    check(response.isSuccessful) {
                        "Opening the Deck attachment failed (HTTP ${response.code})."
                    }
                    val responseBody = response.body
                    val contentLength = responseBody.contentLength()
                    check(contentLength <= maximumBytes || contentLength == -1L) {
                        "The Deck attachment is larger than the external handoff limit."
                    }
                    DesktopDetachedDownload(
                        responseBody.byteStream().copyBoundedTo(output, maximumBytes),
                    )
                }
            }
        }
    }

    override fun copyTextToClipboard(label: String, text: String): Boolean = runCatching {
        require(text.isNotBlank() && text.length <= 8_192 && text.none(Char::isISOControl)) {
            "Clipboard text is invalid."
        }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        true
    }.getOrDefault(false)

    override suspend fun beginLogin(serverUrl: String): LoginChallenge = withContext(Dispatchers.IO) {
        val baseUrl = normalizeServerUrl(serverUrl)
        val response = request("POST", "$baseUrl/index.php/login/v2")
        check(response.status in 200..299) { "Nextcloud Login Flow v2 failed (HTTP ${response.status})." }
        val json = JSONObject(response.text)
        val poll = json.getJSONObject("poll")
        LoginChallenge(poll.getString("endpoint"), poll.getString("token"), json.getString("login"))
    }

    override suspend fun pollLogin(challenge: LoginChallenge): NextcloudSession? = withContext(Dispatchers.IO) {
        val response = request(
            "POST",
            challenge.pollEndpoint,
            body = "token=" + encodeForm(challenge.token),
            contentType = "application/x-www-form-urlencoded",
        )
        if (response.status == 404) return@withContext null
        check(response.status in 200..299) { "Login approval failed (HTTP ${response.status})." }
        val json = JSONObject(response.text)
        NextcloudSession(
            normalizeServerUrl(json.getString("server")),
            json.getString("loginName"),
            json.getString("appPassword"),
        )
    }

    override suspend fun loadServerInfo(session: NextcloudSession): NextcloudServerInfo =
        withContext(Dispatchers.IO) {
            val user = ocsGet(session, "/ocs/v2.php/cloud/user").getJSONObject("ocs").getJSONObject("data")
            val data = ocsGet(session, "/ocs/v1.php/cloud/capabilities")
                .getJSONObject("ocs").getJSONObject("data")
            val capabilities = data.getJSONObject("capabilities")
            val theming = capabilities.optJSONObject("theming")
            val navigation = runCatching {
                ocsGet(session, "/ocs/v2.php/core/navigation/apps")
                    .getJSONObject("ocs").getJSONArray("data")
            }.getOrNull()
            NextcloudServerInfo(
                session.serverUrl,
                user.optString("display-name").ifBlank { session.loginName },
                user.optString("id").ifBlank { session.loginName },
                data.optJSONObject("version")?.optString("string")?.takeIf(String::isNotBlank),
                theming?.optString("name")?.takeIf(String::isNotBlank),
                theming?.optString("color")?.takeIf(String::isNotBlank),
                navigation?.toAppEntries() ?: capabilities.toCapabilityEntries(),
                discoverRecognizeBridge(capabilities.toString()),
                parseNextcloudFileSharingCapabilities(capabilities.toString()),
            )
        }

    override suspend fun listFiles(
        session: NextcloudSession,
        userId: String,
        path: String,
    ): List<NextcloudFile> = listFilesWithSource(session, userId, path).files

    override suspend fun listFilesWithSource(
        session: NextcloudSession,
        userId: String,
        path: String,
    ): NextcloudFileListing = withContext(Dispatchers.IO) {
        val accountId = desktopFileCacheAccountId(session)
        try {
            val response = request(
                "PROPFIND", buildNextcloudFileUrl(session.serverUrl, userId, path), session, DAV_PROPERTIES,
                "application/xml; charset=utf-8", headers = mapOf("Depth" to "1", "Accept" to "application/xml"),
            )
            if (response.status == 207) {
                val files = parseDavFiles(response.body, userId).drop(1)
                    .sortedWith(compareByDescending<NextcloudFile> { it.isDirectory }.thenBy { it.name.lowercase() })
                runCatching { fileReadCache.storeListing(accountId, path, files) }
                NextcloudFileListing(files, NextcloudFileListingSource.Network)
            } else {
                if (response.status >= 500) {
                    fileReadCache.cachedListing(accountId, path)?.let {
                        return@withContext NextcloudFileListing(it, NextcloudFileListingSource.Cache)
                    }
                }
                throw NextcloudFileListingHttpException(response.status)
            }
        } catch (failure: IOException) {
            fileReadCache.cachedListing(accountId, path)
                ?.let { NextcloudFileListing(it, NextcloudFileListingSource.Cache) }
                ?: throw failure
        }
    }

    override suspend fun listFilesCachedWithSource(
        session: NextcloudSession,
        userId: String,
        path: String,
    ): NextcloudFileListing? = withContext(Dispatchers.IO) {
        fileReadCache.cachedListing(desktopFileCacheAccountId(session), path)?.let {
            NextcloudFileListing(it, NextcloudFileListingSource.Cache)
        }
    }

    override suspend fun listMedia(session: NextcloudSession, userId: String): List<NextcloudFile> =
        withContext(Dispatchers.IO) {
            val pages = collectMediaSearchDavPages(
                requests = mediaSearchDavRequests(userId),
                execute = { body ->
                    val response = request(
                        "SEARCH", session.serverUrl + "/remote.php/dav/", session, body,
                        "application/xml; charset=utf-8", headers = mapOf("Accept" to "application/xml"),
                    )
                    MediaSearchDavTransportResponse(response.status, response.body)
                },
                parse = { body -> parseDavFiles(body, userId) },
                shouldSearchRaw = { files -> files.any(NextcloudFile::isRawPhoto) },
                rawCompatibilityPolicy = RawMediaSearchCompatibilityPolicy.KeepAvailableResults,
            )
            mergeMediaSearchResultPages(pages)
        }

    override suspend fun listMediaTimelinePage(
        session: NextcloudSession,
        userId: String,
        cursor: PhotoTimelineCursor?,
        rawPreviouslyObserved: Boolean,
        queryOwner: PhotoMediaQueryOwner,
    ): PhotoTimelinePage = withContext(Dispatchers.IO) {
        suspend fun loadDavPage(davCursor: PhotoTimelineCursor?): PhotoTimelinePage {
            val page = collectMediaTimelineDavPage(
                userId = userId,
                cursor = davCursor,
                execute = { body ->
                    val response = request(
                        "SEARCH", session.serverUrl + "/remote.php/dav/", session, body,
                        "application/xml; charset=utf-8", headers = mapOf("Accept" to "application/xml"),
                    )
                    MediaSearchDavTransportResponse(response.status, response.body)
                },
                parse = { body -> parseDavFiles(body, userId) },
                shouldSearchRaw = { files ->
                    rawPreviouslyObserved || files.any(NextcloudFile::isRawPhoto)
                },
                carryoverStore = mediaTimelineCarryoverStore,
                carryoverAccountScope = photoMediaCarryoverScope(
                    accountScope = desktopFileCacheAccountId(session),
                    owner = queryOwner,
                ),
            )
            return PhotoTimelinePage(
                entries = page.files.mapNotNull(NextcloudFile::toPhotoTimelineEntryOrNull),
                nextCursor = page.nextCursor,
                optionalRawRemovalAuthoritative = page.optionalRawRemovalAuthoritative,
                rawObserved = page.rawObserved,
            )
        }

        if (queryOwner == PhotoMediaQueryOwner.Timeline) {
            memoriesTimeline.loadPage(
                session = session,
                accountScope = desktopFileCacheAccountId(session),
                cursor = cursor,
                fallback = ::loadDavPage,
            )
        } else {
            loadDavPage(cursor)
        }
    }

    override suspend fun listSystemTags(session: NextcloudSession): List<NextcloudSystemTag> =
        withContext(Dispatchers.IO) {
            val discovery = systemTagsDavDiscoveryRequest()
            val response = request(
                discovery.method,
                session.serverUrl + discovery.relativePath,
                session,
                discovery.body.decodeToString(),
                discovery.contentType,
                headers = mapOf("Depth" to discovery.depth.toString(), "Accept" to "application/xml"),
            )
            check(response.status == 207) { "System tag discovery failed (HTTP ${response.status})." }
            parseDesktopSystemTagsDavResponse(response.body)
        }

    override suspend fun resolveFilesById(
        session: NextcloudSession,
        userId: String,
        fileIds: Collection<Long>,
    ): Map<Long, NextcloudFile> = withContext(Dispatchers.IO) {
        fileIds.distinct().chunked(MAX_FILE_IDENTITY_SEARCH_BATCH)
            .flatMap { batch ->
                val search = filesByIdDavSearchRequest(userId, batch)
                val response = request(
                    search.method,
                    session.serverUrl + search.relativePath,
                    session,
                    search.body.decodeToString(),
                    search.contentType,
                    headers = mapOf("Accept" to "application/xml"),
                )
                check(response.status == 207) {
                    "WebDAV file identity lookup failed (HTTP ${response.status})."
                }
                parseDavFiles(response.body, userId)
            }
            .mapNotNull { file -> file.fileId?.let { it to file } }
            .toMap()
    }

    override suspend fun loadPreview(
        session: NextcloudSession,
        fileId: Long,
        width: Int,
        height: Int,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            val safeWidth = boundedPreviewDimension(width)
            val safeHeight = boundedPreviewDimension(height)
            val response = request(
                "GET",
                session.serverUrl +
                    "/index.php/core/preview?fileId=$fileId&x=$safeWidth&y=$safeHeight&a=1&mode=cover" +
                    "&forceIcon=0&mimeFallback=0",
                session,
                headers = mapOf("Accept" to "image/*"),
            )
            check(response.status in 200..299) { "Preview failed (HTTP ${response.status})." }
            response.body
        }

    override suspend fun downloadFile(
        session: NextcloudSession,
        userId: String,
        path: String,
        maxBytes: Long,
    ): NextcloudFileContent = withContext(Dispatchers.IO) {
        require(maxBytes > 0) { "The download size limit must be greater than zero." }
        val accountId = desktopFileCacheAccountId(session)
        val cached = fileReadCache.cachedContent(accountId, path, maxBytes)
        try {
            var response = request(
                "GET",
                buildNextcloudFileUrl(session.serverUrl, userId, path),
                session,
                headers = buildMap {
                    put("Accept", "*/*")
                    cached?.etag?.let { put("If-None-Match", it) }
                },
                maxResponseBytes = maxBytes,
            )
            if (response.status == 304 && cached == null) {
                response = request(
                    "GET",
                    buildNextcloudFileUrl(session.serverUrl, userId, path),
                    session,
                    headers = mapOf("Accept" to "*/*"),
                    maxResponseBytes = maxBytes,
                )
            }
            when {
                response.status == 304 && cached != null ->
                    NextcloudFileContent(cached.bytes, cached.mimeType, cached.etag)
                response.status == 404 -> {
                    runCatching { fileReadCache.invalidate(accountId, path) }
                    error("The file no longer exists on the server.")
                }
                response.status >= 500 && cached != null ->
                    NextcloudFileContent(cached.bytes, cached.mimeType, cached.etag)
                response.status !in 200..299 ->
                    error("Downloading the file failed (HTTP ${response.status}).")
                else -> NextcloudFileContent(response.body, response.contentType, response.etag).also { content ->
                    runCatching { fileReadCache.storeContent(accountId, path, content) }
                }
            }
        } catch (failure: IOException) {
            cached?.let { NextcloudFileContent(it.bytes, it.mimeType, it.etag) } ?: throw failure
        }
    }

    override suspend fun downloadFileRange(
        session: NextcloudSession,
        userId: String,
        path: String,
        offset: Long,
        length: Int,
        expectedEtag: String,
    ): ByteArray = withContext(Dispatchers.IO) {
        require(offset >= 0L) { "The file range offset must not be negative." }
        require(length > 0) { "The file range length must be greater than zero." }
        val safeEtag = requireSafeFileRangeEtag(expectedEtag)
        val endInclusive = Math.addExact(offset, length.toLong() - 1L)
        val response = request(
            "GET",
            buildNextcloudFileUrl(session.serverUrl, userId, path),
            session,
            headers = mapOf(
                "Accept" to "application/octet-stream",
                "Range" to "bytes=$offset-$endInclusive",
                "If-Match" to safeEtag,
            ),
            maxResponseBytes = length.toLong(),
            client = noRedirectHttpClient,
        )
        check(response.status == 206) {
            "The server did not honor the bounded file range request (HTTP ${response.status})."
        }
        check(isExactHttpByteContentRange(response.contentRange, offset, endInclusive)) {
            "The server returned a different file range than requested."
        }
        check(response.body.size == length) {
            "The server returned an incomplete file range."
        }
        response.body
    }

    override suspend fun downloadMemoriesFileRange(
        session: NextcloudSession,
        fileId: Long,
        offset: Long,
        length: Int,
        expectedEtag: String,
        expectedSourceSize: Long,
    ): ByteArray = withContext(Dispatchers.IO) {
        require(fileId > 0L) { "The Memories file ID must be positive." }
        require(offset >= 0L) { "The file range offset must not be negative." }
        require(length > 0) { "The file range length must be greater than zero." }
        require(expectedSourceSize > 0L) { "The source size must be positive." }
        val safeEtag = requireSafeFileRangeEtag(expectedEtag)
        val endInclusive = Math.addExact(offset, length.toLong() - 1L)
        val response = request(
            "GET",
            session.serverUrl.trimEnd('/') + "/index.php/apps/memories/api/stream/$fileId",
            session,
            headers = mapOf(
                "Accept" to "application/octet-stream",
                "Range" to "bytes=$offset-$endInclusive",
                "If-Match" to safeEtag,
            ),
            maxResponseBytes = length.toLong(),
            client = noRedirectHttpClient,
        )
        check(response.status == 206) {
            "The Memories stream did not honor the bounded range request (HTTP ${response.status})."
        }
        check(
            isExactHttpByteContentRange(
                response.contentRange,
                offset,
                endInclusive,
                expectedSourceSize,
            ),
        ) {
            "The Memories stream returned a different file range than requested."
        }
        response.etag?.let { returnedEtag ->
            check(requireSafeFileRangeEtag(returnedEtag) == safeEtag) {
                "The Memories stream returned a different file generation."
            }
        }
        check(response.body.size == length) {
            "The Memories stream returned an incomplete file range."
        }
        response.body
    }

    override suspend fun listFileVersions(
        session: NextcloudSession,
        userId: String,
        file: NextcloudFile,
    ): FileVersionHistory = withContext(Dispatchers.IO) {
        require(!file.isDirectory) { "Folders do not have file version history." }
        val fileId = requireNotNull(file.fileId) { "The file has no stable server identity." }
        require(fileId > 0L) { "The file has no stable server identity." }
        val specification = fileVersionHistoryRequest(userId, fileId)
        val response = request(
            method = specification.method,
            url = session.serverUrl + specification.relativePath,
            session = session,
            body = specification.body?.decodeToString(),
            contentType = specification.contentType,
            headers = mapOf(
                "Depth" to requireNotNull(specification.depth).toString(),
                "Accept" to "application/xml",
            ),
            maxResponseBytes = specification.maximumResponseBytes,
            client = noRedirectHttpClient,
        )
        check(response.status != 404) { "Version history is not available for this file." }
        check(response.status == 207) { "Loading file version history failed (HTTP ${response.status})." }
        normalizeFileVersionHistory(userId, fileId, parseDesktopFileVersionDavRecords(response.body))
    }

    override suspend fun downloadFileVersion(
        session: NextcloudSession,
        userId: String,
        file: NextcloudFile,
        version: NextcloudFileVersion,
        maximumBytes: Long,
    ): NextcloudFileContent = withContext(Dispatchers.IO) {
        val fileId = requireMatchingFileVersion(file, version)
        val specification = boundedFileVersionContentRequest(
            userId,
            fileId,
            version.id,
            maximumBytes,
            version.sizeBytes,
        )
        val response = request(
            method = specification.method,
            url = session.serverUrl + specification.relativePath,
            session = session,
            headers = specification.headers + ("Accept" to "*/*"),
            maxResponseBytes = specification.maximumResponseBytes,
            client = noRedirectHttpClient,
        )
        check(response.status != 404) { "This historical version no longer exists." }
        check(response.status == 200 || response.status == 206) {
            "Downloading the historical version failed (HTTP ${response.status})."
        }
        NextcloudFileContent(response.body, response.contentType, response.etag ?: version.etag)
    }

    override suspend fun restoreFileVersion(
        session: NextcloudSession,
        userId: String,
        file: NextcloudFile,
        version: NextcloudFileVersion,
    ): Unit = withContext(Dispatchers.IO) {
        val specification = fileVersionRestoreRequest(userId, file, version)
        val response = request(
            method = specification.method,
            url = session.serverUrl + specification.relativePath,
            session = session,
            headers = specification.headers + mapOf(
                "Accept" to "*/*",
                "Destination" to session.serverUrl + specification.destinationRelativePath,
            ),
            maxResponseBytes = specification.maximumResponseBytes,
            client = noRedirectHttpClient,
        )
        when (response.status) {
            in 200..299 -> Unit
            403 -> error("You do not have permission to restore this file version.")
            404 -> error("This historical version no longer exists.")
            409 -> error("The server could not restore this version to the current file.")
            else -> error("Restoring the file version failed (HTTP ${response.status}).")
        }
    }

    override suspend fun saveTextFile(
        session: NextcloudSession,
        userId: String,
        path: String,
        text: String,
        expectedEtag: String,
    ): SavedTextFile = withContext(Dispatchers.IO) {
        val utf8 = text.toByteArray(StandardCharsets.UTF_8)
        require(utf8.size.toLong() <= MAX_EDITABLE_TEXT_BYTES) {
            "Text files larger than ${MAX_EDITABLE_TEXT_BYTES / (1024 * 1024)} MiB cannot be edited in the app."
        }
        require(expectedEtag.isNotBlank() && expectedEtag.none { it == '\r' || it == '\n' }) {
            "A valid file version is required before saving."
        }
        val headers = buildMap {
            put("Accept", "*/*")
            put("If-Match", expectedEtag)
        }
        val response = request(
            "PUT",
            buildNextcloudFileUrl(session.serverUrl, userId, path),
            session,
            rawBody = utf8,
            contentType = "text/plain; charset=utf-8",
            headers = headers,
        )
        check(response.status != 412) { "The file changed on the server. Reload it before saving your changes." }
        check(response.status in 200..299) { "Saving the text file failed (HTTP ${response.status})." }
        val etag = response.etag ?: runCatching { loadFileEtag(session, userId, path) }.getOrNull()
        val accountId = desktopFileCacheAccountId(session)
        runCatching {
            fileReadCache.invalidate(accountId, path)
            etag?.let {
                fileReadCache.storeContent(
                    accountId,
                    path,
                    NextcloudFileContent(utf8, "text/plain; charset=utf-8", it),
                )
            }
        }
        SavedTextFile(etag, response.status == 201)
    }

    override suspend fun createTextFileIfAbsent(
        session: NextcloudSession,
        userId: String,
        path: String,
        text: String,
    ): SavedTextFile = withContext(Dispatchers.IO) {
        val utf8 = text.toByteArray(StandardCharsets.UTF_8)
        require(utf8.size.toLong() <= MAX_EDITABLE_TEXT_BYTES) {
            "Text files larger than ${MAX_EDITABLE_TEXT_BYTES / (1024 * 1024)} MiB cannot be created in the app."
        }
        val response = request(
            "PUT",
            buildNextcloudFileUrl(session.serverUrl, userId, path),
            session,
            rawBody = utf8,
            contentType = "text/plain; charset=utf-8",
            headers = mapOf("Accept" to "*/*", "If-None-Match" to "*"),
        )
        if (response.status == 412) return@withContext SavedTextFile(etag = null, wasCreated = false)
        check(response.status in 200..299) { "Creating the text file failed (HTTP ${response.status})." }
        check(response.status == 201) { "The server did not confirm that a new text file was created." }
        runCatching {
            val accountId = desktopFileCacheAccountId(session)
            fileReadCache.invalidate(accountId, path)
            response.etag?.let {
                fileReadCache.storeContent(
                    accountId,
                    path,
                    NextcloudFileContent(utf8, "text/plain; charset=utf-8", it),
                )
            }
        }
        SavedTextFile(response.etag, wasCreated = true)
    }

    override suspend fun createDirectoryIfAbsent(
        session: NextcloudSession,
        userId: String,
        path: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val response = request(
            method = "MKCOL",
            url = buildNextcloudFileUrl(session.serverUrl, userId, path),
            session = session,
            headers = mapOf("Accept" to "*/*", "If-None-Match" to "*"),
            maxResponseBytes = 64 * 1024,
        )
        if (response.status in setOf(405, 412)) return@withContext false
        if (response.status !in 200..299) throw fileOperationException(response.status)
        check(response.status == 201) { "The server did not confirm that a new folder was created." }
        runCatching {
            fileReadCache.invalidate(desktopFileCacheAccountId(session), path)
        }
        true
    }

    override suspend fun executeFileMutation(
        session: NextcloudSession,
        userId: String,
        mutation: NextcloudFileMutation,
    ): NextcloudFileMutationResult = withContext(Dispatchers.IO) {
        val spec = mutation.toWebDavMutationSpec()
        val headers = buildMap {
            put("Accept", "*/*")
            putAll(spec.conflictConditionHeaders())
            spec.destinationPath?.let { destinationPath ->
                put("Destination", buildNextcloudFileUrl(session.serverUrl, userId, destinationPath))
                put("Overwrite", if (spec.overwrite) "T" else "F")
            }
        }
        val response = request(
            method = spec.method,
            url = buildNextcloudFileUrl(session.serverUrl, userId, spec.sourcePath),
            session = session,
            headers = headers,
            maxResponseBytes = 64 * 1024,
        )
        if (response.status !in 200..299) throw fileOperationException(response.status)
        runCatching {
            val accountId = desktopFileCacheAccountId(session)
            fileReadCache.invalidate(accountId, spec.sourcePath)
            spec.destinationPath?.let { fileReadCache.invalidate(accountId, it) }
        }
        NextcloudFileMutationResult(spec.destinationPath, response.etag)
    }

    override suspend fun executeNextcloudApi(
        session: NextcloudSession,
        request: NextcloudApiRequest,
    ): NextcloudApiResponse = withContext(Dispatchers.IO) {
        val safeRequest = request.requireSafe()
        val accountId = desktopFileCacheAccountId(session)
        val cacheIdentity = safeRequest.dynamicReadCacheIdentity()
        if (safeRequest.method != NextcloudApiMethod.GET) {
            dynamicApiRequestCoalescer.invalidateAccount(accountId) {
                runCatching { dynamicApiReadCache.invalidateAccount(accountId) }
            }
        }
        suspend fun executeNetworkRequest(): NextcloudApiResponse {
            val response = request(
                method = safeRequest.method.name,
                url = buildNextcloudApiUrl(session.serverUrl, safeRequest),
                session = session,
                contentType = safeRequest.contentType,
                rawBody = safeRequest.body,
                ocsRequest = safeRequest.ocsApiRequest,
                maxResponseBytes = safeRequest.maximumResponseBytes,
                client = noRedirectHttpClient,
            )
            return NextcloudApiResponse(
                response.status,
                response.body,
                response.contentType,
                response.etag,
                response.location,
            )
        }
        if (safeRequest.method != NextcloudApiMethod.GET) {
            return@withContext try {
                executeNetworkRequest()
            } finally {
                dynamicApiRequestCoalescer.invalidateAccount(accountId) {
                    runCatching { dynamicApiReadCache.invalidateAccount(accountId) }
                }
            }
        }
        executeDesktopDynamicApiGet(
            accountId = accountId,
            requestIdentity = cacheIdentity,
            cachePolicy = safeRequest.cachePolicy,
            coalescer = dynamicApiRequestCoalescer,
            loadCached = {
                dynamicApiReadCache.load(accountId, cacheIdentity, safeRequest.maximumResponseBytes)
                    ?.let { cached ->
                        NextcloudApiResponse(cached.status, cached.body, cached.contentType, cached.etag)
                    }
            },
            invalidateCached = {
                runCatching { dynamicApiReadCache.invalidate(accountId, cacheIdentity) }
            },
            executeNetwork = ::executeNetworkRequest,
            commit = { result ->
                if (
                    result.status in 200..299 &&
                    result.contentType?.contains("json", ignoreCase = true) == true
                ) {
                    runCatching {
                        dynamicApiReadCache.store(
                            accountId,
                            cacheIdentity,
                            CachedDynamicApiResponse(
                                result.status,
                                result.body,
                                result.contentType,
                                result.etag,
                            ),
                        )
                    }
                }
            },
        )
    }

    override suspend fun chooseLocalUploadFile(
        acceptedMimeTypes: List<String>,
        maximumBytes: Long,
    ): LocalUploadSelectionResult =
        localUploadPicker.choose(acceptedMimeTypes, maximumBytes)

    override fun releaseLocalUploadFile(file: LocalUploadFile) {
        localUploadPicker.release(file)
    }

    override suspend fun executeNextcloudMultipartUpload(
        session: NextcloudSession,
        request: NextcloudMultipartUploadRequest,
    ): NextcloudApiResponse = withContext(Dispatchers.IO) {
        val safeRequest = request.requireSafe()
        val envelope = prepareMultipartUpload(
            safeRequest,
            "nc-native-${UUID.randomUUID()}",
        )
        val requestBody = DesktopStreamingMultipartRequestBody(envelope) {
            localUploadPicker.open(safeRequest.file)
        }
        val apiRequest = NextcloudApiRequest(
            method = safeRequest.method,
            relativePath = safeRequest.relativePath,
            queryParameters = safeRequest.queryParameters,
            ocsApiRequest = safeRequest.ocsApiRequest,
            maximumResponseBytes = safeRequest.maximumResponseBytes,
        )
        val accountId = desktopFileCacheAccountId(session)
        dynamicApiRequestCoalescer.invalidateAccount(accountId) {
            runCatching { dynamicApiReadCache.invalidateAccount(accountId) }
        }
        try {
            val response = request(
                method = safeRequest.method.name,
                url = buildNextcloudApiUrl(session.serverUrl, apiRequest),
                session = session,
                ocsRequest = safeRequest.ocsApiRequest,
                streamingBody = requestBody,
                maxResponseBytes = safeRequest.maximumResponseBytes,
                client = noRedirectHttpClient,
            )
            NextcloudApiResponse(
                response.status,
                response.body,
                response.contentType,
                response.etag,
                response.location,
            )
        } finally {
            dynamicApiRequestCoalescer.invalidateAccount(accountId) {
                runCatching { dynamicApiReadCache.invalidateAccount(accountId) }
            }
        }
    }

    override suspend fun executeGroupwareDav(
        session: NextcloudSession,
        request: GroupwareDavRequest,
    ): NextcloudApiResponse = withContext(Dispatchers.IO) {
        val headers = buildMap {
            request.depth?.let { put("Depth", it.toString()) }
            putAll(request.headers)
        }
        val response = request(
            method = request.method,
            url = session.serverUrl.trimEnd('/') + request.relativePath,
            session = session,
            contentType = request.contentType,
            rawBody = request.body,
            headers = headers,
            maxResponseBytes = request.maximumResponseBytes,
            client = noRedirectHttpClient,
        )
        NextcloudApiResponse(response.status, response.body, response.contentType, response.etag, response.location)
    }

    override suspend fun executeMediaCollectionMutation(
        session: NextcloudSession,
        request: NativeMediaCollectionTransportRequest,
    ): NextcloudApiResponse = withContext(Dispatchers.IO) {
        val origin = session.serverUrl.trimEnd('/')
        val headers = buildMap {
            put("Accept", "*/*")
            request.ifMatch?.let { put("If-Match", it) }
            if (request.ifNoneMatch) put("If-None-Match", "*")
            request.destinationRelativePath?.let { put("Destination", origin + it) }
            request.overwrite?.let { put("Overwrite", if (it) "T" else "F") }
        }
        val response = request(
            method = request.method.name,
            url = origin + request.relativePath,
            session = session,
            headers = headers,
            maxResponseBytes = 64 * 1024,
            client = noRedirectHttpClient,
        )
        NextcloudApiResponse(response.status, response.body, response.contentType, response.etag, response.location)
    }

    override suspend fun executePeopleMutation(
        session: NextcloudSession,
        request: PeopleTransportRequest,
    ): NextcloudApiResponse = withContext(Dispatchers.IO) {
        val formBody = request.encodedFormBody()
        val headers = buildMap {
            request.destinationRelativePath?.let { destination ->
                put("Destination", buildPeopleMutationUrl(session, destination))
            }
            request.overwrite?.let { put("Overwrite", if (it) "T" else "F") }
            (request.authorization as? PeopleTransportAuthorization.RecognizeBridgeToken)?.let { authorization ->
                put(authorization.headerName, authorization.bridgeToken.value)
            }
        }
        val response = request(
            method = request.method.name,
            url = buildPeopleMutationUrl(session, request.relativePath),
            session = session,
            contentType = formBody?.let { "application/x-www-form-urlencoded; charset=utf-8" },
            rawBody = formBody,
            ocsRequest = request.surface == PeopleMutationSurface.MemoriesApi,
            headers = headers,
            maxResponseBytes = 64 * 1024,
            client = noRedirectHttpClient,
        )
        NextcloudApiResponse(response.status, response.body, response.contentType, response.etag, response.location)
    }

    override suspend fun acquireSignedOpenApiContract(
        appId: String,
        serverVersion: String,
        installedAppVersion: String?,
    ): AcquiredOpenApiContract? = withContext(Dispatchers.IO) {
        contractAcquirer.acquire(ContractAcquisitionRequest(appId, serverVersion, installedAppVersion))
            ?.let { contract ->
                AcquiredOpenApiContract(
                    appId = contract.appId,
                    appVersion = contract.appVersion,
                    contractVersion = contract.contractVersion,
                    specFile = contract.specFile,
                    document = contract.document,
                    packageUrl = contract.packageUrl,
                    sourceUrl = contract.sourceUrl,
                    sourceKind = when (contract.sourceKind) {
                        OpenApiContractSourceKind.SignedAppPackage ->
                            AcquiredOpenApiContractSourceKind.SignedAppPackage
                        OpenApiContractSourceKind.SignedCompatibleAppPackage ->
                            AcquiredOpenApiContractSourceKind.SignedCompatibleAppPackage
                        OpenApiContractSourceKind.AppStoreLinkedExactGitHubTag ->
                            AcquiredOpenApiContractSourceKind.AppStoreLinkedExactGitHubTag
                        OpenApiContractSourceKind.AppStoreLinkedCompatibleGitHubTag ->
                            AcquiredOpenApiContractSourceKind.AppStoreLinkedCompatibleGitHubTag
                    },
                    contractKind = when (contract.contractKind) {
                        VerifiedContractKind.OpenApi -> AcquiredContractKind.OpenApi
                        VerifiedContractKind.VerifiedReadRoutes -> AcquiredContractKind.VerifiedReadRoutes
                        VerifiedContractKind.OpenApiWithVerifiedReadRoutes ->
                            AcquiredContractKind.OpenApiWithVerifiedReadRoutes
                    },
                )
            }
    }

    override suspend fun listActivities(session: NextcloudSession, limit: Int): List<NextcloudActivity> =
        withContext(Dispatchers.IO) {
            val data = ocsGet(
                session,
                "/ocs/v2.php/apps/activity/api/v2/activity?limit=${boundedActivityLimit(limit)}&sort=desc",
            ).getJSONObject("ocs").getJSONArray("data")
            buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val id = item.optLong("activity_id", -1L).takeIf { it >= 0L } ?: continue
                    add(
                        NextcloudActivity(
                            id = id,
                            app = item.optString("app").ifBlank { "nextcloud" },
                            type = item.optString("type"),
                            subject = item.optString("subject").ifBlank { "Nextcloud activity" },
                            message = item.optString("message").takeIf(String::isNotBlank),
                            objectType = item.optString("object_type").takeIf(String::isNotBlank),
                            objectId = item.optString("object_id").takeIf(String::isNotBlank),
                            objectName = item.optString("object_name").takeIf(String::isNotBlank),
                            link = item.optString("link").takeIf(String::isNotBlank),
                            icon = item.optString("icon").takeIf(String::isNotBlank),
                            dateTime = item.optString("datetime").takeIf(String::isNotBlank),
                        ),
                    )
                }
            }
        }

    override suspend fun loadDocumentEditingCapabilities(
        session: NextcloudSession,
        expectedEtag: String?,
    ): NextcloudConditionalRead<NextcloudDocumentEditingCapabilities> = withContext(Dispatchers.IO) {
        val response = request(
            method = "GET",
            url = session.serverUrl + DIRECT_EDITING_INFO_RELATIVE_PATH,
            session = session,
            ocsRequest = true,
            headers = documentEditingConditionalHeaders(expectedEtag),
            maxResponseBytes = MAX_DOCUMENT_EDITING_CAPABILITIES_BYTES,
            client = noRedirectHttpClient,
        )
        if (response.status == 304) return@withContext NextcloudConditionalRead.NotModified
        check(response.status in 200..299 && response.location == null) {
            "Loading document editing capabilities failed (HTTP ${response.status})."
        }
        val capabilitiesResponse = request(
            method = "GET",
            url = session.serverUrl + NEXTCLOUD_CAPABILITIES_RELATIVE_PATH,
            session = session,
            ocsRequest = true,
            maxResponseBytes = MAX_DOCUMENT_EDITING_CAPABILITIES_BYTES,
            client = noRedirectHttpClient,
        )
        check(capabilitiesResponse.status in 200..299 && capabilitiesResponse.location == null) {
            "Loading direct-editing support failed (HTTP ${capabilitiesResponse.status})."
        }
        NextcloudConditionalRead.Modified(
            value = parseDesktopDocumentEditingCapabilities(
                response.text,
                supportsFileId = parseDesktopDirectEditingSupportsFileId(capabilitiesResponse.text),
            ),
            responseEtag = response.etag,
        )
    }

    override suspend fun beginDocumentEditSession(
        session: NextcloudSession,
        request: NextcloudDocumentEditSessionRequest,
    ): NextcloudDocumentEditSession = withContext(Dispatchers.IO) {
        val response = request(
            method = "POST",
            url = session.serverUrl + DIRECT_EDITING_OPEN_RELATIVE_PATH,
            session = session,
            body = directEditingOpenForm(request),
            contentType = "application/x-www-form-urlencoded",
            ocsRequest = true,
            maxResponseBytes = MAX_DOCUMENT_EDIT_SESSION_RESPONSE_BYTES,
            client = noRedirectHttpClient,
        )
        check(response.status in 200..299 && response.location == null) {
            "Starting the Office edit session failed (HTTP ${response.status})."
        }
        val candidate = JSONObject(response.text)
            .getJSONObject("ocs")
            .getJSONObject("data")
            .getString("url")
        NextcloudDocumentEditSession(
            validatedDirectEditingHandoffUrl(session.serverUrl, candidate),
        )
    }

    override suspend fun listDocumentTemplates(
        session: NextcloudSession,
        editorId: String,
        creatorId: String,
    ): List<NextcloudDocumentTemplate> = withContext(Dispatchers.IO) {
        val primary = request(
            method = "GET",
            url = session.serverUrl + documentTemplatesRelativePath(editorId, creatorId),
            session = session,
            ocsRequest = true,
            maxResponseBytes = MAX_DOCUMENT_TEMPLATES_RESPONSE_BYTES,
            client = noRedirectHttpClient,
        )
        if (primary.status in 200..299 && primary.location == null) {
            return@withContext parseDesktopDocumentTemplates(primary.text, creatorId)
        }
        check(primary.status != 401 && primary.status != 403 && primary.location == null) {
            "Loading document templates failed (HTTP ${primary.status})."
        }
        check(editorId == OFFICE_DIRECT_EDITOR_ID) {
            "Loading document templates failed (HTTP ${primary.status})."
        }
        val fallback = request(
            method = "GET",
            url = session.serverUrl + legacyRichdocumentsTemplatesRelativePath(creatorId),
            session = session,
            ocsRequest = true,
            maxResponseBytes = MAX_DOCUMENT_TEMPLATES_RESPONSE_BYTES,
            client = noRedirectHttpClient,
        )
        check(fallback.status in 200..299 && fallback.location == null) {
            "Loading document templates failed (HTTP ${fallback.status})."
        }
        parseDesktopDocumentTemplates(fallback.text, creatorId)
    }

    override suspend fun listNotes(session: NextcloudSession): List<NextcloudNote> =
        when (val result = listNotesConditionally(session, expectedEtag = null)) {
            is NextcloudConditionalRead.Modified -> result.value
            NextcloudConditionalRead.NotModified -> error("An unconditional Notes list read returned not modified.")
        }

    override suspend fun listNotesConditionally(
        session: NextcloudSession,
        expectedEtag: String?,
    ): NextcloudConditionalRead<List<NextcloudNote>> =
        withContext(Dispatchers.IO) {
            val response = request(
                "GET",
                session.serverUrl + NOTES_LIST_RELATIVE_PATH,
                session,
                headers = notesConditionalHeaders(expectedEtag),
            )
            if (response.status == 304) return@withContext NextcloudConditionalRead.NotModified
            check(response.status in 200..299) { "Loading Notes failed (HTTP ${response.status})." }
            val data = JSONArray(response.text)
            val notes = buildList {
                for (index in 0 until data.length()) {
                    data.optJSONObject(index)?.toNextcloudNote()?.let(::add)
                }
            }.sortedWith(compareByDescending<NextcloudNote> { it.favorite }.thenByDescending { it.modified })
            NextcloudConditionalRead.Modified(notes, response.etag)
        }

    override suspend fun loadNote(session: NextcloudSession, noteId: Long): NextcloudNote =
        when (val result = loadNoteConditionally(session, noteId, expectedEtag = null)) {
            is NextcloudConditionalRead.Modified -> result.value
            NextcloudConditionalRead.NotModified -> error("An unconditional note read returned not modified.")
        }

    override suspend fun loadNoteConditionally(
        session: NextcloudSession,
        noteId: Long,
        expectedEtag: String?,
    ): NextcloudConditionalRead<NextcloudNote> =
        withContext(Dispatchers.IO) {
            val response = request(
                "GET",
                session.serverUrl + notesDetailRelativePath(noteId),
                session,
                headers = notesConditionalHeaders(expectedEtag),
            )
            if (response.status == 304) return@withContext NextcloudConditionalRead.NotModified
            check(response.status != 404) { "The note no longer exists." }
            check(response.status in 200..299) { "Loading the note failed (HTTP ${response.status})." }
            val note = requireNotNull(JSONObject(response.text).toNextcloudNote(response.etag)) {
                "The note response is invalid."
            }
            NextcloudConditionalRead.Modified(note, response.etag)
        }

    override suspend fun updateNote(
        session: NextcloudSession,
        noteId: Long,
        content: String,
        category: String,
        favorite: Boolean,
        expectedEtag: String?,
        title: String?,
    ): NextcloudNote = withContext(Dispatchers.IO) {
        require(content.encodeToByteArray().size.toLong() <= MAX_NOTE_BYTES) {
            "Notes larger than ${MAX_NOTE_BYTES / (1024 * 1024)} MiB cannot be edited in the app."
        }
        val body = JSONObject()
            .put("content", content)
            .put("category", category)
            .put("favorite", favorite)
            .apply { title?.let { put("title", it) } }
            .toString()
        val response = request(
            "PUT",
            session.serverUrl + notesDetailRelativePath(noteId),
            session,
            body,
            "application/json; charset=utf-8",
            headers = expectedEtag?.takeIf(String::isNotBlank)?.let { mapOf("If-Match" to it) }.orEmpty(),
        )
        check(response.status != 412) { "This note changed on the server. Reload it before saving your changes." }
        check(response.status != 423) { "This note is temporarily locked on the server." }
        check(response.status in 200..299) { "Saving the note failed (HTTP ${response.status})." }
        requireNotNull(JSONObject(response.text).toNextcloudNote(response.etag)) { "The saved note response is invalid." }
    }

    override suspend fun createNote(
        session: NextcloudSession,
        title: String,
        content: String,
        category: String,
    ): NextcloudNote = withContext(Dispatchers.IO) {
        val plan = createNoteRequest(title, content, category)
        val response = request(
            plan.method.name,
            session.serverUrl + plan.relativePath,
            session,
            requireNotNull(plan.body).decodeToString(),
            plan.contentType,
        )
        check(response.status in 200..299) { "Creating the note failed (HTTP ${response.status})." }
        requireNotNull(JSONObject(response.text).toNextcloudNote(response.etag)) {
            "The created note response is invalid."
        }
    }

    override suspend fun deleteNote(
        session: NextcloudSession,
        noteId: Long,
        expectedEtag: String?,
    ) = withContext(Dispatchers.IO) {
        val plan = deleteNoteRequest(noteId)
        val response = request(
            plan.method.name,
            session.serverUrl + plan.relativePath,
            session,
            headers = expectedEtag?.takeIf(String::isNotBlank)
                ?.let { etag -> mapOf("If-Match" to etag) }
                .orEmpty(),
        )
        check(response.status != 404) { "The note no longer exists." }
        check(response.status != 412) { "This note changed on the server. Reload it before deleting it." }
        check(response.status != 423) { "This note is temporarily locked on the server." }
        check(response.status in 200..299) { "Deleting the note failed (HTTP ${response.status})." }
    }

    override suspend fun listPeople(session: NextcloudSession, backend: String): List<NextcloudPerson> =
        withContext(Dispatchers.IO) {
            require(backend in setOf("recognize", "facerecognition")) { "Unsupported people backend." }
            val response = request(
                "GET",
                session.serverUrl + "/index.php/apps/memories/api/clusters/${encodePath(backend)}",
                session,
                ocsRequest = true,
            )
            check(response.status in 200..299) { "Loading people from Memories failed (HTTP ${response.status})." }
            val data = JSONArray(response.text)
            buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val id = item.optLong("cluster_id", -1L).takeIf { it >= 0L } ?: continue
                    val rawName = item.optString("name")
                    add(
                        NextcloudPerson(
                            id = id,
                            name = rawName.ifBlank { "Unnamed person" },
                            userId = item.optString("user_id").ifBlank { session.loginName },
                            queryName = rawName.ifBlank { id.toString() },
                            count = item.optInt("count", 0),
                            coverFileId = item.optLong("cover", -1L).takeIf { it >= 0L },
                            coverEtag = item.optString("cover_etag").takeIf(String::isNotBlank),
                            backend = item.optString("cluster_type").ifBlank { backend },
                        ),
                    )
                }
            }.sortedWith(compareByDescending<NextcloudPerson> { it.count }.thenBy { it.name.lowercase() })
        }

    override suspend fun loadPersonCover(session: NextcloudSession, person: NextcloudPerson): ByteArray =
        withContext(Dispatchers.IO) {
            require(person.coverFileId != null) { "This person does not have a selected cover." }
            val response = request(
                "GET",
                session.serverUrl + "/index.php/apps/memories/api/clusters/${encodePath(person.backend)}/preview" +
                    "?name=${person.id}&cover=${person.coverFileId}&cover_etag=${encodeForm(person.coverEtag.orEmpty())}",
                session,
                ocsRequest = true,
                headers = mapOf("Accept" to "image/*"),
            )
            check(response.status in 200..299) { "Loading the person cover failed (HTTP ${response.status})." }
            response.body
        }

    override suspend fun listPersonMedia(session: NextcloudSession, person: NextcloudPerson): List<NextcloudFile> =
        withContext(Dispatchers.IO) {
            val filter = encodeForm("${person.userId}/${person.queryName}")
            val daysResponse = request(
                "GET",
                session.serverUrl + "/index.php/apps/memories/api/days?${encodePath(person.backend)}=$filter" +
                    "&nopreload=1&facerect=1",
                session,
                ocsRequest = true,
            )
            check(daysResponse.status in 200..299) {
                "Loading this person from Memories failed (HTTP ${daysResponse.status})."
            }
            val days = JSONArray(daysResponse.text)
            val files = linkedMapOf<Long, NextcloudFile>()
            val dayIds = buildList {
                for (index in 0 until days.length()) {
                    val day = days.optJSONObject(index) ?: continue
                    val dayId = day.optLong("dayid", -1L).takeIf { it >= 0L } ?: continue
                    add(dayId)
                    if (size >= PERSON_MEDIA_INITIAL_DAY_LIMIT) break
                }
            }
            if (dayIds.isNotEmpty()) {
                val response = request(
                    "GET",
                    session.serverUrl + "/index.php/apps/memories/api/days/${dayIds.joinToString(",")}" +
                        "?${encodePath(person.backend)}=$filter&facerect=1",
                    session,
                    ocsRequest = true,
                )
                if (response.status in 200..299) JSONArray(response.text).appendMemoryFiles(person, files)
            }
            files.values.toList()
        }

    override suspend fun listTalkRooms(session: NextcloudSession): List<TalkRoom> =
        withContext(Dispatchers.IO) {
            val data = ocsGet(session, "/ocs/v2.php/apps/spreed/api/v4/room?noStatusUpdate=1")
                .getJSONObject("ocs").getJSONArray("data")
            buildList {
                for (index in 0 until data.length()) {
                    val room = data.optJSONObject(index) ?: continue
                    val token = room.optString("token").takeIf(String::isNotBlank) ?: continue
                    add(
                        TalkRoom(
                            token,
                            room.optString("displayName").ifBlank { "Conversation" },
                            room.optJSONObject("lastMessage")
                                ?.let { parseTalkMessageJson(it.toString()) }
                                ?.content
                                ?.summary
                                ?.takeIf(String::isNotBlank),
                            room.optInt("unreadMessages", 0),
                        ),
                    )
                }
            }
        }

    override suspend fun listTalkMessages(session: NextcloudSession, token: String): List<TalkMessage> =
        listTalkMessagePage(session, token).messages

    override suspend fun listTalkMessagePage(
        session: NextcloudSession,
        token: String,
        olderCursor: Long?,
        limit: Int,
    ): TalkMessagePage =
        withContext(Dispatchers.IO) {
            val response = request(
                "GET",
                session.serverUrl + talkMessageHistoryPath(token, olderCursor, limit),
                session,
                ocsRequest = true,
            )
            val data = if (response.status == 304) {
                JSONArray()
            } else {
                check(response.status in 200..299) {
                    "Loading Talk history failed (HTTP ${response.status})."
                }
                JSONObject(response.text).getJSONObject("ocs").getJSONArray("data")
            }
            val messages = buildList {
                for (index in 0 until data.length()) {
                    val message = data.optJSONObject(index) ?: continue
                    parseTalkMessageJson(message.toString())?.let(::add)
                }
            }
            val nextCursor = response.chatLastGiven?.toLongOrNull()
            TalkMessagePage(
                messages = messages,
                olderCursor = nextCursor,
                hasMoreHistory = response.status != 304 && nextCursor != null,
            )
        }

    override suspend fun sendTalkMessage(session: NextcloudSession, token: String, message: String) =
        withContext(Dispatchers.IO) {
            val response = request(
                "POST",
                session.serverUrl + "/ocs/v2.php/apps/spreed/api/v1/chat/${encodePath(token)}?format=json",
                session,
                "message=" + encodeForm(message),
                "application/x-www-form-urlencoded",
                true,
            )
            check(response.status in 200..299) { "Sending the Talk message failed (HTTP ${response.status})." }
            Unit
        }

    override suspend fun revokeSession(session: NextcloudSession) = withContext(Dispatchers.IO) {
        request("DELETE", session.serverUrl + "/ocs/v2.php/core/apppassword", session, ocsRequest = true)
        Unit
    }

    private fun ocsGet(session: NextcloudSession, path: String): JSONObject {
        val separator = if ('?' in path) '&' else '?'
        val response = request("GET", session.serverUrl + path + separator + "format=json", session, ocsRequest = true)
        check(response.status in 200..299) { "Nextcloud API request failed (HTTP ${response.status})." }
        return JSONObject(response.text)
    }

    private fun loadFileEtag(session: NextcloudSession, userId: String, path: String): String? {
        val response = request(
            "PROPFIND",
            buildNextcloudFileUrl(session.serverUrl, userId, path),
            session,
            DAV_ETAG_PROPERTY,
            "application/xml; charset=utf-8",
            headers = mapOf("Depth" to "0", "Accept" to "application/xml"),
        )
        if (response.status != 207) return null
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(response.body))
            .documentElement.firstText(DAV, "getetag")
    }

    private fun request(
        method: String,
        url: String,
        session: NextcloudSession? = null,
        body: String? = null,
        contentType: String? = null,
        ocsRequest: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        rawBody: ByteArray? = null,
        maxResponseBytes: Long = MAX_API_RESPONSE_BYTES,
        client: OkHttpClient = httpClient,
        streamingBody: RequestBody? = null,
    ): HttpResponse {
        val requestBody = when {
            streamingBody != null -> streamingBody
            rawBody != null -> rawBody.toRequestBody(contentType?.toMediaType())
            body != null -> body.toRequestBody(contentType?.toMediaType())
            method == "POST" || method == "PUT" || method == "PATCH" -> byteArrayOf().toRequestBody(null)
            else -> null
        }
        val builder = Request.Builder().url(url).method(method, requestBody)
            .header("Accept", "application/json").header("User-Agent", USER_AGENT)
        if (ocsRequest) builder.header("OCS-APIRequest", "true")
        headers.forEach(builder::header)
        session?.let {
            val encoded = Base64.getEncoder().encodeToString("${it.loginName}:${it.appPassword}".toByteArray())
            builder.header("Authorization", "Basic $encoded")
        }
        return client.newCall(builder.build()).execute().use { response ->
            val responseBody = response.body
            val contentLength = responseBody.contentLength()
            val readLimit = if (response.isSuccessful) maxResponseBytes else MAX_ERROR_RESPONSE_BYTES
            check(contentLength <= readLimit || contentLength == -1L) {
                "The server response is larger than the allowed ${formatByteLimit(readLimit)} limit."
            }
            HttpResponse(
                response.code,
                responseBody.byteStream().readBounded(readLimit),
                responseBody.contentType()?.toString(),
                response.header("ETag") ?: response.header("OC-Etag"),
                response.header("Location"),
                response.header("X-Chat-Last-Given"),
                response.header("Content-Range"),
            )
        }
    }

    private fun java.io.InputStream.readBounded(maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_CAPACITY.toLong()).toInt())
        val buffer = ByteArray(DEFAULT_BUFFER_CAPACITY)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read == -1) break
            total += read
            check(total <= maxBytes) {
                "The server response is larger than the allowed ${formatByteLimit(maxBytes)} limit."
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun java.io.InputStream.copyBoundedTo(
        output: java.io.OutputStream,
        maxBytes: Long,
    ): Long {
        require(maxBytes > 0L)
        val buffer = ByteArray(DEFAULT_BUFFER_CAPACITY)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read == -1) break
            total += read
            check(total <= maxBytes) {
                "The Deck attachment is larger than the external handoff limit."
            }
            output.write(buffer, 0, read)
        }
        return total
    }

    private fun formatByteLimit(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MiB"
        bytes >= 1024 -> "${bytes / 1024} KiB"
        else -> "$bytes bytes"
    }

    private fun parseDavFiles(xml: ByteArray, userId: String): List<NextcloudFile> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val responses = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
            .getElementsByTagNameNS(DAV, "response")
        return buildList {
            for (index in 0 until responses.length) {
                val response = responses.item(index)
                val name = response.firstText(DAV, "displayname") ?: continue
                val href = URLDecoder.decode(response.firstText(DAV, "href").orEmpty(), StandardCharsets.UTF_8)
                val path = href.substringAfter("/files/$userId/", name).trimEnd('/').ifBlank { name }
                add(
                    NextcloudFile(
                        path = path,
                        name = name,
                        isDirectory = response.childCount(DAV, "collection") > 0,
                        mimeType = response.firstText(DAV, "getcontenttype"),
                        size = response.firstText(OC, "size")?.toLongOrNull()
                            ?: response.firstText(DAV, "getcontentlength")?.toLongOrNull(),
                        lastModified = response.firstText(DAV, "getlastmodified"),
                        fileId = response.firstText(OC, "fileid")?.toLongOrNull(),
                        hasPreview = response.firstText(NC, "has-preview") == "true",
                        etag = response.firstText(DAV, "getetag"),
                        permissions = response.firstText(OC, "permissions"),
                    ),
                )
            }
        }
    }

    private fun org.w3c.dom.Node.firstText(namespace: String, name: String): String? =
        (this as? org.w3c.dom.Element)?.getElementsByTagNameNS(namespace, name)?.item(0)
            ?.textContent?.takeIf(String::isNotBlank)

    private fun org.w3c.dom.Node.childCount(namespace: String, name: String): Int =
        (this as? org.w3c.dom.Element)?.getElementsByTagNameNS(namespace, name)?.length ?: 0

    private fun normalizeServerUrl(value: String): String {
        val candidate = value.trim().let { if ("://" in it) it else "https://$it" }
        val uri = URI(candidate)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "Enter a valid secure https:// server address." }
        return candidate.trimEnd('/').removeSuffix("/index.php")
    }

    private fun secretTool(command: String, server: String, login: String): String? = runCatching {
        val process = ProcessBuilder(
            "secret-tool", command, "application", APP_ID, "server", server, "login", login,
        ).start()
        val value = process.inputStream.bufferedReader().use { it.readText().trim() }
        if (process.waitFor() == 0) value.takeIf(String::isNotBlank) else null
    }.getOrNull()

    private fun JSONArray.toAppEntries(): List<NextcloudAppEntry> = buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val id = item.optString("id").takeIf(String::isNotBlank) ?: continue
            add(NextcloudAppEntry(id, item.optString("name").ifBlank { readableName(id) }, item.optString("href").takeIf(String::isNotBlank)))
        }
    }

    private fun JSONObject.toCapabilityEntries(): List<NextcloudAppEntry> = keys().asSequence()
        .filterNot { it in setOf("core", "theming") }
        .map { NextcloudAppEntry(it, readableName(it), null) }.sortedBy(NextcloudAppEntry::name).toList()

    private fun readableName(id: String): String = id.replace('_', ' ').split(' ')
        .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private fun JSONObject.toNextcloudNote(responseEtag: String? = null): NextcloudNote? {
        val id = optLong("id", -1L).takeIf { it >= 0L } ?: return null
        return NextcloudNote(
            id = id,
            title = optString("title").ifBlank { "Untitled note" },
            modified = optLong("modified", 0L),
            category = optString("category"),
            favorite = optBoolean("favorite", false),
            readOnly = optBoolean("readonly", false),
            content = if (has("content")) optString("content") else null,
            etag = resolvedNoteEtag(responseEtag, optString("etag")),
            internalPath = optString("internalPath").takeIf(String::isNotBlank),
            isShared = optBoolean("isShared", false),
        )
    }

    private fun JSONArray.appendMemoryFiles(person: NextcloudPerson, target: MutableMap<Long, NextcloudFile>) {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val fileId = item.optLong("fileid", -1L).takeIf { it >= 0L } ?: continue
            target.putIfAbsent(
                fileId,
                syntheticMemoriesPersonFile(
                    personId = person.id.toString(),
                    fileId = fileId,
                    name = item.optString("basename").ifBlank { "Photo $fileId" },
                    mimeType = item.optString("mimetype").takeIf(String::isNotBlank),
                    lastModified = item.optLong("epoch", 0L).takeIf { it > 0L }?.toString(),
                    etag = item.optString("etag").takeIf(String::isNotBlank),
                ),
            )
        }
    }

    private fun encodePath(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    private fun encodeForm(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
    private fun escapeXml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private data class HttpResponse(
        val status: Int,
        val body: ByteArray,
        val contentType: String? = null,
        val etag: String? = null,
        val location: String? = null,
        val chatLastGiven: String? = null,
        val contentRange: String? = null,
    ) {
        val text: String get() = body.toString(StandardCharsets.UTF_8)
    }

    private companion object {
        const val APP_ID = "dev.obiente.nextcloudnative"
        const val KEY_THEME = "theme"
        const val KEY_LAST_OPENED_APP = "last_opened_app"
        const val KEY_SERVER = "server"
        const val KEY_LOGIN = "login"
        const val USER_AGENT = "Nextcloud-Native/0.1.0 (Desktop)"
        const val DAV = "DAV:"
        const val OC = "http://owncloud.org/ns"
        const val NC = "http://nextcloud.org/ns"
        const val DEFAULT_BUFFER_CAPACITY = 8 * 1024
        const val MAX_API_RESPONSE_BYTES = 16L * 1024L * 1024L
        const val MAX_ERROR_RESPONSE_BYTES = 64L * 1024L
        const val PERSON_MEDIA_INITIAL_DAY_LIMIT = 12
        const val MAX_DOCUMENT_EDITING_CAPABILITIES_BYTES = 512L * 1024L
        const val MAX_DOCUMENT_EDIT_SESSION_RESPONSE_BYTES = 64L * 1024L
        const val MAX_DOCUMENT_TEMPLATES_RESPONSE_BYTES = 2L * 1024L * 1024L
        val DAV_PROPERTIES = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns"><d:prop>
              <d:displayname/><d:getcontenttype/><d:getlastmodified/><d:getcontentlength/><d:getetag/><d:resourcetype/><oc:fileid/><oc:size/><oc:permissions/><nc:has-preview/>
            </d:prop></d:propfind>
        """.trimIndent()
        val DAV_ETAG_PROPERTY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:getetag/></d:prop></d:propfind>
        """.trimIndent()
    }
}

internal fun parseDesktopFileVersionDavRecords(xml: ByteArray): List<FileVersionDavRecord> {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }
    val responses = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        .getElementsByTagNameNS(FILE_VERSION_DESKTOP_DAV_NAMESPACE, "response")
    return buildList {
        for (index in 0 until responses.length) {
            val response = responses.item(index)
            val properties = response.successfulFileVersionPropertyRoot() ?: continue
            add(
                FileVersionDavRecord(
                    href = response.fileVersionFirstText(FILE_VERSION_DESKTOP_DAV_NAMESPACE, "href").orEmpty(),
                    contentLength = properties.fileVersionFirstText(
                        FILE_VERSION_DESKTOP_DAV_NAMESPACE,
                        "getcontentlength",
                    ),
                    lastModified = properties.fileVersionFirstText(
                        FILE_VERSION_DESKTOP_DAV_NAMESPACE,
                        "getlastmodified",
                    ),
                    etag = properties.fileVersionFirstText(FILE_VERSION_DESKTOP_DAV_NAMESPACE, "getetag"),
                    author = properties.fileVersionFirstText(FILE_VERSION_DESKTOP_NC_NAMESPACE, "version-author"),
                    label = properties.fileVersionFirstText(FILE_VERSION_DESKTOP_NC_NAMESPACE, "version-label"),
                ),
            )
        }
    }
}

private fun org.w3c.dom.Node.successfulFileVersionPropertyRoot(): org.w3c.dom.Node? {
    val element = this as? org.w3c.dom.Element ?: return null
    val propstats = element.getElementsByTagNameNS(FILE_VERSION_DESKTOP_DAV_NAMESPACE, "propstat")
    if (propstats.length > 0) {
        for (index in 0 until propstats.length) {
            val propstat = propstats.item(index)
            val status = propstat.fileVersionFirstText(FILE_VERSION_DESKTOP_DAV_NAMESPACE, "status").orEmpty()
            if (status.isFileVersionDavSuccessStatus()) return propstat
        }
        return null
    }
    return if (
        element.getElementsByTagNameNS(FILE_VERSION_DESKTOP_DAV_NAMESPACE, "status")
            .item(0)?.textContent.orEmpty().isFileVersionDavSuccessStatus()
    ) {
        element
    } else {
        null
    }
}

private fun String.isFileVersionDavSuccessStatus(): Boolean =
    trim().split(' ').any { token -> token.toIntOrNull()?.let { it in 200..299 } == true }

private fun org.w3c.dom.Node.fileVersionFirstText(namespace: String, localName: String): String? =
    (this as? org.w3c.dom.Element)
        ?.getElementsByTagNameNS(namespace, localName)
        ?.item(0)
        ?.textContent
        ?.takeIf(String::isNotBlank)

private const val FILE_VERSION_DESKTOP_DAV_NAMESPACE = "DAV:"
private const val FILE_VERSION_DESKTOP_NC_NAMESPACE = "http://nextcloud.org/ns"

internal fun parseDesktopSystemTagsDavResponse(xml: ByteArray): List<NextcloudSystemTag> {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }
    val responses = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        .getElementsByTagNameNS(SYSTEM_TAG_DESKTOP_DAV_NAMESPACE, "response")
    val records = buildList {
        for (index in 0 until responses.length) {
            val response = responses.item(index)
            add(
                SystemTagDavRecord(
                    href = response.systemTagFirstText(SYSTEM_TAG_DESKTOP_DAV_NAMESPACE, "href").orEmpty(),
                    id = response.systemTagFirstText(SYSTEM_TAG_DESKTOP_OC_NAMESPACE, "id"),
                    displayName = response.systemTagFirstText(SYSTEM_TAG_DESKTOP_OC_NAMESPACE, "display-name"),
                    userVisible = response.systemTagFirstText(SYSTEM_TAG_DESKTOP_OC_NAMESPACE, "user-visible"),
                    userAssignable = response.systemTagFirstText(SYSTEM_TAG_DESKTOP_OC_NAMESPACE, "user-assignable"),
                    canAssign = response.systemTagFirstText(SYSTEM_TAG_DESKTOP_OC_NAMESPACE, "can-assign"),
                    color = response.systemTagFirstText(SYSTEM_TAG_DESKTOP_NC_NAMESPACE, "color"),
                    etag = response.systemTagFirstText(SYSTEM_TAG_DESKTOP_DAV_NAMESPACE, "getetag"),
                ),
            )
        }
    }
    return normalizeSystemTagsDavResponse(records).tags
}

private fun org.w3c.dom.Node.systemTagFirstText(namespace: String, localName: String): String? =
    (this as? org.w3c.dom.Element)
        ?.getElementsByTagNameNS(namespace, localName)
        ?.item(0)
        ?.textContent
        ?.takeIf(String::isNotBlank)

private const val SYSTEM_TAG_DESKTOP_DAV_NAMESPACE = "DAV:"
private const val SYSTEM_TAG_DESKTOP_OC_NAMESPACE = "http://owncloud.org/ns"
private const val SYSTEM_TAG_DESKTOP_NC_NAMESPACE = "http://nextcloud.org/ns"
