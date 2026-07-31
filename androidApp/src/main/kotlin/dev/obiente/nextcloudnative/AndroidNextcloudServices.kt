package dev.obiente.nextcloudnative

import android.content.Context
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Base64
import android.util.Log
import dev.obiente.nextcloudnative.app.AcquiredOpenApiContract
import dev.obiente.nextcloudnative.app.AcquiredOpenApiContractSourceKind
import dev.obiente.nextcloudnative.app.AcquiredContractKind
import dev.obiente.nextcloudnative.app.DeckAttachment
import dev.obiente.nextcloudnative.app.DeckAttachmentOpenTarget
import dev.obiente.nextcloudnative.app.DeckCardDraftKey
import dev.obiente.nextcloudnative.app.DurableUploadEnqueueResult
import dev.obiente.nextcloudnative.app.DurableUploadScope
import dev.obiente.nextcloudnative.app.DurableUploadStatus
import dev.obiente.nextcloudnative.app.LoginChallenge
import dev.obiente.nextcloudnative.app.MAX_EDITABLE_TEXT_BYTES
import dev.obiente.nextcloudnative.app.MAX_FILE_IDENTITY_SEARCH_BATCH
import dev.obiente.nextcloudnative.app.MAX_NOTE_BYTES
import dev.obiente.nextcloudnative.app.MAX_TALK_MESSAGE_PAGE_SIZE
import dev.obiente.nextcloudnative.app.NextcloudApiCachePolicy
import dev.obiente.nextcloudnative.app.NextcloudApiRequest
import dev.obiente.nextcloudnative.app.NextcloudApiResponse
import dev.obiente.nextcloudnative.app.LocalUploadFile
import dev.obiente.nextcloudnative.app.LocalUploadSelectionResult
import dev.obiente.nextcloudnative.app.NextcloudMultipartUploadRequest
import dev.obiente.nextcloudnative.app.GroupwareDavRequest
import dev.obiente.nextcloudnative.app.NextcloudAppEntry
import dev.obiente.nextcloudnative.app.NextcloudActivity
import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.NextcloudFileContent
import dev.obiente.nextcloudnative.app.NextcloudFileRangeSession
import dev.obiente.nextcloudnative.app.NextcloudFileListing
import dev.obiente.nextcloudnative.app.NextcloudFileListingHttpException
import dev.obiente.nextcloudnative.app.NextcloudFileListingSource
import dev.obiente.nextcloudnative.app.FileVersionDavRecord
import dev.obiente.nextcloudnative.app.FileVersionHistory
import dev.obiente.nextcloudnative.app.NextcloudFileVersion
import dev.obiente.nextcloudnative.app.FileOfflineAvailability
import dev.obiente.nextcloudnative.app.FileOfflineCenterActionResult
import dev.obiente.nextcloudnative.app.FileOfflineCenterSnapshot
import dev.obiente.nextcloudnative.app.FileOfflineKey
import dev.obiente.nextcloudnative.app.FileSyncCenterActionResult
import dev.obiente.nextcloudnative.app.FileSyncCenterSnapshot
import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncDecisionChoice
import dev.obiente.nextcloudnative.app.FileSyncLocalRoot
import dev.obiente.nextcloudnative.app.MediaSyncFolderDiscovery
import dev.obiente.nextcloudnative.app.MAX_MEDIA_BACKUP_STATUS_PATHS
import dev.obiente.nextcloudnative.app.MediaBackupStatus
import dev.obiente.nextcloudnative.app.MediaInformation
import dev.obiente.nextcloudnative.app.MediaBackupLedgerCursor
import dev.obiente.nextcloudnative.app.MediaTransferCenterState
import dev.obiente.nextcloudnative.app.MediaTransferSection
import dev.obiente.nextcloudnative.app.mediaTransferCenterState
import dev.obiente.nextcloudnative.app.basicMediaInformation
import dev.obiente.nextcloudnative.app.transferState
import dev.obiente.nextcloudnative.app.MediaSyncFolderPreview
import dev.obiente.nextcloudnative.app.MediaSyncFolderSuggestion
import dev.obiente.nextcloudnative.app.filesByIdDavSearchRequest
import dev.obiente.nextcloudnative.app.ExternalFileHandoffAction
import dev.obiente.nextcloudnative.app.ExternalFileHandoffCapability
import dev.obiente.nextcloudnative.app.ExternalFileHandoffResult
import dev.obiente.nextcloudnative.app.ExternalFileHandoffSupport
import dev.obiente.nextcloudnative.app.MAX_EXTERNAL_FILE_HANDOFF_BYTES
import dev.obiente.nextcloudnative.app.NextcloudFileMutation
import dev.obiente.nextcloudnative.app.NextcloudFileMutationResult
import dev.obiente.nextcloudnative.app.NativeMediaCollectionTransportRequest
import dev.obiente.nextcloudnative.app.NextcloudNote
import dev.obiente.nextcloudnative.app.createNoteRequest
import dev.obiente.nextcloudnative.app.deleteNoteRequest
import dev.obiente.nextcloudnative.app.NextcloudPlatformServices
import dev.obiente.nextcloudnative.app.NextcloudPerson
import dev.obiente.nextcloudnative.app.syntheticMemoriesPersonFile
import dev.obiente.nextcloudnative.app.NextcloudServerInfo
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.NextcloudSystemTag
import dev.obiente.nextcloudnative.app.SavedTextFile
import dev.obiente.nextcloudnative.app.SystemTagDavRecord
import dev.obiente.nextcloudnative.app.TalkMessage
import dev.obiente.nextcloudnative.app.TalkMessagePage
import dev.obiente.nextcloudnative.app.TalkRoom
import dev.obiente.nextcloudnative.app.ThemePreference
import dev.obiente.nextcloudnative.app.PlatformCapability
import dev.obiente.nextcloudnative.app.PlatformCapabilityStatus
import dev.obiente.nextcloudnative.app.AndroidDirectRelease
import dev.obiente.nextcloudnative.app.AndroidUpdateChannel
import dev.obiente.nextcloudnative.app.AppUpdateCheckResult
import dev.obiente.nextcloudnative.app.AppUpdateInstallResult
import dev.obiente.nextcloudnative.app.AppUpdateInstallState
import dev.obiente.nextcloudnative.app.AppUpdatePreferences
import dev.obiente.nextcloudnative.app.AppUpdateRelease
import dev.obiente.nextcloudnative.app.AppUpdateSupport
import dev.obiente.nextcloudnative.app.ProjectNewsResult
import dev.obiente.nextcloudnative.app.ProjectNewsImage
import dev.obiente.nextcloudnative.app.PeopleMutationSurface
import dev.obiente.nextcloudnative.app.PeopleTransportAuthorization
import dev.obiente.nextcloudnative.app.PeopleTransportRequest
import dev.obiente.nextcloudnative.app.PersistedDeckCardDraft
import dev.obiente.nextcloudnative.app.boundedPreviewDimension
import dev.obiente.nextcloudnative.app.boundedActivityLimit
import dev.obiente.nextcloudnative.app.buildNextcloudFileUrl
import dev.obiente.nextcloudnative.app.buildNextcloudApiUrl
import dev.obiente.nextcloudnative.app.prepareMultipartUpload
import dev.obiente.nextcloudnative.app.buildPeopleMutationUrl
import dev.obiente.nextcloudnative.app.conflictConditionHeaders
import dev.obiente.nextcloudnative.app.boundedFileVersionContentRequest
import dev.obiente.nextcloudnative.app.encodedFormBody
import dev.obiente.nextcloudnative.app.fileOperationException
import dev.obiente.nextcloudnative.app.fileVersionHistoryRequest
import dev.obiente.nextcloudnative.app.fileVersionRestoreRequest
import dev.obiente.nextcloudnative.app.historicalFileCopyName
import dev.obiente.nextcloudnative.app.isExactHttpByteContentRange
import dev.obiente.nextcloudnative.app.normalizeFileVersionHistory
import dev.obiente.nextcloudnative.app.requireMatchingFileVersion
import dev.obiente.nextcloudnative.app.requireSafeFileRangeEtag
import dev.obiente.nextcloudnative.app.discoverRecognizeBridge
import dev.obiente.nextcloudnative.app.DynamicApiRequestCoalescer
import dev.obiente.nextcloudnative.app.dynamicReadCacheIdentity
import dev.obiente.nextcloudnative.app.collectMediaSearchDavPages
import dev.obiente.nextcloudnative.app.collectMediaTimelineDavPage
import dev.obiente.nextcloudnative.app.mediaSearchDavRequests
import dev.obiente.nextcloudnative.app.MediaSearchDavTransportResponse
import dev.obiente.nextcloudnative.app.MediaTimelineDavCarryoverStore
import dev.obiente.nextcloudnative.app.MemoriesPreferredTimelineReadService
import dev.obiente.nextcloudnative.app.MemoriesTimelineNavigationLoadResult
import dev.obiente.nextcloudnative.app.MemoriesTimelineNavigationSnapshot
import dev.obiente.nextcloudnative.app.PhotoMediaQueryOwner
import dev.obiente.nextcloudnative.app.PhotoTimelineCursor
import dev.obiente.nextcloudnative.app.PhotoTimelineMonthResolver
import dev.obiente.nextcloudnative.app.PhotoTimelinePage
import dev.obiente.nextcloudnative.app.RawMediaSearchCompatibilityPolicy
import dev.obiente.nextcloudnative.app.isRawPhoto
import dev.obiente.nextcloudnative.app.mergeMediaSearchResultPages
import dev.obiente.nextcloudnative.app.photoMediaCarryoverScope
import dev.obiente.nextcloudnative.app.toPhotoTimelineEntryOrNull
import dev.obiente.nextcloudnative.app.normalizeSystemTagsDavResponse
import dev.obiente.nextcloudnative.app.parseNextcloudFileSharingCapabilities
import dev.obiente.nextcloudnative.app.parseTalkMessageJson
import dev.obiente.nextcloudnative.app.requireSafe
import dev.obiente.nextcloudnative.app.systemTagsDavDiscoveryRequest
import dev.obiente.nextcloudnative.app.toWebDavMutationSpec
import dev.obiente.nextcloudnative.contracts.ContractAcquisitionRequest
import dev.obiente.nextcloudnative.contracts.CachedDynamicApiResponse
import dev.obiente.nextcloudnative.contracts.DynamicApiResponseCache
import dev.obiente.nextcloudnative.contracts.OpenApiContractSourceKind
import dev.obiente.nextcloudnative.contracts.FileAppStoreCatalogCache
import dev.obiente.nextcloudnative.contracts.FileVerifiedContractCache
import dev.obiente.nextcloudnative.contracts.SignedAppStoreContractAcquirer
import dev.obiente.nextcloudnative.contracts.VerifiedContractKind
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

internal fun resolveAndroidNextcloudRedirectLocation(
    requestUrl: HttpUrl,
    serverUrl: String,
    location: String?,
): String? {
    val target = location?.let(requestUrl::resolve) ?: return null
    if (target.fragment != null) return null
    val account = serverUrl.toHttpUrlOrNull() ?: return null
    if (
        target.scheme != account.scheme ||
        target.host != account.host ||
        target.port != account.port
    ) {
        return null
    }
    val accountPath = account.encodedPath.trimEnd('/').takeUnless { it == "/" }.orEmpty()
    if (
        accountPath.isNotEmpty() &&
        target.encodedPath != accountPath &&
        !target.encodedPath.startsWith("$accountPath/")
    ) {
        return null
    }
    val relativePath = target.encodedPath.removePrefix(accountPath)
    if (!relativePath.startsWith('/') || relativePath.startsWith("//")) return null
    return buildString {
        append(relativePath)
        target.encodedQuery?.let { query ->
            append('?')
            append(query)
        }
    }
}

internal suspend fun executeAndroidDynamicApiGet(
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

internal class AndroidNextcloudServices(
    context: Context,
    private val fileSyncRootPicker: AndroidFileSyncRootPicker? = null,
    private val localUploadPicker: AndroidLocalUploadPicker? = null,
    private val requestPlatformPermissions: ((Array<String>) -> Boolean)? = null,
    private val onThemePreferenceChanged: (ThemePreference) -> Unit = {},
) : NextcloudPlatformServices {
    private val appContext = context.applicationContext
    private val activity = context as? Activity
    private val preferences = appContext.getSharedPreferences("nextcloud_native", Context.MODE_PRIVATE)
    private val sessionCipher = SessionCipher()
    private val httpClient = OkHttpClient()
    private val noRedirectHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val contractAcquirer = SignedAppStoreContractAcquirer(
        catalogCache = FileAppStoreCatalogCache(File(appContext.filesDir, "contracts/catalogs")),
        verifiedContractCache = FileVerifiedContractCache(File(appContext.filesDir, "contracts/verified")),
    )
    private val fileOfflineRepository = AndroidFileOfflineRepository(appContext)
    private val fileReadCache = AndroidFileReadCache(File(appContext.cacheDir, "files-read-v1"))
    private val dynamicApiReadCache = DynamicApiResponseCache(File(appContext.cacheDir, "dynamic-api-v1"))
    private val nativeMediaPreviewCache = AndroidNativeMediaPreviewCache(
        File(appContext.cacheDir, "native-media-previews-v1"),
    )
    private val nativeMediaPreviewDecodeMutex = Mutex()
    private val dynamicApiRequestCoalescer = DynamicApiRequestCoalescer<NextcloudApiResponse>()
    private val mediaTimelineCarryoverStore = MediaTimelineDavCarryoverStore()
    private val memoriesTimeline = MemoriesPreferredTimelineReadService { session, request ->
        executeNextcloudApi(session, request)
    }
    private val fileSyncEngine = AndroidFileSyncEngine(appContext)
    private val mediaSyncFolderDetector = AndroidMediaSyncFolderDetector(appContext)
    private val externalFileHandoff = AndroidExternalFileHandoff(appContext)
    private val platformCapabilities = AndroidPlatformCapabilities(
        context = appContext,
        activity = activity,
        requestPermissions = requestPlatformPermissions,
    )
    private val projectContent = AndroidProjectContentClient(appContext, activity)
    private val durableMultipartUploads = AndroidDurableMultipartUploads(appContext)
    private val deckCardDrafts = AndroidDeckCardDraftStore(appContext)

    override val supportsFileOfflineStorage: Boolean = true
    override val supportsRecursiveFileOfflineStorage: Boolean = true
    override val supportsBidirectionalFileSync: Boolean = fileSyncRootPicker != null
    override val externalFileHandoffSupport: ExternalFileHandoffSupport = ExternalFileHandoffSupport.Available(
        ExternalFileHandoffCapability(
            supportedActions = ExternalFileHandoffAction.entries.toSet(),
            maximumFileBytes = MAX_EXTERNAL_FILE_HANDOFF_BYTES,
        ),
    )

    override fun platformCapabilities(): List<PlatformCapabilityStatus> = platformCapabilities.statuses()

    override fun requestPlatformCapability(capability: PlatformCapability): Boolean =
        platformCapabilities.request(capability)

    override suspend fun loadProjectNews(forceRefresh: Boolean): ProjectNewsResult =
        withContext(Dispatchers.IO) { projectContent.loadNews(forceRefresh) }

    override suspend fun loadProjectNewsImage(image: ProjectNewsImage): ByteArray =
        withContext(Dispatchers.IO) { projectContent.loadNewsImage(image) }

    override fun appUpdateSupport(): AppUpdateSupport = projectContent.support()

    override fun loadAppUpdateChannel(): AndroidUpdateChannel = projectContent.updateChannel()

    override fun saveAppUpdateChannel(channel: AndroidUpdateChannel): Boolean {
        val saved = projectContent.saveUpdateChannel(channel)
        if (saved) AndroidAppUpdateWork.schedule(appContext, projectContent.updatePreferences())
        return saved
    }

    override fun loadAppUpdatePreferences(): AppUpdatePreferences =
        projectContent.updatePreferences()

    override fun saveAppUpdatePreferences(preferences: AppUpdatePreferences): Boolean {
        projectContent.saveUpdatePreferences(preferences)
        AndroidAppUpdateWork.schedule(appContext, preferences)
        return true
    }

    override fun appUpdateNotificationDeliveryAllowed(): Boolean =
        notificationDeliveryAllowed(appContext, CHANNEL_APP_UPDATES)

    override fun requestAppUpdateNotificationDelivery(): Boolean {
        if (!notificationPermissionAllowed(appContext)) {
            return platformCapabilities.request(PlatformCapability.Notifications)
        }
        val host = activity ?: return false
        host.startActivity(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_APP_UPDATES)
            },
        )
        return true
    }

    override fun observeAppUpdateCheckResult(): Flow<AppUpdateCheckResult?> =
        projectContent.observeUpdateCheckResult()

    override suspend fun checkForAppUpdate(
        channel: AndroidUpdateChannel,
        automatic: Boolean,
    ): AppUpdateCheckResult = withContext(Dispatchers.IO) {
        val updatePreferences = projectContent.updatePreferences()
        if (
            automatic &&
            !automaticAndroidUpdateCheckAllowed(
                preferences = updatePreferences,
                networkMetered = isAndroidActiveNetworkMetered(appContext),
            )
        ) {
            return@withContext AppUpdateCheckResult.Unavailable(projectContent.support())
        }
        val result = projectContent.checkForUpdate(channel)
        if (automatic && result is AppUpdateCheckResult.Available) {
            AndroidAppUpdateNotifier(appContext).notifyIfNeeded(
                channel = channel,
                update = result,
                enabled = updatePreferences.notifications,
            )
        }
        result
    }

    override fun observeAppUpdateInstallState(): Flow<AppUpdateInstallState> =
        projectContent.observeUpdateState()

    override suspend fun beginAppUpdate(release: AppUpdateRelease): AppUpdateInstallResult =
        withContext(Dispatchers.IO) { projectContent.beginUpdate(release) }

    override fun cancelAppUpdate(): Boolean = projectContent.cancelUpdate()

    override fun loadThemePreference(): ThemePreference = runCatching {
        ThemePreference.valueOf(preferences.getString(KEY_THEME, ThemePreference.System.name).orEmpty())
    }.getOrDefault(ThemePreference.System)

    override fun saveThemePreference(preference: ThemePreference) {
        preferences.edit().putString(KEY_THEME, preference.name).apply()
        onThemePreferenceChanged(preference)
    }

    override fun loadLastOpenedAppId(): String = preferences.getString(KEY_LAST_OPENED_APP, "files") ?: "files"

    override fun saveLastOpenedAppId(appId: String) {
        preferences.edit().putString(KEY_LAST_OPENED_APP, appId).apply()
    }

    override fun loadSession(): NextcloudSession? {
        return ANDROID_FILE_SYNC_SESSION_SCHEDULING_GUARD.restorePersistedSession(
            load = {
                val encrypted = preferences.getString(KEY_SESSION, null)
                    ?: return@restorePersistedSession null
                runCatching {
                    val json = JSONObject(sessionCipher.decrypt(encrypted))
                    NextcloudSession(
                        serverUrl = json.getString("serverUrl"),
                        loginName = json.getString("loginName"),
                        appPassword = json.getString("appPassword"),
                    )
                }.getOrNull()
            },
            accountIdOf = NextcloudDocumentIds::accountKey,
        )
    }

    override fun saveSession(session: NextcloudSession) {
        val previousAccountId = loadSession()?.let(NextcloudDocumentIds::cacheAccountId)
        val replacementAccountId = NextcloudDocumentIds.cacheAccountId(session)
        val json = JSONObject()
            .put("serverUrl", session.serverUrl)
            .put("loginName", session.loginName)
            .put("appPassword", session.appPassword)
            .toString()
        val encrypted = sessionCipher.encrypt(json)
        val scheduler = AndroidFileSyncScheduler(appContext)
        ANDROID_FILE_SYNC_SESSION_SCHEDULING_GUARD.replaceSession(
            replacementAccountId = NextcloudDocumentIds.accountKey(session),
            persist = {
                preferences.edit()
                    .putString(KEY_SESSION, encrypted)
                    .remove(KEY_TEST_READ_ONLY)
                    .apply()
            },
            cancelAll = scheduler::cancelAll,
        )
        if (previousAccountId != null && previousAccountId != replacementAccountId) {
            nativeMediaPreviewCache.clearAccount(previousAccountId)
        }
        notifyDocumentsRootsChanged()
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

    override fun clearSession() {
        val accountId = loadSession()?.let(NextcloudDocumentIds::cacheAccountId)
        val scheduler = AndroidFileSyncScheduler(appContext)
        ANDROID_FILE_SYNC_SESSION_SCHEDULING_GUARD.clearSession(
            persist = {
                preferences.edit()
                    .remove(KEY_SESSION)
                    .remove(KEY_TEST_READ_ONLY)
                    .apply()
            },
            cancelAll = scheduler::cancelAll,
        )
        accountId?.let(nativeMediaPreviewCache::clearAccount)
        notifyDocumentsRootsChanged()
    }

    override fun openExternalUrl(url: String) {
        appContext.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun copyTextToClipboard(label: String, text: String): Boolean = runCatching {
        require(text.isNotBlank() && text.length <= 8_192 && text.none(Char::isISOControl)) {
            "Clipboard text is invalid."
        }
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label.take(128), text))
        true
    }.getOrDefault(false)

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
        require(target.method == dev.obiente.nextcloudnative.app.NextcloudApiMethod.GET) {
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
                val authorization = Base64.encodeToString(
                    "${session.loginName}:${session.appPassword}".toByteArray(StandardCharsets.UTF_8),
                    Base64.NO_WRAP,
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
                    AndroidDetachedDownload(
                        byteCount = responseBody.byteStream().copyBoundedTo(output, maximumBytes),
                        mimeType = responseBody.contentType()?.toString(),
                    )
                }
            }
        }
    }

    private fun notifyDocumentsRootsChanged() {
        appContext.contentResolver.notifyChange(
            DocumentsContract.buildRootsUri(NEXTCLOUD_DOCUMENTS_AUTHORITY),
            null,
        )
    }

    override suspend fun beginLogin(serverUrl: String): LoginChallenge = withContext(Dispatchers.IO) {
        val baseUrl = normalizeServerUrl(serverUrl)
        val response = request(method = "POST", url = "$baseUrl/index.php/login/v2")
        check(response.status in 200..299) {
            "This server did not start Nextcloud Login Flow v2 (HTTP ${response.status})."
        }
        val json = JSONObject(response.text)
        val poll = json.getJSONObject("poll")
        LoginChallenge(
            pollEndpoint = poll.getString("endpoint"),
            token = poll.getString("token"),
            loginUrl = json.getString("login"),
        )
    }

    override suspend fun pollLogin(challenge: LoginChallenge): NextcloudSession? = withContext(Dispatchers.IO) {
        val formBody = "token=" + URLEncoder.encode(challenge.token, StandardCharsets.UTF_8.name())
        val response = request(
            method = "POST",
            url = challenge.pollEndpoint,
            body = formBody,
            contentType = "application/x-www-form-urlencoded",
        )
        if (response.status == 404) return@withContext null
        check(response.status in 200..299) { "Login approval failed (HTTP ${response.status})." }
        val json = JSONObject(response.text)
        NextcloudSession(
            serverUrl = normalizeServerUrl(json.getString("server")),
            loginName = json.getString("loginName"),
            appPassword = json.getString("appPassword"),
        )
    }

    override suspend fun loadServerInfo(session: NextcloudSession): NextcloudServerInfo =
        withContext(Dispatchers.IO) {
            val user = ocsGet(session, "/ocs/v2.php/cloud/user").getJSONObject("ocs").getJSONObject("data")
            val capabilityData = ocsGet(session, "/ocs/v1.php/cloud/capabilities")
                .getJSONObject("ocs")
                .getJSONObject("data")
            val capabilities = capabilityData.getJSONObject("capabilities")
            val theming = capabilities.optJSONObject("theming")
            val navigation = runCatching {
                ocsGet(session, "/ocs/v2.php/core/navigation/apps")
                    .getJSONObject("ocs")
                    .getJSONArray("data")
            }.getOrNull()

            NextcloudServerInfo(
                serverUrl = session.serverUrl,
                displayName = user.optString("display-name").ifBlank { session.loginName },
                userId = user.optString("id").ifBlank { session.loginName },
                version = capabilityData.optJSONObject("version")?.optString("string")?.takeIf(String::isNotBlank),
                themeName = theming?.optString("name")?.takeIf(String::isNotBlank),
                themeColor = theming?.optString("color")?.takeIf(String::isNotBlank),
                apps = navigation?.toAppEntries() ?: capabilities.toCapabilityEntries(),
                recognizeBridge = discoverRecognizeBridge(capabilities.toString()),
                fileSharing = parseNextcloudFileSharingCapabilities(capabilities.toString()),
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
        val accountId = NextcloudDocumentIds.accountKey(session)
        try {
            val response = request(
                method = "PROPFIND",
                url = buildNextcloudFileUrl(session.serverUrl, userId, path),
                session = session,
                body = DAV_PROPERTIES,
                contentType = "application/xml; charset=utf-8",
                headers = mapOf("Depth" to "1", "Accept" to "application/xml"),
            )
            if (response.status == 207) {
                val files = parseDavFiles(response.body, userId).drop(1)
                    .sortedWith(compareByDescending<NextcloudFile> { it.isDirectory }.thenBy { it.name.lowercase() })
                runCatching { fileReadCache.storeListing(accountId, path, files) }
                NextcloudFileListing(files, NextcloudFileListingSource.Network)
            } else {
                if (response.status >= 500) {
                    fileReadCache.cachedListing(accountId, path)?.files?.let {
                        return@withContext NextcloudFileListing(it, NextcloudFileListingSource.Cache)
                    }
                }
                throw NextcloudFileListingHttpException(response.status)
            }
        } catch (failure: IOException) {
            fileReadCache.cachedListing(accountId, path)?.files
                ?.let { NextcloudFileListing(it, NextcloudFileListingSource.Cache) }
                ?: throw failure
        }
    }

    override suspend fun listFilesCachedWithSource(
        session: NextcloudSession,
        userId: String,
        path: String,
    ): NextcloudFileListing? = withContext(Dispatchers.IO) {
        fileReadCache.cachedListing(NextcloudDocumentIds.accountKey(session), path)?.let {
            NextcloudFileListing(it.files, NextcloudFileListingSource.Cache)
        }
    }

    override suspend fun loadFileOfflineAvailability(
        session: NextcloudSession,
        userId: String,
        files: List<NextcloudFile>,
    ): Map<String, FileOfflineAvailability> = withContext(Dispatchers.IO) {
        fileOfflineRepository.loadAvailability(session, userId, files)
    }

    override suspend fun setFileAvailableOffline(
        session: NextcloudSession,
        userId: String,
        file: NextcloudFile,
        available: Boolean,
    ): FileOfflineAvailability = withContext(Dispatchers.IO) {
        fileOfflineRepository.setAvailable(session, userId, file, available)
    }

    override suspend fun loadFileOfflineCenter(
        session: NextcloudSession,
        userId: String,
    ): FileOfflineCenterSnapshot = withContext(Dispatchers.IO) {
        fileOfflineRepository.loadCenter(session)
    }

    override suspend fun retryFileOfflineItem(
        session: NextcloudSession,
        userId: String,
        key: FileOfflineKey,
    ): FileOfflineCenterActionResult = withContext(Dispatchers.IO) {
        fileOfflineRepository.retryCenterItem(session, userId, key)
    }

    override suspend fun removeFileOfflineItem(
        session: NextcloudSession,
        userId: String,
        key: FileOfflineKey,
    ): FileOfflineCenterActionResult = withContext(Dispatchers.IO) {
        fileOfflineRepository.removeCenterItem(session, userId, key)
    }

    override suspend fun chooseFileSyncLocalRoot(initialRootHint: String?): FileSyncLocalRoot? =
        checkNotNull(fileSyncRootPicker) {
            "The native folder chooser is not available from this Android component."
        }.choose(initialRootHint)

    override suspend fun discoverMediaSyncFolders(): MediaSyncFolderDiscovery =
        withContext(Dispatchers.IO) {
            mediaSyncFolderDetector.discover()
        }

    override suspend fun previewMediaSyncFolder(
        suggestion: MediaSyncFolderSuggestion,
    ): MediaSyncFolderPreview = withContext(Dispatchers.IO) {
        mediaSyncFolderDetector.preview(suggestion)
    }

    override suspend fun loadFileSyncCenter(
        session: NextcloudSession,
        userId: String,
    ): FileSyncCenterSnapshot = withContext(Dispatchers.IO) {
        fileSyncEngine.loadCenter(session, userId)
    }

    override suspend fun addFileSyncPair(
        session: NextcloudSession,
        userId: String,
        localRoot: FileSyncLocalRoot,
        remoteRootPath: String,
        configuration: FileSyncConfiguration,
    ): FileSyncCenterActionResult = withContext(Dispatchers.IO) {
        fileSyncEngine.addPair(session, userId, localRoot, remoteRootPath, configuration)
    }

    override suspend fun runFileSyncPair(
        session: NextcloudSession,
        userId: String,
        pairId: String,
    ): FileSyncCenterActionResult = withContext(Dispatchers.IO) {
        fileSyncEngine.runPair(session, userId, pairId)
    }

    override suspend fun resolveFileSyncConflict(
        session: NextcloudSession,
        userId: String,
        pairId: String,
        workId: Long,
        choice: FileSyncDecisionChoice,
    ): FileSyncCenterActionResult = withContext(Dispatchers.IO) {
        fileSyncEngine.resolveConflictAndRun(session, userId, pairId, workId, choice)
    }

    override suspend fun removeFileSyncPair(
        session: NextcloudSession,
        userId: String,
        pairId: String,
    ): FileSyncCenterActionResult = withContext(Dispatchers.IO) {
        fileSyncEngine.removePair(session, pairId)
    }

    override suspend fun listMedia(
        session: NextcloudSession,
        userId: String,
    ): List<NextcloudFile> = withContext(Dispatchers.IO) {
        val pages = collectMediaSearchDavPages(
            requests = mediaSearchDavRequests(userId),
            execute = { body ->
                val response = request(
                    method = "SEARCH",
                    url = session.serverUrl + "/remote.php/dav/",
                    session = session,
                    body = body,
                    contentType = "application/xml; charset=utf-8",
                    headers = mapOf("Accept" to "application/xml"),
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
                        method = "SEARCH",
                        url = session.serverUrl + "/remote.php/dav/",
                        session = session,
                        body = body,
                        contentType = "application/xml; charset=utf-8",
                        headers = mapOf("Accept" to "application/xml"),
                    )
                    MediaSearchDavTransportResponse(response.status, response.body)
                },
                parse = { body -> parseDavFiles(body, userId) },
                shouldSearchRaw = { files ->
                    rawPreviouslyObserved || files.any(NextcloudFile::isRawPhoto)
                },
                carryoverStore = mediaTimelineCarryoverStore,
                carryoverAccountScope = photoMediaCarryoverScope(
                    accountScope = NextcloudDocumentIds.cacheAccountId(session),
                    owner = queryOwner,
                ),
            )
            return PhotoTimelinePage(
                entries = page.files.mapNotNull(NextcloudFile::toPhotoTimelineEntryOrNull),
                nextCursor = page.nextCursor,
                optionalRawRemovalAuthoritative = page.optionalRawRemovalAuthoritative,
                rawObserved = page.rawObserved,
                optionalRawSearchRetryPending = page.optionalRawSearchRetryPending,
            )
        }

        if (queryOwner == PhotoMediaQueryOwner.Timeline) {
            memoriesTimeline.loadPage(
                session = session,
                accountScope = NextcloudDocumentIds.cacheAccountId(session),
                cursor = cursor,
                fallback = ::loadDavPage,
            )
        } else {
            loadDavPage(cursor)
        }
    }

    override suspend fun loadMediaTimelineNavigationSnapshot(
        session: NextcloudSession,
        monthResolver: PhotoTimelineMonthResolver,
    ): MemoriesTimelineNavigationSnapshot? = withContext(Dispatchers.IO) {
        memoriesTimeline.navigationSnapshot(
            accountScope = NextcloudDocumentIds.cacheAccountId(session),
            monthResolver = monthResolver,
        )
    }

    override suspend fun loadMediaTimelineNavigationTarget(
        session: NextcloudSession,
        sourceGeneration: Long,
        targetDayId: Long,
    ): MemoriesTimelineNavigationLoadResult = withContext(Dispatchers.IO) {
        memoriesTimeline.loadNavigationTarget(
            session = session,
            accountScope = NextcloudDocumentIds.cacheAccountId(session),
            sourceGeneration = sourceGeneration,
            targetDayId = targetDayId,
        )
    }

    override suspend fun loadMediaBackupStatuses(
        session: NextcloudSession,
        userId: String,
        files: Collection<NextcloudFile>,
    ): Map<String, MediaBackupStatus> = withContext(Dispatchers.IO) {
        val paths = files.asSequence()
            .filterNot(NextcloudFile::isDirectory)
            .map { it.path.trim('/') }
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (paths.isEmpty()) return@withContext emptyMap()
        val store = createAndroidMediaBackupLedgerStore(
            context = appContext,
            recoverInterruptedTransfers = false,
        )
        try {
            buildMap {
                paths.chunked(MAX_MEDIA_BACKUP_STATUS_PATHS).forEach { chunk ->
                    putAll(
                        store.statusesForRemotePaths(
                            accountId = NextcloudDocumentIds.accountKey(session),
                            remotePaths = chunk,
                        ),
                    )
                }
            }
        } finally {
            store.close()
        }
    }

    override fun observeMediaBackupStatusChanges(session: NextcloudSession): Flow<Unit> {
        val accountId = NextcloudDocumentIds.accountKey(session)
        return MediaBackupStatusUpdates.changes
            .filter { changedAccountId -> changedAccountId == accountId }
            .map { }
    }

    override val supportsMediaTransferCenter: Boolean = true

    override suspend fun loadMediaTransferCenter(
        session: NextcloudSession,
        section: MediaTransferSection,
        after: MediaBackupLedgerCursor?,
    ): MediaTransferCenterState = withContext(Dispatchers.IO) {
        val store = createAndroidMediaBackupLedgerStore(
            context = appContext,
            recoverInterruptedTransfers = false,
        )
        try {
            val accountId = NextcloudDocumentIds.accountKey(session)
            fileSyncEngine.reconcileMediaTransfersForDisplay(accountId, store)
            val snapshot = store.snapshot(
                accountId = accountId,
                transferState = section.transferState(),
                after = after,
                limit = dev.obiente.nextcloudnative.app.MEDIA_TRANSFER_CENTER_PAGE_SIZE,
                includeClearedCompleted = false,
            )
            mediaTransferCenterState(
                summary = snapshot.summary,
                section = section,
                page = snapshot.page,
                canLoadNewer = after != null,
            )
        } finally {
            store.close()
        }
    }

    override suspend fun clearCompletedMediaTransferHistory(session: NextcloudSession): Int =
        withContext(Dispatchers.IO) {
            val store = createAndroidMediaBackupLedgerStore(
                context = appContext,
                recoverInterruptedTransfers = false,
            )
            try {
                store.clearCompleted(NextcloudDocumentIds.accountKey(session))
            } finally {
                store.close()
            }
        }

    override suspend fun listSystemTags(session: NextcloudSession): List<NextcloudSystemTag> =
        withContext(Dispatchers.IO) {
            val discovery = systemTagsDavDiscoveryRequest()
            val response = request(
                method = discovery.method,
                url = session.serverUrl + discovery.relativePath,
                session = session,
                body = discovery.body.decodeToString(),
                contentType = discovery.contentType,
                headers = mapOf("Depth" to discovery.depth.toString(), "Accept" to "application/xml"),
            )
            check(response.status == 207) { "System tag discovery failed (HTTP ${response.status})." }
            parseAndroidSystemTagsDavResponse(response.body)
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
                    method = search.method,
                    url = session.serverUrl + search.relativePath,
                    session = session,
                    body = search.body.decodeToString(),
                    contentType = search.contentType,
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
                method = "GET",
                url = session.serverUrl +
                    "/index.php/core/preview?fileId=$fileId&x=$safeWidth&y=$safeHeight&a=1&mode=cover" +
                    "&forceIcon=0&mimeFallback=0",
                session = session,
                headers = mapOf("Accept" to "image/*"),
            )
            check(response.status in 200..299) { "Preview failed (HTTP ${response.status})." }
            response.body
        }

    override suspend fun loadNativeMediaPreview(
        session: NextcloudSession,
        userId: String?,
        file: NextcloudFile,
        maximumDimension: Int,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (!file.isNativeTiffPreviewFormat()) return@withContext null
        val safeDimension = maximumDimension.coerceIn(
            MINIMUM_NATIVE_MEDIA_PREVIEW_DIMENSION,
            MAXIMUM_NATIVE_MEDIA_PREVIEW_DIMENSION,
        )
        val sourceFile = resolveNativeTiffPreviewSource(
            session = session,
            userId = userId,
            requestedFile = file,
        ) ?: return@withContext null
        val rangePlan = nativeTiffRangeReadPlanOrNull(sourceFile, userId)
            ?: return@withContext null
        val key = NativeMediaPreviewCacheKey(
            accountId = NextcloudDocumentIds.cacheAccountId(session),
            fileId = rangePlan.fileId,
            etag = rangePlan.etag,
            maximumDimension = safeDimension,
            decoderVersion = NATIVE_TIFF_DECODER_VERSION,
        )
        nativeMediaPreviewCache.load(key)?.let { return@withContext it }
        val accountGeneration = nativeMediaPreviewCache.accountGeneration(key.accountId)

        nativeMediaPreviewDecodeMutex.withLock {
            nativeMediaPreviewCache.load(key)?.let { return@withLock it }
            val decoder = AndroidTiffPreviewDecoder(
                sourceSize = rangePlan.sourceSize,
                readRange = { offset, length ->
                    when (rangePlan) {
                        is NativeTiffRangeReadPlan.FilesDav -> downloadFileRange(
                            session = session,
                            userId = rangePlan.userId,
                            path = rangePlan.path,
                            offset = offset,
                            length = length,
                            expectedEtag = rangePlan.etag,
                        )
                        is NativeTiffRangeReadPlan.Memories -> downloadMemoriesFileRange(
                            session = session,
                            fileId = rangePlan.fileId,
                            offset = offset,
                            length = length,
                            expectedEtag = rangePlan.etag,
                            expectedSourceSize = rangePlan.sourceSize,
                        )
                    }
                },
            )
            val encoded = decoder.decodeFirstPage(maximumDimension = safeDimension)
                ?.also { currentCoroutineContext().ensureActive() }
                ?.encodeDisplayImage()
                ?: return@withLock null
            currentCoroutineContext().ensureActive()
            if (
                !nativeMediaPreviewCache.store(
                    key = key,
                    bytes = encoded,
                    expectedAccountGeneration = accountGeneration,
                )
            ) {
                return@withLock null
            }
            encoded
        }
    }

    private suspend fun resolveNativeTiffPreviewSource(
        session: NextcloudSession,
        userId: String?,
        requestedFile: NextcloudFile,
    ): NextcloudFile? {
        if (!requestedFile.isNativeTiffPreviewFormat()) return null
        if (nativeTiffRangeReadPlanOrNull(requestedFile, userId) != null) return requestedFile
        val fileId = requestedFile.fileId?.takeIf { it > 0L } ?: return null
        val safeUserId = userId?.takeIf { it.isNotBlank() } ?: return null
        val resolved = runCatching {
            resolveFilesById(session, safeUserId, listOf(fileId))[fileId]
        }.getOrNull() ?: return null
        return resolvedNativeTiffPreviewSourceOrNull(
            requestedFile = requestedFile,
            resolvedFile = resolved,
            userId = safeUserId,
        )
    }

    override suspend fun loadMediaInformation(
        session: NextcloudSession,
        userId: String,
        file: NextcloudFile,
    ): MediaInformation = withContext(Dispatchers.IO) {
        val resolvedFile = file.fileId
            ?.takeIf { file.size == null || file.etag.isNullOrBlank() || !file.davPathAuthoritative }
            ?.let { fileId ->
                runCatching {
                    resolveFilesById(session, userId, listOf(fileId))[fileId]
                }.getOrNull()
            }
        val sourceFile = resolvedFile
            ?.copy(
                originalAccessAllowed =
                    file.originalAccessAllowed && resolvedFile.originalAccessAllowed,
                memoriesRenderAllowed = file.memoriesRenderAllowed || resolvedFile.memoriesRenderAllowed,
                mediaWidth = file.mediaWidth ?: resolvedFile.mediaWidth,
                mediaHeight = file.mediaHeight ?: resolvedFile.mediaHeight,
                capturedAtEpochSeconds = file.capturedAtEpochSeconds
                    ?: resolvedFile.capturedAtEpochSeconds,
                mediaDurationSeconds = file.mediaDurationSeconds
                    ?: resolvedFile.mediaDurationSeconds,
            )
            ?: file
        var information = sourceFile.basicMediaInformation()
        if (!sourceFile.isEmbeddedMediaInformationCandidate()) return@withContext information
        val sourceSize = sourceFile.size?.takeIf { it > 0L } ?: return@withContext information
        val etag = sourceFile.etag
            ?.takeIf { runCatching { requireSafeFileRangeEtag(it) }.isSuccess }
            ?: return@withContext information
        val fileId = sourceFile.fileId?.takeIf { it > 0L }
        val nativeTiffRangePlan = nativeTiffRangeReadPlanOrNull(sourceFile, userId)
        val rangeReader: suspend (Long, Int) -> ByteArray = { offset, length ->
            if (nativeTiffRangePlan is NativeTiffRangeReadPlan.FilesDav) {
                downloadFileRange(
                    session = session,
                    userId = nativeTiffRangePlan.userId,
                    path = nativeTiffRangePlan.path,
                    offset = offset,
                    length = length,
                    expectedEtag = nativeTiffRangePlan.etag,
                )
            } else if (nativeTiffRangePlan is NativeTiffRangeReadPlan.Memories) {
                downloadMemoriesFileRange(
                    session = session,
                    fileId = nativeTiffRangePlan.fileId,
                    offset = offset,
                    length = length,
                    expectedEtag = nativeTiffRangePlan.etag,
                    expectedSourceSize = nativeTiffRangePlan.sourceSize,
                )
            } else if (sourceFile.memoriesRenderAllowed && fileId != null) {
                downloadMemoriesFileRange(
                    session = session,
                    fileId = fileId,
                    offset = offset,
                    length = length,
                    expectedEtag = etag,
                    expectedSourceSize = sourceSize,
                )
            } else {
                check(sourceFile.davPathAuthoritative && sourceFile.originalAccessAllowed) {
                    "Embedded media information requires authoritative original access."
                }
                downloadFileRange(
                    session = session,
                    userId = userId,
                    path = sourceFile.path,
                    offset = offset,
                    length = length,
                    expectedEtag = etag,
                )
            }
        }

        val prefixLength = minOf(sourceSize, MAXIMUM_EMBEDDED_INFORMATION_PREFIX_BYTES.toLong())
            .toInt()
        runCatching {
            rangeReader(0L, prefixLength)
        }.getOrNull()
            ?.let(::extractAndroidEmbeddedMediaInformation)
            ?.let { information = information.mergedWith(it) }

        if (sourceFile.isNativeTiffPreviewFormat()) {
            runCatching {
                AndroidTiffPreviewDecoder(sourceSize, rangeReader).inspectFirstPage()
            }.getOrNull()
                ?.toMediaInformation()
                ?.let { information = information.mergedWith(it) }
        }
        information
    }

    override suspend fun downloadFile(
        session: NextcloudSession,
        userId: String,
        path: String,
        maxBytes: Long,
    ): NextcloudFileContent = withContext(Dispatchers.IO) {
        require(maxBytes > 0) { "The download size limit must be greater than zero." }
        val offline = fileOfflineRepository.availableContent(session, path)
            ?.takeIf { it.content.length() <= maxBytes }
        var verifiedOffline: NextcloudFileContent? = null
        var offlineChecked = false
        fun offlineContent(): NextcloudFileContent? {
            if (!offlineChecked) {
                verifiedOffline = offline?.readVerified(maxBytes)
                offlineChecked = true
            }
            return verifiedOffline
        }
        try {
            var response = request(
                method = "GET",
                url = buildNextcloudFileUrl(session.serverUrl, userId, path),
                session = session,
                headers = buildMap {
                    put("Accept", "*/*")
                    offline?.file?.etag?.let { put("If-None-Match", it) }
                },
                maxResponseBytes = maxBytes,
            )
            if (response.status == 304 && offlineContent() == null) {
                response = request(
                    method = "GET",
                    url = buildNextcloudFileUrl(session.serverUrl, userId, path),
                    session = session,
                    headers = mapOf("Accept" to "*/*"),
                    maxResponseBytes = maxBytes,
                )
            }
            when {
                response.status == 304 -> requireNotNull(offlineContent())
                response.status == 404 -> error("The file no longer exists on the server.")
                response.status >= 500 -> offlineContent()
                    ?: error("Downloading the file failed (HTTP ${response.status}).")
                response.status !in 200..299 ->
                    error("Downloading the file failed (HTTP ${response.status}).")
                else -> NextcloudFileContent(response.body, response.contentType, response.etag)
            }
        } catch (failure: IOException) {
            offlineContent() ?: throw failure
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
            method = "GET",
            url = buildNextcloudFileUrl(session.serverUrl, userId, path),
            session = session,
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

    override fun openFileRangeSession(
        session: NextcloudSession,
        userId: String,
        path: String,
        size: Long,
        expectedEtag: String,
    ): NextcloudFileRangeSession {
        require(size > 0L) { "The file range session size must be positive." }
        val safeEtag = requireSafeFileRangeEtag(expectedEtag)
        val url = buildNextcloudFileUrl(session.serverUrl, userId, path)
        val authorization = Base64.encodeToString(
            "${session.loginName}:${session.appPassword}".toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP,
        )
        val closed = AtomicBoolean(false)
        val activeCalls = ConcurrentHashMap.newKeySet<okhttp3.Call>()
        return NextcloudFileRangeSession(
            size = size,
            readBlock = { offset, length ->
                withContext(Dispatchers.IO) {
                    require(offset >= 0L) { "The file range offset must not be negative." }
                    require(length > 0) { "The file range length must be greater than zero." }
                    val endInclusive = Math.addExact(offset, length.toLong() - 1L)
                    require(endInclusive < size) { "The file range exceeds the source size." }
                    check(!closed.get()) { "The file range session is closed." }
                    val request = Request.Builder()
                        .url(url)
                        .get()
                        .header("Accept", "application/octet-stream")
                        .header("User-Agent", USER_AGENT)
                        .header("Authorization", "Basic $authorization")
                        .header("Range", "bytes=$offset-$endInclusive")
                        .header("If-Match", safeEtag)
                        .build()
                    val call = noRedirectHttpClient.newCall(request)
                    activeCalls += call
                    if (closed.get()) {
                        call.cancel()
                    }
                    try {
                        call.execute().use { response ->
                            check(response.code == 206) {
                                "The server did not honor the bounded file range request " +
                                    "(HTTP ${response.code})."
                            }
                            check(
                                isExactHttpByteContentRange(
                                    response.header("Content-Range"),
                                    offset,
                                    endInclusive,
                                ),
                            ) {
                                "The server returned a different file range than requested."
                            }
                            val responseBody = response.body
                            val contentLength = responseBody.contentLength()
                            check(contentLength == length.toLong() || contentLength == -1L) {
                                "The server returned an incomplete file range."
                            }
                            responseBody.byteStream().readBounded(length.toLong()).also { bytes ->
                                check(bytes.size == length) {
                                    "The server returned an incomplete file range."
                                }
                            }
                        }
                    } finally {
                        activeCalls -= call
                    }
                }
            },
            closeBlock = {
                if (closed.compareAndSet(false, true)) {
                    activeCalls.forEach { call -> call.cancel() }
                    activeCalls.clear()
                }
            },
        )
    }

    override suspend fun downloadMemoriesFileRange(
        session: NextcloudSession,
        fileId: Long,
        offset: Long,
        length: Int,
        expectedEtag: String,
        expectedSourceSize: Long,
    ): ByteArray {
        require(fileId > 0L) { "The Memories file ID must be positive." }
        require(offset >= 0L) { "The file range offset must not be negative." }
        require(length > 0) { "The file range length must be greater than zero." }
        require(expectedSourceSize > 0L) { "The source size must be positive." }
        val safeEtag = requireSafeFileRangeEtag(expectedEtag)
        val endInclusive = Math.addExact(offset, length.toLong() - 1L)
        val authorization = Base64.encodeToString(
            "${session.loginName}:${session.appPassword}".toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP,
        )
        val rangeRequest = Request.Builder()
            .url(session.serverUrl.trimEnd('/') + "/index.php/apps/memories/api/stream/$fileId")
            .get()
            .header("Accept", "application/octet-stream")
            .header("User-Agent", USER_AGENT)
            .header("Authorization", "Basic $authorization")
            .header("Range", "bytes=$offset-$endInclusive")
            .header("If-Match", safeEtag)
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = noRedirectHttpClient.newCall(rangeRequest)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            check(response.code == 206) {
                                "The Memories stream did not honor the bounded range request " +
                                    "(HTTP ${response.code})."
                            }
                            check(
                                isExactHttpByteContentRange(
                                    response.header("Content-Range"),
                                    offset,
                                    endInclusive,
                                    expectedSourceSize,
                                ),
                            ) {
                                "The Memories stream returned a different file range than requested."
                            }
                            (response.header("ETag") ?: response.header("OC-Etag"))
                                ?.let { returnedEtag ->
                                    check(requireSafeFileRangeEtag(returnedEtag) == safeEtag) {
                                        "The Memories stream returned a different file generation."
                                    }
                                }
                            val responseBody = response.body
                            val contentLength = responseBody.contentLength()
                            check(contentLength == length.toLong() || contentLength == -1L) {
                                "The Memories stream returned an incomplete file range."
                            }
                            responseBody.byteStream().readBounded(length.toLong()).also { bytes ->
                                check(bytes.size == length) {
                                    "The Memories stream returned an incomplete file range."
                                }
                            }
                        }
                    }
                    continuation.resumeWith(result)
                }
            })
        }
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
        normalizeFileVersionHistory(userId, fileId, parseAndroidFileVersionDavRecords(response.body))
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

    override suspend fun handoffFileVersionToExternalApp(
        session: NextcloudSession,
        userId: String,
        file: NextcloudFile,
        version: NextcloudFileVersion,
        action: ExternalFileHandoffAction,
    ): ExternalFileHandoffResult {
        requireMatchingFileVersion(file, version)
        val capability = (externalFileHandoffSupport as ExternalFileHandoffSupport.Available).capability
        val historicalCopy = file.copy(
            name = historicalFileCopyName(file.name, version.id),
            size = version.sizeBytes,
            etag = version.etag,
        )
        return externalFileHandoff.launch(historicalCopy, action, capability) { maximumBytes ->
            downloadFileVersion(session, userId, file, version, maximumBytes)
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
            method = "PUT",
            url = buildNextcloudFileUrl(session.serverUrl, userId, path),
            session = session,
            rawBody = utf8,
            contentType = "text/plain; charset=utf-8",
            headers = headers,
        )
        check(response.status != 412) { "The file changed on the server. Reload it before saving your changes." }
        check(response.status in 200..299) { "Saving the text file failed (HTTP ${response.status})." }
        val etag = response.etag ?: runCatching { loadFileEtag(session, userId, path) }.getOrNull()
        runCatching { fileReadCache.invalidate(NextcloudDocumentIds.accountKey(session), path) }
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
            method = "PUT",
            url = buildNextcloudFileUrl(session.serverUrl, userId, path),
            session = session,
            rawBody = utf8,
            contentType = "text/plain; charset=utf-8",
            headers = mapOf("Accept" to "*/*", "If-None-Match" to "*"),
        )
        if (response.status == 412) return@withContext SavedTextFile(etag = null, wasCreated = false)
        check(response.status in 200..299) { "Creating the text file failed (HTTP ${response.status})." }
        check(response.status == 201) { "The server did not confirm that a new text file was created." }
        runCatching { fileReadCache.invalidate(NextcloudDocumentIds.accountKey(session), path) }
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
        runCatching { fileReadCache.invalidate(NextcloudDocumentIds.accountKey(session), path) }
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
        val accountId = NextcloudDocumentIds.accountKey(session)
        runCatching { fileReadCache.invalidate(accountId, spec.sourcePath) }
        spec.destinationPath?.let { destination ->
            runCatching { fileReadCache.invalidate(accountId, destination) }
        }
        NextcloudFileMutationResult(spec.destinationPath, response.etag)
    }

    override suspend fun executeNextcloudApi(
        session: NextcloudSession,
        request: NextcloudApiRequest,
    ): NextcloudApiResponse = withContext(Dispatchers.IO) {
        val safeRequest = request.requireSafe()
        safeRequest.multipartBody?.let { multipart ->
            return@withContext executeNextcloudMultipartUpload(
                session,
                multipart.toUploadRequest(safeRequest),
            )
        }
        val accountId = NextcloudDocumentIds.cacheAccountId(session)
        val cacheIdentity = safeRequest.dynamicReadCacheIdentity()
        if (safeRequest.method != dev.obiente.nextcloudnative.app.NextcloudApiMethod.GET) {
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
        if (safeRequest.method != dev.obiente.nextcloudnative.app.NextcloudApiMethod.GET) {
            return@withContext try {
                executeNetworkRequest()
            } finally {
                dynamicApiRequestCoalescer.invalidateAccount(accountId) {
                    runCatching { dynamicApiReadCache.invalidateAccount(accountId) }
                }
            }
        }
        executeAndroidDynamicApiGet(
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
        localUploadPicker?.choose(acceptedMimeTypes, maximumBytes)
            ?: LocalUploadSelectionResult.Unavailable(
                "Local file selection is unavailable on this Android host.",
            )

    override fun releaseLocalUploadFile(file: LocalUploadFile) {
        localUploadPicker?.release(file)
    }

    override suspend fun executeNextcloudMultipartUpload(
        session: NextcloudSession,
        request: NextcloudMultipartUploadRequest,
    ): NextcloudApiResponse = withContext(Dispatchers.IO) {
        val safeRequest = request.requireSafe()
        val picker = checkNotNull(localUploadPicker) {
            "Local file upload is unavailable on this Android host."
        }
        val envelope = prepareMultipartUpload(
            safeRequest,
            "nc-native-${UUID.randomUUID()}",
        )
        val requestBody = AndroidStreamingMultipartRequestBody(envelope) {
            picker.open(safeRequest.file)
        }
        val apiRequest = NextcloudApiRequest(
            method = safeRequest.method,
            relativePath = safeRequest.relativePath,
            queryParameters = safeRequest.queryParameters,
            ocsApiRequest = safeRequest.ocsApiRequest,
            maximumResponseBytes = safeRequest.maximumResponseBytes,
        )
        val accountId = NextcloudDocumentIds.cacheAccountId(session)
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

    override suspend fun enqueueDurableMultipartUpload(
        session: NextcloudSession,
        scope: DurableUploadScope,
        request: NextcloudMultipartUploadRequest,
    ): DurableUploadEnqueueResult = withContext(Dispatchers.IO) {
        durableMultipartUploads.enqueue(session, scope, request)
    }

    override suspend fun durableMultipartUploadStatuses(
        session: NextcloudSession,
        scope: DurableUploadScope,
    ): List<DurableUploadStatus> = withContext(Dispatchers.IO) {
        durableMultipartUploads.statuses(session, scope)
    }

    override suspend fun dismissDurableMultipartUpload(
        session: NextcloudSession,
        scope: DurableUploadScope,
        uploadId: String,
    ): Boolean = withContext(Dispatchers.IO) {
        durableMultipartUploads.dismiss(session, scope, uploadId)
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

    override suspend fun listActivities(
        session: NextcloudSession,
        limit: Int,
    ): List<NextcloudActivity> = withContext(Dispatchers.IO) {
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

    override suspend fun listNotes(session: NextcloudSession): List<NextcloudNote> =
        withContext(Dispatchers.IO) {
            val response = request(
                method = "GET",
                url = session.serverUrl + "/index.php/apps/notes/api/v1/notes?exclude=content",
                session = session,
            )
            check(response.status in 200..299) { "Loading Notes failed (HTTP ${response.status})." }
            val data = JSONArray(response.text)
            buildList {
                for (index in 0 until data.length()) {
                    data.optJSONObject(index)?.toNextcloudNote()?.let(::add)
                }
            }.sortedWith(compareByDescending<NextcloudNote> { it.favorite }.thenByDescending { it.modified })
        }

    override suspend fun loadNote(session: NextcloudSession, noteId: Long): NextcloudNote =
        withContext(Dispatchers.IO) {
            require(noteId >= 0L) { "The note ID is invalid." }
            val response = request(
                method = "GET",
                url = session.serverUrl + "/index.php/apps/notes/api/v1/notes/$noteId",
                session = session,
            )
            check(response.status != 404) { "The note no longer exists." }
            check(response.status in 200..299) { "Loading the note failed (HTTP ${response.status})." }
            requireNotNull(JSONObject(response.text).toNextcloudNote(response.etag)) { "The note response is invalid." }
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
            method = "PUT",
            url = session.serverUrl + "/index.php/apps/notes/api/v1/notes/$noteId",
            session = session,
            body = body,
            contentType = "application/json; charset=utf-8",
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
            method = plan.method.name,
            url = session.serverUrl + plan.relativePath,
            session = session,
            body = requireNotNull(plan.body).decodeToString(),
            contentType = plan.contentType,
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
            method = plan.method.name,
            url = session.serverUrl + plan.relativePath,
            session = session,
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
                method = "GET",
                url = session.serverUrl + "/index.php/apps/memories/api/clusters/${encodePathSegment(backend)}",
                session = session,
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
                method = "GET",
                url = session.serverUrl +
                    "/index.php/apps/memories/api/clusters/${encodePathSegment(person.backend)}/preview" +
                    "?name=${person.id}&cover=${person.coverFileId}&cover_etag=" +
                    URLEncoder.encode(person.coverEtag.orEmpty(), StandardCharsets.UTF_8.name()),
                session = session,
                ocsRequest = true,
                headers = mapOf("Accept" to "image/*"),
            )
            check(response.status in 200..299) { "Loading the person cover failed (HTTP ${response.status})." }
            response.body
        }

    override suspend fun listPersonMedia(
        session: NextcloudSession,
        person: NextcloudPerson,
    ): List<NextcloudFile> = withContext(Dispatchers.IO) {
        val filter = URLEncoder.encode("${person.userId}/${person.queryName}", StandardCharsets.UTF_8.name())
        val daysResponse = request(
            method = "GET",
            url = session.serverUrl +
                "/index.php/apps/memories/api/days?${encodePathSegment(person.backend)}=$filter" +
                "&nopreload=1&facerect=1",
            session = session,
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
                method = "GET",
                url = session.serverUrl + "/index.php/apps/memories/api/days/${dayIds.joinToString(",")}" +
                    "?${encodePathSegment(person.backend)}=$filter&facerect=1",
                session = session,
                ocsRequest = true,
            )
            if (response.status in 200..299) JSONArray(response.text).appendMemoryFiles(person, files)
        }
        files.values.toList()
    }

    override suspend fun listTalkRooms(session: NextcloudSession): List<TalkRoom> =
        withContext(Dispatchers.IO) {
            try {
                val data = ocsGet(session, "/ocs/v2.php/apps/spreed/api/v4/room?noStatusUpdate=1")
                    .getJSONObject("ocs")
                    .getJSONArray("data")
                buildList {
                    for (index in 0 until data.length()) {
                        val room = data.optJSONObject(index) ?: continue
                        val token = room.optString("token").takeIf(String::isNotBlank) ?: continue
                        val lastMessage = room.optJSONObject("lastMessage")
                        add(
                            TalkRoom(
                                token = token,
                                displayName = room.optString("displayName").ifBlank { "Conversation" },
                                lastMessage = lastMessage
                                    ?.let { parseTalkMessageJson(it.toString()) }
                                    ?.content
                                    ?.summary
                                    ?.takeIf(String::isNotBlank),
                                unreadMessages = room.optInt("unreadMessages", 0),
                            ),
                        )
                    }
                }
            } catch (failure: Throwable) {
                Log.e(LOG_TAG, "Loading Talk conversations failed", failure)
                throw failure
            }
        }

    override suspend fun listTalkMessages(
        session: NextcloudSession,
        token: String,
    ): List<TalkMessage> = listTalkMessagePage(session, token).messages

    override suspend fun listTalkMessagePage(
        session: NextcloudSession,
        token: String,
        olderCursor: Long?,
        limit: Int,
    ): TalkMessagePage = withContext(Dispatchers.IO) {
        require(limit in 1..MAX_TALK_MESSAGE_PAGE_SIZE) {
            "Talk message page size must be between 1 and $MAX_TALK_MESSAGE_PAGE_SIZE."
        }
        require(olderCursor == null || olderCursor >= 0L) {
            "Talk history cursor must not be negative."
        }
        try {
            val encodedToken = encodePathSegment(token)
            val response = request(
                method = "GET",
                url = session.serverUrl + "/ocs/v2.php/apps/spreed/api/v1/chat/$encodedToken" +
                    "?format=json&lookIntoFuture=0&limit=$limit&lastKnownMessageId=${olderCursor ?: 0L}" +
                    "&includeLastKnown=0&setReadMarker=0&markNotificationsAsRead=0&noStatusUpdate=1",
                session = session,
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
            buildList {
                for (index in 0 until data.length()) {
                    val message = data.optJSONObject(index) ?: continue
                    parseTalkMessageJson(message.toString())?.let(::add)
                }
            }.let { messages ->
                val nextCursor = response.chatLastGiven?.toLongOrNull()
                TalkMessagePage(
                    messages = messages,
                    olderCursor = nextCursor,
                    hasMoreHistory = response.status != 304 && nextCursor != null,
                )
            }
        } catch (failure: Throwable) {
            Log.e(LOG_TAG, "Loading Talk messages failed", failure)
            throw failure
        }
    }

    override suspend fun sendTalkMessage(
        session: NextcloudSession,
        token: String,
        message: String,
    ) = withContext(Dispatchers.IO) {
        val encodedToken = encodePathSegment(token)
        val response = request(
            method = "POST",
            url = session.serverUrl + "/ocs/v2.php/apps/spreed/api/v1/chat/$encodedToken?format=json",
            session = session,
            body = "message=" + URLEncoder.encode(message, StandardCharsets.UTF_8.name()),
            contentType = "application/x-www-form-urlencoded",
            ocsRequest = true,
        )
        check(response.status in 200..299) { "Sending the Talk message failed (HTTP ${response.status})." }
        Unit
    }

    override suspend fun revokeSession(session: NextcloudSession) = withContext(Dispatchers.IO) {
        request(
            method = "DELETE",
            url = session.serverUrl + "/ocs/v2.php/core/apppassword",
            session = session,
            ocsRequest = true,
        )
        Unit
    }

    private fun ocsGet(session: NextcloudSession, path: String): JSONObject {
        val separator = if ('?' in path) '&' else '?'
        val response = request(
            method = "GET",
            url = session.serverUrl + path + separator + "format=json",
            session = session,
            ocsRequest = true,
        )
        check(response.status in 200..299) { "Nextcloud API request failed (HTTP ${response.status})." }
        return JSONObject(response.text)
    }

    private fun loadFileEtag(session: NextcloudSession, userId: String, path: String): String? {
        val response = request(
            method = "PROPFIND",
            url = buildNextcloudFileUrl(session.serverUrl, userId, path),
            session = session,
            body = DAV_ETAG_PROPERTY,
            contentType = "application/xml; charset=utf-8",
            headers = mapOf("Depth" to "0", "Accept" to "application/xml"),
        )
        if (response.status != 207) return null
        return SafeXmlParser.parse(response.body).documentElement.firstText(DAV_NAMESPACE, "getetag")
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
        check(appContext.isAllowedTestRequest(method, url)) {
            "This emulator is using a shared read-only test session. Cloud changes are blocked."
        }
        val requestBody = when {
            streamingBody != null -> streamingBody
            rawBody != null -> rawBody.toRequestBody(contentType?.toMediaType())
            body != null -> body.toRequestBody(contentType?.toMediaType())
            method == "POST" || method == "PUT" || method == "PATCH" -> byteArrayOf().toRequestBody(null)
            else -> null
        }
        val builder = Request.Builder()
            .url(url)
            .method(method, requestBody)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
        if (ocsRequest) builder.header("OCS-APIRequest", "true")
        headers.forEach(builder::header)
        session?.let {
            val value = "${it.loginName}:${it.appPassword}"
            val encoded = Base64.encodeToString(value.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
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
                status = response.code,
                body = responseBody.byteStream().readBounded(readLimit),
                contentType = responseBody.contentType()?.toString(),
                etag = response.header("ETag") ?: response.header("OC-Etag"),
                location = if (session == null) {
                    response.header("Location")
                } else {
                    resolveAndroidNextcloudRedirectLocation(
                        requestUrl = response.request.url,
                        serverUrl = session.serverUrl,
                        location = response.header("Location"),
                    )
                },
                chatLastGiven = response.header("X-Chat-Last-Given"),
                contentRange = response.header("Content-Range"),
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
        val document = SafeXmlParser.parse(xml)
        val responses = document.getElementsByTagNameNS(DAV_NAMESPACE, "response")
        return buildList {
            for (index in 0 until responses.length) {
                val response = responses.item(index)
                val name = response.firstText(DAV_NAMESPACE, "displayname") ?: continue
                val href = response.firstText(DAV_NAMESPACE, "href").orEmpty()
                val decodedHref = decodeDavHref(href)
                val marker = "/files/$userId/"
                val path = decodedHref.substringAfter(marker, name).trimEnd('/').ifBlank { name }
                val contentType = response.firstText(DAV_NAMESPACE, "getcontenttype")
                val size = response.firstText(OWNCLOUD_NAMESPACE, "size")?.toLongOrNull()
                    ?: response.firstText(DAV_NAMESPACE, "getcontentlength")?.toLongOrNull()
                add(
                    NextcloudFile(
                        path = path,
                        name = name,
                        isDirectory = response.childCount(DAV_NAMESPACE, "collection") > 0,
                        mimeType = contentType,
                        size = size,
                        lastModified = response.firstText(DAV_NAMESPACE, "getlastmodified"),
                        fileId = response.firstText(OWNCLOUD_NAMESPACE, "fileid")?.toLongOrNull(),
                        hasPreview = response.firstText(NEXTCLOUD_NAMESPACE, "has-preview") == "true",
                        etag = response.firstText(DAV_NAMESPACE, "getetag"),
                        permissions = response.firstText(OWNCLOUD_NAMESPACE, "permissions"),
                    ),
                )
            }
        }
    }

    private fun org.w3c.dom.Node.firstText(namespace: String, localName: String): String? =
        (this as? org.w3c.dom.Element)
            ?.getElementsByTagNameNS(namespace, localName)
            ?.item(0)
            ?.textContent
            ?.takeIf(String::isNotBlank)

    private fun org.w3c.dom.Node.childCount(namespace: String, localName: String): Int =
        (this as? org.w3c.dom.Element)?.getElementsByTagNameNS(namespace, localName)?.length ?: 0

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun normalizeServerUrl(value: String): String {
        val withScheme = value.trim().let { if ("://" in it) it else "https://$it" }
        val uri = URI(withScheme)
        require(uri.scheme == "https") { "Use a secure https:// server address." }
        require(!uri.host.isNullOrBlank()) { "Enter a valid Nextcloud server address." }
        return withScheme.trimEnd('/').removeSuffix("/index.php")
    }

    private fun JSONArray.toAppEntries(): List<NextcloudAppEntry> = buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val id = item.optString("id").takeIf(String::isNotBlank) ?: continue
            add(
                NextcloudAppEntry(
                    id = id,
                    name = item.optString("name").ifBlank { readableName(id) },
                    href = item.optString("href").takeIf(String::isNotBlank),
                ),
            )
        }
    }

    private fun JSONObject.toCapabilityEntries(): List<NextcloudAppEntry> = keys().asSequence()
        .filterNot { it in NON_APP_CAPABILITIES }
        .map { id -> NextcloudAppEntry(id, readableName(id), null) }
        .sortedBy(NextcloudAppEntry::name)
        .toList()

    private fun readableName(id: String): String = id
        .replace('_', ' ')
        .split(' ')
        .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }

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
            etag = optString("etag").takeIf(String::isNotBlank) ?: responseEtag,
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
        const val KEY_THEME = "theme_preference"
        const val KEY_LAST_OPENED_APP = "last_opened_app"
        const val KEY_SESSION = "encrypted_session"
        const val KEY_TEST_READ_ONLY = "emulator_test_read_only"
        const val USER_AGENT = "Nextcloud-Native/0.1.0 (Android)"
        const val DAV_NAMESPACE = "DAV:"
        const val OWNCLOUD_NAMESPACE = "http://owncloud.org/ns"
        const val NEXTCLOUD_NAMESPACE = "http://nextcloud.org/ns"
        const val DEFAULT_BUFFER_CAPACITY = 8 * 1024
        const val MAX_API_RESPONSE_BYTES = 16L * 1024L * 1024L
        const val MAX_ERROR_RESPONSE_BYTES = 64L * 1024L
        const val PERSON_MEDIA_INITIAL_DAY_LIMIT = 12
        const val LOG_TAG = "NextcloudNative"
        val DAV_PROPERTIES = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
              <d:prop>
                <d:displayname/><d:getcontenttype/><d:getlastmodified/><d:getcontentlength/><d:getetag/>
                <d:resourcetype/><oc:fileid/><oc:size/><oc:permissions/><nc:has-preview/>
              </d:prop>
            </d:propfind>
        """.trimIndent()
        val DAV_ETAG_PROPERTY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:getetag/></d:prop></d:propfind>
        """.trimIndent()
        val NON_APP_CAPABILITIES = setOf("core", "theming")
    }
}

private fun NextcloudFile.isNativeTiffPreviewFormat(): Boolean {
    if (isDirectory) return false
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
    return extension in setOf("tif", "tiff") || mime in setOf("image/tif", "image/tiff")
}

internal sealed interface NativeTiffRangeReadPlan {
    val fileId: Long
    val sourceSize: Long
    val etag: String

    data class FilesDav(
        override val fileId: Long,
        override val sourceSize: Long,
        override val etag: String,
        val userId: String,
        val path: String,
    ) : NativeTiffRangeReadPlan

    data class Memories(
        override val fileId: Long,
        override val sourceSize: Long,
        override val etag: String,
    ) : NativeTiffRangeReadPlan
}

internal fun nativeTiffRangeReadPlanOrNull(
    file: NextcloudFile,
    userId: String?,
): NativeTiffRangeReadPlan? {
    if (!file.isNativeTiffPreviewFormat()) return null
    val fileId = file.fileId?.takeIf { it > 0L } ?: return null
    val sourceSize = file.size?.takeIf { it > 0L } ?: return null
    val etag = file.etag
        ?.takeIf { runCatching { requireSafeFileRangeEtag(it) }.isSuccess }
        ?: return null
    val safeUserId = userId?.takeIf { it.isNotBlank() && it == it.trim() }
    val safePath = file.path.takeIf {
        file.originalAccessAllowed &&
            file.davPathAuthoritative &&
            it.isSafeNativeTiffDavPath()
    }
    if (safeUserId != null && safePath != null) {
        return NativeTiffRangeReadPlan.FilesDav(
            fileId = fileId,
            sourceSize = sourceSize,
            etag = etag,
            userId = safeUserId,
            path = safePath,
        )
    }
    return if (file.memoriesRenderAllowed) {
        NativeTiffRangeReadPlan.Memories(
            fileId = fileId,
            sourceSize = sourceSize,
            etag = etag,
        )
    } else {
        null
    }
}

private fun String.isSafeNativeTiffDavPath(): Boolean =
    isNotEmpty() &&
        '\u0000' !in this &&
        '\\' !in this &&
        !startsWith('/') &&
        !endsWith('/') &&
        split('/').all { segment ->
            segment.isNotEmpty() && segment != "." && segment != ".."
        }

internal fun resolvedNativeTiffPreviewSourceOrNull(
    requestedFile: NextcloudFile,
    resolvedFile: NextcloudFile,
    userId: String,
): NextcloudFile? {
    val requestedFileId = requestedFile.fileId?.takeIf { it > 0L } ?: return null
    if (
        resolvedFile.fileId != requestedFileId ||
        !requestedFile.isNativeTiffPreviewFormat() ||
        !resolvedFile.isNativeTiffPreviewFormat()
    ) {
        return null
    }
    return resolvedFile.copy(
        originalAccessAllowed =
            requestedFile.originalAccessAllowed && resolvedFile.originalAccessAllowed,
        memoriesRenderAllowed =
            requestedFile.memoriesRenderAllowed || resolvedFile.memoriesRenderAllowed,
    ).takeIf { nativeTiffRangeReadPlanOrNull(it, userId) != null }
}

private fun NextcloudFile.isEmbeddedMediaInformationCandidate(): Boolean {
    val sourceSize = size
    if (
        isDirectory ||
        sourceSize == null ||
        sourceSize <= 0L ||
        etag.isNullOrBlank() ||
        (!memoriesRenderAllowed && (!davPathAuthoritative || !originalAccessAllowed))
    ) {
        return false
    }
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
    return mime.startsWith("image/") || extension in EMBEDDED_MEDIA_INFORMATION_EXTENSIONS
}

private val EMBEDDED_MEDIA_INFORMATION_EXTENSIONS = setOf(
    "arw",
    "avif",
    "cr2",
    "cr3",
    "dng",
    "heic",
    "heif",
    "jpeg",
    "jpg",
    "nef",
    "nrw",
    "orf",
    "pef",
    "raf",
    "raw",
    "rw2",
    "srw",
    "tif",
    "tiff",
    "webp",
)

private val READ_ONLY_TEST_REQUEST_METHODS =
    setOf("GET", "HEAD", "OPTIONS", "PROPFIND", "REPORT", "SEARCH")

internal fun String.isReadOnlyTestRequestMethod(): Boolean =
    uppercase(Locale.ROOT) in READ_ONLY_TEST_REQUEST_METHODS

internal fun parseAndroidFileVersionDavRecords(xml: ByteArray): List<FileVersionDavRecord> {
    val responses = SafeXmlParser.parse(xml).getElementsByTagNameNS(FILE_VERSION_DAV_NAMESPACE, "response")
    return buildList {
        for (index in 0 until responses.length) {
            val response = responses.item(index)
            val properties = response.successfulFileVersionPropertyRoot() ?: continue
            add(
                FileVersionDavRecord(
                    href = response.fileVersionFirstText(FILE_VERSION_DAV_NAMESPACE, "href").orEmpty(),
                    contentLength = properties.fileVersionFirstText(
                        FILE_VERSION_DAV_NAMESPACE,
                        "getcontentlength",
                    ),
                    lastModified = properties.fileVersionFirstText(FILE_VERSION_DAV_NAMESPACE, "getlastmodified"),
                    etag = properties.fileVersionFirstText(FILE_VERSION_DAV_NAMESPACE, "getetag"),
                    author = properties.fileVersionFirstText(FILE_VERSION_NC_NAMESPACE, "version-author"),
                    label = properties.fileVersionFirstText(FILE_VERSION_NC_NAMESPACE, "version-label"),
                ),
            )
        }
    }
}

private fun org.w3c.dom.Node.successfulFileVersionPropertyRoot(): org.w3c.dom.Node? {
    val element = this as? org.w3c.dom.Element ?: return null
    val propstats = element.getElementsByTagNameNS(FILE_VERSION_DAV_NAMESPACE, "propstat")
    if (propstats.length > 0) {
        for (index in 0 until propstats.length) {
            val propstat = propstats.item(index)
            val status = propstat.fileVersionFirstText(FILE_VERSION_DAV_NAMESPACE, "status").orEmpty()
            if (status.isDavSuccessStatus()) return propstat
        }
        return null
    }
    return if (
        element.getElementsByTagNameNS(FILE_VERSION_DAV_NAMESPACE, "status")
            .item(0)?.textContent.orEmpty().isDavSuccessStatus()
    ) {
        element
    } else {
        null
    }
}

private fun String.isDavSuccessStatus(): Boolean =
    trim().split(' ').any { token -> token.toIntOrNull()?.let { it in 200..299 } == true }

private fun org.w3c.dom.Node.fileVersionFirstText(namespace: String, localName: String): String? =
    (this as? org.w3c.dom.Element)
        ?.getElementsByTagNameNS(namespace, localName)
        ?.item(0)
        ?.textContent
        ?.takeIf(String::isNotBlank)

private const val FILE_VERSION_DAV_NAMESPACE = "DAV:"
private const val FILE_VERSION_NC_NAMESPACE = "http://nextcloud.org/ns"

internal fun parseAndroidSystemTagsDavResponse(xml: ByteArray): List<NextcloudSystemTag> {
    val responses = SafeXmlParser.parse(xml).getElementsByTagNameNS(SYSTEM_TAG_DAV_NAMESPACE, "response")
    val records = buildList {
        for (index in 0 until responses.length) {
            val response = responses.item(index)
            add(
                SystemTagDavRecord(
                    href = response.systemTagFirstText(SYSTEM_TAG_DAV_NAMESPACE, "href").orEmpty(),
                    id = response.systemTagFirstText(SYSTEM_TAG_OC_NAMESPACE, "id"),
                    displayName = response.systemTagFirstText(SYSTEM_TAG_OC_NAMESPACE, "display-name"),
                    userVisible = response.systemTagFirstText(SYSTEM_TAG_OC_NAMESPACE, "user-visible"),
                    userAssignable = response.systemTagFirstText(SYSTEM_TAG_OC_NAMESPACE, "user-assignable"),
                    canAssign = response.systemTagFirstText(SYSTEM_TAG_OC_NAMESPACE, "can-assign"),
                    color = response.systemTagFirstText(SYSTEM_TAG_NC_NAMESPACE, "color"),
                    etag = response.systemTagFirstText(SYSTEM_TAG_DAV_NAMESPACE, "getetag"),
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

private const val SYSTEM_TAG_DAV_NAMESPACE = "DAV:"
private const val SYSTEM_TAG_OC_NAMESPACE = "http://owncloud.org/ns"
private const val SYSTEM_TAG_NC_NAMESPACE = "http://nextcloud.org/ns"
private const val MINIMUM_NATIVE_MEDIA_PREVIEW_DIMENSION = 64
private const val MAXIMUM_NATIVE_MEDIA_PREVIEW_DIMENSION = 4_096
        private const val NATIVE_TIFF_DECODER_VERSION = "tiff-stream-v4"
private const val MAXIMUM_EMBEDDED_INFORMATION_PREFIX_BYTES = 4 * 1024 * 1024
