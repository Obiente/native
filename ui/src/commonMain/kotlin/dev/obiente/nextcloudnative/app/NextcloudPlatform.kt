package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.Serializable

enum class ThemePreference {
    System,
    Light,
    Dark,
}

enum class DurableMutationRecoveryKind(val storageKey: String) {
    Calendar("calendar-v1"),
    Contacts("contacts-v1"),
    NoteDeletion("note-deletion-v1"),
}

internal const val MAX_DURABLE_MUTATION_RECOVERY_BYTES = 1024 * 1024

enum class PlatformCapability {
    Notifications,
    Camera,
    Microphone,
    NearbyAudio,
    BackgroundSync,
    FilesAndMedia,
    MediaLibrary,
    AllFilesAccess,
}

enum class PlatformCapabilityState {
    Granted,
    NeedsPermission,
    Blocked,
    AvailableWithoutPermission,
    Unsupported,
}

data class PlatformCapabilityStatus(
    val capability: PlatformCapability,
    val label: String,
    val description: String,
    val state: PlatformCapabilityState,
)

data class NextcloudSession(
    val serverUrl: String,
    val loginName: String,
    val appPassword: String,
)

data class LoginChallenge(
    val enteredServerUrl: String,
    val pollEndpoint: String,
    val pollFallbackEndpoint: String?,
    val token: String,
    val loginUrl: String,
    val transportSecurity: LoginTransportSecurity = LoginTransportSecurity.Tls,
)

enum class LoginTransportSecurity {
    Tls,
    PlainHttp,
}

internal fun serverAddressUsesPlainHttp(value: String): Boolean =
    value.trim().startsWith("http://", ignoreCase = true)

data class ServerCertificateReview(
    val serverOrigin: String,
    val serverDisplayName: String,
    val subject: String,
    val issuer: String,
    val sha256Fingerprint: String,
    val validFrom: String,
    val validUntil: String,
)

data class TrustedServerCertificate(
    val sha256Fingerprint: String,
)

sealed interface LoginPollResult {
    data object Pending : LoginPollResult

    data class Approved(val session: NextcloudSession) : LoginPollResult

    data class RetryablePreExchangeFailure(val code: String) : LoginPollResult

    data class FatalFailure(val message: String, val code: String? = null) : LoginPollResult

    data class AmbiguousAfterExchangeFailure(val message: String, val code: String? = null) : LoginPollResult
}

@Serializable
data class NextcloudAppEntry(
    val id: String,
    val name: String,
    val href: String?,
)

data class NextcloudServerInfo(
    val serverUrl: String,
    val displayName: String,
    val userId: String,
    val version: String?,
    val themeName: String?,
    val themeColor: String?,
    val apps: List<NextcloudAppEntry>,
    val appsAuthoritative: Boolean = true,
    val recognizeBridge: RecognizeBridgeDiscovery = RecognizeBridgeDiscovery.NotAdvertised,
    val fileSharing: NextcloudFileSharingCapabilities = NextcloudFileSharingCapabilities.Unavailable,
)

@Serializable
data class NextcloudLivePhotoReference(
    /** Opaque server token returned by Memories. It must never be parsed or reconstructed. */
    val serverToken: String,
) {
    init {
        require(serverToken.isSafeLivePhotoToken()) { "The Live Photo reference is invalid." }
    }
}

@Serializable
data class NextcloudFile(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val mimeType: String?,
    val size: Long?,
    val lastModified: String?,
    val fileId: Long?,
    val hasPreview: Boolean,
    val etag: String? = null,
    /** Server-backed Files favorite state exposed through the `oc:favorite` DAV property. */
    val favorite: Boolean = false,
    /** Account identity supplied by DAV for shared and federated items, when available. */
    val ownerId: String? = null,
    val ownerDisplayName: String? = null,
    /** Number of comments the server reports as unread for this item. */
    val unreadComments: Int = 0,
    /**
     * Whether callers may read the original object rather than a bounded server preview.
     *
     * Ordinary Files/Photos records allow this by default. Shared surfaces such as Talk can
     * explicitly preserve a server-side no-download policy through generic file handoffs.
     */
    val originalAccessAllowed: Boolean = true,
    /**
     * Whether [path] identifies the original object in the authenticated Files DAV tree.
     *
     * Surfaces such as Talk can carry a stable file ID without supplying a DAV path. Their
     * display-only placeholder must never be used for original byte reads.
     */
    val davPathAuthoritative: Boolean = true,
    /** Raw DAV permission flags. `W` is required before planning an edit session. */
    val permissions: String? = null,
    /** Strong content identities supplied by DAV clients, for example `SHA256:<hex>`. */
    val checksums: List<String> = emptyList(),
    /** Optional Memories relationship for the motion component of this still image. */
    val livePhoto: NextcloudLivePhotoReference? = null,
    /**
     * Stable file IDs supplied for a directory's visual preview.
     *
     * These IDs authorize preview rendering only. They do not identify child DAV paths and must
     * never be used to invent original file locations or mutation targets.
     */
    val directoryPreviewFileIds: List<Long> = emptyList(),
    /**
     * Whether the authenticated Memories app may render this stable [fileId] server-side.
     *
     * This is deliberately separate from [originalAccessAllowed] and [davPathAuthoritative]:
     * timeline records can authorize the file-ID route without authorizing an original download
     * or claiming that their UI-only [path] exists in Files DAV.
     */
    val memoriesRenderAllowed: Boolean = false,
    /** Pixel dimensions supplied by a trusted media listing or a bounded local metadata reader. */
    val mediaWidth: Int? = null,
    val mediaHeight: Int? = null,
    /** Capture time supplied by Memories or embedded media metadata. */
    val capturedAtEpochSeconds: Long? = null,
    /** Whole-second duration supplied for video or audio media. */
    val mediaDurationSeconds: Int? = null,
)

data class NextcloudFileContent(
    val bytes: ByteArray,
    val mimeType: String?,
    val etag: String?,
)

data class SavedTextFile(
    val etag: String?,
    val wasCreated: Boolean,
)

enum class NextcloudApiMethod {
    GET,
    POST,
    PUT,
    PATCH,
    DELETE,
}

enum class NextcloudApiCachePolicy {
    PreferCache,
    RefreshNetwork,
    ForceNetwork,
}

class NextcloudApiReadFailure(
    val responseBodyMayHaveStarted: Boolean,
    cause: Throwable,
) : Exception(cause.message, cause)

/**
 * Restricted same-origin transport used by schema-declared dynamic app actions.
 *
 * Callers cannot supply authentication or arbitrary headers. Platform implementations attach the
 * authenticated Nextcloud session and reject redirects so credentials never leave the account
 * origin. The descriptor trust boundary is still responsible for approving the endpoint prefix.
 */
data class NextcloudApiRequest(
    val method: NextcloudApiMethod,
    val relativePath: String,
    val queryParameters: Map<String, String> = emptyMap(),
    val contentType: String? = null,
    val body: ByteArray? = null,
    val ocsApiRequest: Boolean = false,
    val maximumResponseBytes: Long = DEFAULT_DYNAMIC_API_RESPONSE_LIMIT_BYTES,
    val cachePolicy: NextcloudApiCachePolicy = NextcloudApiCachePolicy.PreferCache,
    val multipartBody: NextcloudMultipartBody? = null,
)

data class NextcloudApiResponse(
    val status: Int,
    val body: ByteArray,
    val contentType: String?,
    val etag: String?,
    /**
     * Present only when redirects are disabled and the server returned a safe Location header.
     * Authenticated platform transports resolve same-account redirects and expose only the
     * account-relative path; cross-origin and out-of-account locations are withheld.
     */
    val location: String? = null,
)

/**
 * Stable credential-free identity for a dynamic GET response.
 *
 * Length-prefixed query fields avoid ambiguous delimiter collisions while retaining a readable
 * prefix for diagnostics. The response bound is part of the representation identity so a smaller
 * in-flight read cannot satisfy a larger request. Authentication and request bodies are excluded.
 */
fun NextcloudApiRequest.dynamicReadCacheIdentity(): String = buildString {
    append(method.name)
    append(' ')
    append(relativePath)
    append(" ocs=")
    append(ocsApiRequest)
    append(" max=")
    append(maximumResponseBytes)
    queryParameters.toSortedMap().forEach { (name, value) ->
        val encodedName = encodeUrlComponent(name)
        val encodedValue = encodeUrlComponent(value)
        append(" q")
        append(encodedName.length)
        append(':')
        append(encodedName)
        append('=')
        append(encodedValue.length)
        append(':')
        append(encodedValue)
    }
}

data class AcquiredOpenApiContract(
    val appId: String,
    val appVersion: String,
    val contractVersion: String,
    val specFile: String,
    val document: String,
    val packageUrl: String,
    val sourceUrl: String,
    val sourceKind: AcquiredOpenApiContractSourceKind,
    val contractKind: AcquiredContractKind = AcquiredContractKind.OpenApi,
)

enum class AcquiredContractKind {
    OpenApi,
    VerifiedReadRoutes,
    OpenApiWithVerifiedReadRoutes,
}

enum class AcquiredOpenApiContractSourceKind {
    SignedAppPackage,
    SignedCompatibleAppPackage,
    AppStoreLinkedExactGitHubTag,
    AppStoreLinkedCompatibleGitHubTag,
}

@Serializable
data class TalkRoom(
    val token: String,
    val displayName: String,
    val lastMessage: String?,
    val unreadMessages: Int,
)

data class TalkMessage(
    val id: Long,
    val actorDisplayName: String,
    val actorId: String,
    val actorType: String,
    val message: String,
    val timestamp: Long,
    val messageType: TalkMessageType,
    val systemMessage: TalkSystemMessageType,
    val systemMessageName: String?,
    val parameters: Map<String, TalkRichObjectParameter>,
    val content: TalkMessageContent,
    val threadId: Long? = null,
    val isThread: Boolean = false,
    val threadTitle: String? = null,
    val threadReplies: Int = 0,
    val isReplyable: Boolean = false,
    val parent: TalkMessageQuote? = null,
    val reactions: List<TalkReaction> = emptyList(),
    val editedAt: Long? = null,
    val editedBy: String? = null,
    val deleted: Boolean = false,
    val silent: Boolean = false,
    val expiresAt: Long? = null,
    val scheduledAt: Long? = null,
    val referenceId: String? = null,
) {
    val isSystemMessage: Boolean get() = systemMessageName != null
}

data class TalkMessageQuote(
    val id: Long,
    val actorDisplayName: String,
    val summary: String,
    val deleted: Boolean,
)

data class TalkReaction(
    val emoji: String,
    val count: Int,
    val reactedByMe: Boolean,
) {
    init {
        require(emoji.isNotBlank())
        require(count > 0)
    }
}

data class TalkMessagePage(
    val messages: List<TalkMessage>,
    /** Opaque history position returned by Talk for the next older-page request. */
    val olderCursor: Long?,
    val hasMoreHistory: Boolean,
)

data class NextcloudActivity(
    val id: Long,
    val app: String,
    val type: String,
    val subject: String,
    val message: String?,
    val objectType: String?,
    val objectId: String?,
    val objectName: String?,
    val link: String?,
    val icon: String?,
    val dateTime: String?,
    val preview: NextcloudActivityPreview? = null,
)

data class NextcloudActivityPreview(
    val fileId: Long,
    val filename: String,
    val mimeType: String?,
    val isMimeTypeIcon: Boolean,
) {
    init {
        require(fileId > 0L) { "The activity preview file identifier is invalid." }
        require(filename.isNotBlank() && filename.length <= 4_096) {
            "The activity preview filename is invalid."
        }
    }
}

@Serializable
data class NextcloudNote(
    val id: Long,
    val title: String,
    val modified: Long,
    val category: String,
    val favorite: Boolean,
    val readOnly: Boolean,
    val content: String?,
    val etag: String?,
    val internalPath: String? = null,
    val isShared: Boolean = false,
)

sealed interface NextcloudNotePresence {
    data class Present(val note: NextcloudNote) : NextcloudNotePresence
    data object Absent : NextcloudNotePresence
}

sealed interface NextcloudConditionalRead<out T> {
    data class Modified<T>(
        val value: T,
        val responseEtag: String?,
    ) : NextcloudConditionalRead<T>

    data object NotModified : NextcloudConditionalRead<Nothing>
}

@Serializable
data class NextcloudPerson(
    val id: Long,
    val name: String,
    val userId: String,
    val queryName: String,
    val count: Int,
    val coverFileId: Long?,
    val coverEtag: String?,
    val backend: String,
)

interface NextcloudPlatformServices {
    /** Loads public project news from the fixed Obiente feed, with a bounded platform cache. */
    suspend fun loadProjectNews(forceRefresh: Boolean = false): ProjectNewsResult =
        error("Project news is unavailable on this platform.")

    /** Loads one hash-verified, canonical public image referenced by the project news feed. */
    suspend fun loadProjectNewsImage(image: ProjectNewsImage): ByteArray =
        error("Project news images are unavailable on this platform.")

    /** Describes who owns app updates. Store-owned installs must remain with their store. */
    fun appUpdateSupport(): AppUpdateSupport = AppUpdateSupport(
        channel = AppDistributionChannel.Unsupported,
        currentVersionName = "Unknown",
        currentVersionCode = 0,
        canCheckDirectUpdates = false,
        explanation = "In-app update checks are unavailable on this platform.",
    )

    fun loadAppUpdateChannel(): AndroidUpdateChannel = enforcedAppUpdateChannel

    /**
     * Persists an available direct update channel.
     *
     * Store-owned and unsupported installations return false and remain unchanged.
     */
    fun saveAppUpdateChannel(channel: AndroidUpdateChannel): Boolean = false

    fun loadAppUpdatePreferences(): AppUpdatePreferences = AppUpdatePreferences()

    fun saveAppUpdatePreferences(preferences: AppUpdatePreferences): Boolean = false

    /** True only when the platform can currently deliver the dedicated app-update notification. */
    fun appUpdateNotificationDeliveryAllowed(): Boolean = false

    /** Requests runtime, app-wide, or app-update-channel notification access as appropriate. */
    fun requestAppUpdateNotificationDelivery(): Boolean = false

    /** Periodic check interval while this app process is running, or null for platform-owned scheduling. */
    fun appUpdateAutomaticCheckIntervalMillis(): Long? = null

    fun observeAppUpdateCheckResult(): Flow<AppUpdateCheckResult?> = flowOf(null)

    suspend fun checkForAppUpdate(
        channel: AndroidUpdateChannel = loadAppUpdateChannel(),
        automatic: Boolean = false,
    ): AppUpdateCheckResult =
        AppUpdateCheckResult.Unavailable(appUpdateSupport())

    /** Observable direct-package download, verification, cancellation, and retry state. */
    fun observeAppUpdateInstallState(): Flow<AppUpdateInstallState> =
        flowOf(AppUpdateInstallState.Idle)

    /**
     * Downloads and verifies one direct-APK release before opening the platform confirmation flow.
     *
     * Implementations may never silently install or invoke this for store-owned installs.
     */
    suspend fun beginAppUpdate(release: AppUpdateRelease): AppUpdateInstallResult =
        AppUpdateInstallResult.Rejected("Direct app updates are unavailable on this platform.")

    /** Cancels the active direct-package download; the result states whether a partial can resume. */
    fun cancelAppUpdate(): Boolean = false

    /** True only when this platform has durable app-private offline file storage and execution. */
    val supportsFileOfflineStorage: Boolean get() = false

    /** True when remote files can hydrate into a managed, automatically reclaimable local cache. */
    val supportsVirtualFileStorage: Boolean get() = false

    /**
     * True only for one-way recursive folder availability backed by durable platform execution.
     *
     * This capability never implies local-to-server upload or bidirectional folder synchronization.
     */
    val supportsRecursiveFileOfflineStorage: Boolean get() = false

    /** True only when this platform can execute durable local-folder/remote-folder sync pairs. */
    val supportsBidirectionalFileSync: Boolean get() = false

    /** Native handoff is opt-in; unsupported platforms must never imply that an action was launched. */
    val externalFileHandoffSupport: ExternalFileHandoffSupport
        get() = ExternalFileHandoffSupport.Unsupported("External file handoff is not supported on this platform.")

    fun platformCapabilities(): List<PlatformCapabilityStatus> = emptyList()

    /** Starts a platform permission flow or opens platform settings when the permission is blocked. */
    fun requestPlatformCapability(capability: PlatformCapability): Boolean = false

    fun loadThemePreference(): ThemePreference

    fun saveThemePreference(preference: ThemePreference)

    /** Desktop-only login startup integration; unsupported platforms keep this setting hidden. */
    val supportsStartOnLogin: Boolean
        get() = false

    fun loadStartOnLoginPreference(): Boolean = false

    /** Returns a user-facing limitation when the preference could not be applied immediately. */
    fun saveStartOnLoginPreference(enabled: Boolean): String? = null

    /** Summary of bounded, already-sanitized application diagnostics kept in private storage. */
    suspend fun loadSupportDiagnosticsSummary(): SupportDiagnosticsSummary = SupportDiagnosticsSummary(
        available = false,
        eventCount = 0,
        warningCount = 0,
        errorCount = 0,
        oldestEventAtEpochMillis = null,
        newestEventAtEpochMillis = null,
        components = emptySet(),
        storedBytes = 0L,
        includedFiles = SUPPORT_BUNDLE_INCLUDED_FILES,
        explanation = "Anonymized support reports are unavailable on this platform.",
    )

    /** Emits after the visible diagnostic history changes so an open support card stays current. */
    fun supportDiagnosticsRevisions(): Flow<Long> = flowOf(0L)

    /** Creates a local sanitized report and opens the platform-owned save or share flow. */
    suspend fun exportSupportDiagnostics(reproductionSteps: String): SupportDiagnosticsExportResult =
        SupportDiagnosticsExportResult.Unsupported(
            "Anonymized support reports are unavailable on this platform.",
        )

    /** Current explicit support submission. No implementation may start one automatically. */
    fun supportDiagnosticsSubmissionStates(): Flow<SupportDiagnosticsSubmissionState> =
        flowOf(SupportDiagnosticsSubmissionState.Unsupported("Direct support submission is unavailable on this platform."))

    /** Packages and submits the reviewed report after the UI confirmation step. */
    suspend fun submitSupportDiagnostics(reproductionSteps: String) = Unit

    /** Retries only a retained, idempotent submission after reconciliation. */
    suspend fun retrySupportDiagnosticsSubmission() = Unit

    /** Cancels packaging or upload and removes its app-private temporary archive. */
    suspend fun cancelSupportDiagnosticsSubmission(): Boolean = false

    /** Deletes one retained submitted report after an explicit user confirmation. */
    suspend fun deleteSubmittedSupportDiagnosticsReport(recordId: String): SupportDiagnosticsDeletionResult =
        SupportDiagnosticsDeletionResult.Unsupported(
            "Deleting submitted support reports is unavailable on this platform.",
        )

    /** Refreshes private report statuses and conversations using their retained capabilities. */
    suspend fun refreshSubmittedSupportDiagnosticsReports(): SupportDiagnosticsConversationResult =
        SupportDiagnosticsConversationResult.Unsupported(
            "Private support conversations are unavailable on this platform.",
        )

    /** Sends one reporter reply through the retained private report capability. */
    suspend fun sendSubmittedSupportDiagnosticsMessage(
        recordId: String,
        message: String,
    ): SupportDiagnosticsConversationResult = SupportDiagnosticsConversationResult.Unsupported(
        "Private support conversations are unavailable on this platform.",
    )

    /** Acknowledges the currently visible status and maintainer messages on this device. */
    suspend fun markSubmittedSupportDiagnosticsReportRead(recordId: String): Boolean = false

    /** Clears only diagnostic history. The private alias key remains stable across reports. */
    suspend fun clearSupportDiagnostics(): Boolean = false

    /** Records a structured event. Implementations sanitize it before app-private persistence. */
    fun recordSupportDiagnostic(event: SupportDiagnosticEventDraft) = Unit

    /** Registers a private value for exact in-memory removal from later diagnostic messages. */
    fun registerSupportDiagnosticPrivateValue(value: String?) = Unit

    /** Desktop-only close behavior; unsupported platforms keep this setting hidden. */
    val supportsKeepRunningInBackground: Boolean
        get() = false

    fun loadKeepRunningInBackgroundPreference(): Boolean = false

    fun saveKeepRunningInBackgroundPreference(enabled: Boolean) = Unit

    fun loadLastOpenedAppId(): String

    fun saveLastOpenedAppId(appId: String)

    /**
     * Loads one account-scoped mutation intent from app-private durable storage.
     *
     * Callers persist the intent before contacting the server and clear it only after an
     * authoritative response or a follow-up read proves the postcondition.
     */
    suspend fun loadDurableMutationRecovery(
        accountScope: String,
        kind: DurableMutationRecoveryKind,
    ): String? = null

    suspend fun saveDurableMutationRecovery(
        accountScope: String,
        kind: DurableMutationRecoveryKind,
        encoded: String,
    ): Boolean = false

    suspend fun clearDurableMutationRecovery(
        accountScope: String,
        kind: DurableMutationRecoveryKind,
        expectedEncoded: String,
    ): Boolean = false

    /** Loads an account-scoped verified app contract without any cached user records. */
    suspend fun loadCachedDynamicAppDiscovery(
        session: NextcloudSession,
        appId: String,
    ): DynamicDescriptorDiscovery? = null

    /** Persists a verified app contract so its adaptive workspace can paint before revalidation. */
    suspend fun saveCachedDynamicAppDiscovery(
        session: NextcloudSession,
        discovery: DynamicDescriptorDiscovery,
    ) = Unit

    /** Loads one exact account/app/action/record mutation staged before a non-idempotent send. */
    suspend fun loadPendingDynamicMutation(
        session: NextcloudSession,
        appId: String,
        actionId: String,
        targetRecordId: String,
    ): Map<String, String>? = null

    /** Durably stages exact validated request values before transport may observe the mutation. */
    suspend fun savePendingDynamicMutation(
        session: NextcloudSession,
        appId: String,
        actionId: String,
        targetRecordId: String,
        values: Map<String, String>,
    ): Unit = throw UnsupportedOperationException(
        "Crash-safe dynamic mutation staging is not supported on this platform.",
    )

    /** Clears a staged mutation only after success or an authoritative rejected outcome. */
    suspend fun clearPendingDynamicMutation(
        session: NextcloudSession,
        appId: String,
        actionId: String,
        targetRecordId: String,
    ) = Unit

    fun loadSession(): NextcloudSession?

    suspend fun saveSession(session: NextcloudSession)

    suspend fun clearSession()

    /** Loads one bounded, account-scoped unsaved Deck editor draft from app-private storage. */
    suspend fun loadDeckCardDraft(
        session: NextcloudSession,
        key: DeckCardDraftKey,
    ): PersistedDeckCardDraft? = null

    /** Persists one bounded Deck editor draft without storing account credentials in its key. */
    suspend fun saveDeckCardDraft(
        session: NextcloudSession,
        draft: PersistedDeckCardDraft,
    ) = Unit

    /** Clears a draft after an explicit cancel or a confirmed successful server mutation. */
    suspend fun clearDeckCardDraft(
        session: NextcloudSession,
        key: DeckCardDraftKey,
    ) = Unit

    fun openExternalUrl(url: String)

    /** Opens the one-time browser login URL without blocking the UI dispatcher. */
    suspend fun openLoginUrl(url: String) = openExternalUrl(url)

    /** Copies bounded application text without exposing session credentials to another process. */
    fun copyTextToClipboard(label: String, text: String): Boolean = false

    suspend fun handoffFileToExternalApp(
        session: NextcloudSession,
        userId: String,
        file: NextcloudFile,
        action: ExternalFileHandoffAction,
    ): ExternalFileHandoffResult = ExternalFileHandoffResult.Unsupported(
        (externalFileHandoffSupport as? ExternalFileHandoffSupport.Unsupported)?.reason
            ?: "External file handoff is not supported on this platform.",
    )

    /**
     * Streams an authenticated Deck attachment into a detached private platform cache.
     *
     * The typed target comes from the permission-checked Deck route planner. Implementations must
     * reject redirects, enforce the external handoff byte limit while streaming, and must not
     * invent a DAV path or ETag for the attachment.
     */
    suspend fun handoffDeckAttachmentToExternalApp(
        session: NextcloudSession,
        target: DeckAttachmentOpenTarget,
        attachment: DeckAttachment,
        action: ExternalFileHandoffAction = ExternalFileHandoffAction.OpenWith,
    ): ExternalFileHandoffResult = ExternalFileHandoffResult.Unsupported(
        (externalFileHandoffSupport as? ExternalFileHandoffSupport.Unsupported)?.reason
            ?: "Deck attachment handoff is not supported on this platform.",
    )

    suspend fun beginLogin(
        serverUrl: String,
        transportSecurity: LoginTransportSecurity = LoginTransportSecurity.Tls,
    ): LoginChallenge

    /** Returns a review only when a platform can safely offer explicit certificate trust. */
    suspend fun inspectServerCertificateFailure(
        serverUrl: String,
        failure: Throwable,
    ): ServerCertificateReview? = null

    /** Revalidates and persists the exact reviewed certificate for its HTTPS origin. */
    suspend fun trustServerCertificate(review: ServerCertificateReview) {
        error("Explicit server certificate trust is not supported on this platform.")
    }

    fun trustedServerCertificate(serverUrl: String): TrustedServerCertificate? = null

    fun removeTrustedServerCertificate(serverUrl: String): Boolean = false

    suspend fun pollLogin(challenge: LoginChallenge): LoginPollResult

    /** Releases transient state for a completed, failed, cancelled, or timed-out login challenge. */
    fun finishLoginPolling(challenge: LoginChallenge) = Unit

    /** Waits briefly for a usable platform network before another pre-exchange login attempt. */
    suspend fun awaitLoginNetworkAvailability() = Unit

    suspend fun loadServerInfo(session: NextcloudSession): NextcloudServerInfo

    suspend fun listFiles(session: NextcloudSession, userId: String, path: String): List<NextcloudFile>

    /**
     * Lists a folder while preserving whether the returned snapshot was confirmed by the server.
     *
     * Platforms with a persistent read cache override this method. The default keeps existing
     * service implementations source-compatible but never claims that an unknown result is cached.
     */
    suspend fun listFilesWithSource(
        session: NextcloudSession,
        userId: String,
        path: String,
    ): NextcloudFileListing = NextcloudFileListing(
        files = listFiles(session, userId, path),
        source = NextcloudFileListingSource.Network,
    )

    /**
     * Reads a cached folder listing, if available, without performing a network request.
     *
     * Default implementations return null and should be overridden by cache-capable services.
     */
    suspend fun listFilesCachedWithSource(
        session: NextcloudSession,
        userId: String,
        path: String,
    ): NextcloudFileListing? = null

    /** Recursively searches Files through the authenticated account's DAV search endpoint. */
    suspend fun searchFiles(
        session: NextcloudSession,
        userId: String,
        query: String,
        scopePath: String = "",
        maximumResults: Int = 200,
    ): List<NextcloudFile> = listFiles(session, userId, scopePath)
        .filter { file -> file.name.contains(query.trim(), ignoreCase = true) }
        .take(maximumResults)

    /** Recursively lists server-backed favorites from the requested DAV scope. */
    suspend fun listFavoriteFiles(
        session: NextcloudSession,
        userId: String,
        scopePath: String = "",
    ): List<NextcloudFile> = listFiles(session, userId, scopePath).filter(NextcloudFile::favorite)

    /** Updates the server-backed favorite property and returns only after DAV confirms the write. */
    suspend fun setFileFavorite(
        session: NextcloudSession,
        userId: String,
        file: NextcloudFile,
        favorite: Boolean,
    ) {
        error("File favorites are not supported on this platform.")
    }

    /** Returns persisted availability for the supplied files, keyed by canonical relative path. */
    suspend fun loadFileOfflineAvailability(
        session: NextcloudSession,
        userId: String,
        files: List<NextcloudFile>,
    ): Map<String, FileOfflineAvailability> = emptyMap()

    /** Persists pin intent and schedules durable execution; it does not claim immediate availability. */
    suspend fun setFileAvailableOffline(
        session: NextcloudSession,
        userId: String,
        file: NextcloudFile,
        available: Boolean,
    ): FileOfflineAvailability = error("Offline file storage is not supported on this platform.")

    /**
     * Returns an account-scoped inventory for the Sync & Offline center.
     *
     * The safe default reports the platform limitation instead of returning an empty inventory
     * that could be mistaken for proof that no files are pinned.
     */
    suspend fun loadFileOfflineCenter(
        session: NextcloudSession,
        userId: String,
    ): FileOfflineCenterSnapshot = defaultFileOfflineCenterSnapshot(
        supportsIndividualOfflineFiles = supportsFileOfflineStorage,
        supportsRecursiveFolderAvailability = supportsRecursiveFileOfflineStorage,
    )

    /** Retries only an already-planned offline download; the default never schedules work. */
    suspend fun retryFileOfflineItem(
        session: NextcloudSession,
        userId: String,
        key: FileOfflineKey,
    ): FileOfflineCenterActionResult = FileOfflineCenterActionResult.Unsupported(
        "Retrying offline work is not available on this platform.",
    )

    /** Removes one app-private offline copy; it must never delete the remote Nextcloud file. */
    suspend fun removeFileOfflineItem(
        session: NextcloudSession,
        userId: String,
        key: FileOfflineKey,
    ): FileOfflineCenterActionResult = FileOfflineCenterActionResult.Unsupported(
        "Removing offline copies from this center is not available on this platform.",
    )

    /** Returns cache and platform-provider status without performing network IO. */
    suspend fun loadVirtualFileStorage(
        session: NextcloudSession,
        userId: String,
    ): VirtualFileStorageSnapshot = defaultVirtualFileStorageSnapshot()

    /** Persists automatic virtual-file cleanup rules and immediately enforces hard limits. */
    suspend fun saveVirtualFileCachePolicy(
        session: NextcloudSession,
        userId: String,
        policy: VirtualFileCachePolicy,
    ): VirtualFileStorageActionResult = VirtualFileStorageActionResult.Unsupported(
        "Virtual file cache rules are not available on this platform.",
    )

    /** Frees only disposable hydrated content; it must never remove pins or unsynchronized data. */
    suspend fun freeUpVirtualFileSpace(
        session: NextcloudSession,
        userId: String,
        requestedBytes: Long,
    ): VirtualFileStorageActionResult = VirtualFileStorageActionResult.Unsupported(
        "Virtual file storage cleanup is not available on this platform.",
    )

    /** Activates the operating-system virtual file provider at its configured location. */
    suspend fun activateVirtualFileProvider(
        session: NextcloudSession,
        userId: String,
    ): VirtualFileStorageActionResult = VirtualFileStorageActionResult.Unsupported(
        "A system virtual file provider is not available on this platform.",
    )

    /** Stops the operating-system provider without deleting cached or remote content. */
    suspend fun deactivateVirtualFileProvider(
        session: NextcloudSession,
        userId: String,
    ): VirtualFileStorageActionResult = VirtualFileStorageActionResult.Unsupported(
        "A system virtual file provider is not available on this platform.",
    )

    /** Acknowledges a durable provider recovery notice without deleting preserved local files. */
    suspend fun acknowledgeVirtualFileProviderRecovery(
        session: NextcloudSession,
        userId: String,
    ): VirtualFileStorageActionResult = VirtualFileStorageActionResult.Unsupported(
        "A virtual file recovery notice is not available on this platform.",
    )

    /** Opens a native directory chooser for the parent of the visible virtual-file folder. */
    suspend fun chooseVirtualFileProviderParent(initialParentPath: String?): String? = null

    /** Opens a native directory chooser for a physical virtual-file cache tier. */
    suspend fun chooseVirtualFileCacheLocation(initialPath: String?): String? = null

    /** Moves cache storage without changing the visible virtual-files namespace. */
    suspend fun saveVirtualFileCacheTiers(
        session: NextcloudSession,
        userId: String,
        configuration: VirtualFileCacheTierConfiguration,
    ): VirtualFileStorageActionResult = VirtualFileStorageActionResult.Unsupported(
        "Tiered virtual-file storage is not available on this platform.",
    )

    /** Persists a validated provider location. Active providers must be migrated explicitly. */
    suspend fun saveVirtualFileProviderLocation(
        session: NextcloudSession,
        userId: String,
        location: VirtualFileProviderLocation,
    ): VirtualFileStorageActionResult = VirtualFileStorageActionResult.Unsupported(
        "Changing the virtual-file location is not available on this platform.",
    )

    /** Persists recursive virtual-folder retention and schedules hydration or safe release. */
    suspend fun setVirtualFolderRetention(
        session: NextcloudSession,
        userId: String,
        relativePath: String,
        retention: VirtualFolderRetention,
    ): VirtualFileStorageActionResult = VirtualFileStorageActionResult.Unsupported(
        "Selective virtual folders are not available on this platform.",
    )

    /** Retries hydration without rewriting the folder-retention tree. */
    suspend fun retryVirtualFolderHydration(
        session: NextcloudSession,
        userId: String,
        relativePath: String,
    ): VirtualFileStorageActionResult = VirtualFileStorageActionResult.Unsupported(
        "Selective virtual folders are not available on this platform.",
    )

    /** Opens the native folder chooser and persists a least-privilege folder grant. */
    suspend fun chooseFileSyncLocalRoot(initialRootHint: String? = null): FileSyncLocalRoot? = null

    suspend fun discoverMediaSyncFolders(): MediaSyncFolderDiscovery = MediaSyncFolderDiscovery(
        support = MediaSyncFolderDiscoverySupport.Unsupported,
        suggestions = emptyList(),
        message = "Automatic media folder discovery is not available on this platform.",
    )

    suspend fun previewMediaSyncFolder(
        suggestion: MediaSyncFolderSuggestion,
    ): MediaSyncFolderPreview = MediaSyncFolderPreview(
        localRootHint = suggestion.localRootHint,
        state = MediaSyncFolderPreviewState.Inaccessible,
        access = MediaSyncFolderAccess.LimitedSelection,
        totalItems = 0,
        totalBytes = 0L,
        items = emptyList(),
        message = "Media folder previews are not available on this platform.",
    )

    suspend fun loadFileSyncCenter(
        session: NextcloudSession,
        userId: String,
    ): FileSyncCenterSnapshot = FileSyncCenterSnapshot(
        support = FileSyncCenterSupport.Unsupported,
        pairs = emptyList(),
        limitation = "Bidirectional folder synchronization is not available on this platform.",
    )

    suspend fun addFileSyncPair(
        session: NextcloudSession,
        userId: String,
        localRoot: FileSyncLocalRoot,
        remoteRootPath: String,
        configuration: FileSyncConfiguration,
    ): FileSyncCenterActionResult = FileSyncCenterActionResult.Unsupported(
        "Bidirectional folder synchronization is not available on this platform.",
    )

    /** Scans both roots and executes all conflict-free work with revision guards. */
    suspend fun runFileSyncPair(
        session: NextcloudSession,
        userId: String,
        pairId: String,
    ): FileSyncCenterActionResult = FileSyncCenterActionResult.Unsupported(
        "Bidirectional folder synchronization is not available on this platform.",
    )

    suspend fun resolveFileSyncConflict(
        session: NextcloudSession,
        userId: String,
        pairId: String,
        workId: Long,
        choice: FileSyncDecisionChoice,
    ): FileSyncCenterActionResult = FileSyncCenterActionResult.Unsupported(
        "Folder sync conflict review is not available on this platform.",
    )

    suspend fun removeFileSyncPair(
        session: NextcloudSession,
        userId: String,
        pairId: String,
    ): FileSyncCenterActionResult = FileSyncCenterActionResult.Unsupported(
        "Bidirectional folder synchronization is not available on this platform.",
    )

    suspend fun listMedia(session: NextcloudSession, userId: String): List<NextcloudFile>

    /**
     * Loads one bounded, newest-first Photos timeline page.
     *
     * Implementations should use an opaque cursor and retain independent server-side partitions
     * when one media type has more results than another.
     */
    suspend fun listMediaTimelinePage(
        session: NextcloudSession,
        userId: String,
        cursor: PhotoTimelineCursor?,
        rawPreviouslyObserved: Boolean = false,
        queryOwner: PhotoMediaQueryOwner = PhotoMediaQueryOwner.Timeline,
    ): PhotoTimelinePage {
        if (cursor != null) return PhotoTimelinePage(emptyList(), null)
        return PhotoTimelinePage(
            entries = listMedia(session, userId)
                .mapNotNull(NextcloudFile::toPhotoTimelineEntryOrNull)
                .take(MAX_PHOTO_TIMELINE_PAGE_SIZE),
            nextCursor = null,
        )
    }

    /**
     * Returns the complete Memories day geometry bound to the currently painted timeline source.
     *
     * Platforms return null while DAV fallback is active or when no matching Memories index is
     * cached. Implementations must reuse the same source cache that served
     * [listMediaTimelinePage].
     */
    suspend fun loadMediaTimelineNavigationSnapshot(
        session: NextcloudSession,
        monthResolver: PhotoTimelineMonthResolver,
    ): MemoriesTimelineNavigationSnapshot? = null

    /**
     * Loads one bounded Memories day only when [sourceGeneration] still owns the active timeline.
     *
     * A stale generation must not be converted to a DAV request because Memories and DAV cursors
     * do not share an identity or paging contract.
     */
    suspend fun loadMediaTimelineNavigationTarget(
        session: NextcloudSession,
        sourceGeneration: Long,
        targetDayId: Long,
    ): MemoriesTimelineNavigationLoadResult =
        MemoriesTimelineNavigationLoadResult.Unavailable(
            "Complete photo timeline navigation is unavailable on this platform.",
        )

    /**
     * Returns locally known backup state for authoritative server paths.
     *
     * Missing paths mean this platform has no device-local backup evidence. Callers must not infer
     * a successful backup from a missing entry.
     */
    suspend fun loadMediaBackupStatuses(
        session: NextcloudSession,
        userId: String,
        files: Collection<NextcloudFile>,
    ): Map<String, MediaBackupStatus> = emptyMap()

    /** Emits after this device changes backup evidence for the active account. */
    fun observeMediaBackupStatusChanges(session: NextcloudSession): Flow<Unit> = emptyFlow()

    /** True when the platform maintains an account-scoped, bounded media upload ledger. */
    val supportsMediaTransferCenter: Boolean get() = false

    /**
     * Loads one bounded window of device-local media transfer history.
     *
     * This is a local projection only. Implementations must not perform remote mutations while
     * loading it, and must scope every row to the authenticated account.
     */
    suspend fun loadMediaTransferCenter(
        session: NextcloudSession,
        section: MediaTransferSection,
        after: MediaBackupLedgerCursor? = null,
    ): MediaTransferCenterState = mediaTransferCenterState(
        summary = MediaBackupLedgerSummary(0, 0, 0, 0),
        section = section,
        page = MediaBackupLedgerPage(emptyList(), null),
        canLoadNewer = after != null,
    )

    /** Clears only completed local history. It must never delete device or Nextcloud media. */
    suspend fun clearCompletedMediaTransferHistory(session: NextcloudSession): Int = 0

    /**
     * Resolves stable server file IDs to current authoritative Files records.
     *
     * Missing IDs are omitted. Callers must keep unresolved media preview-only and must not invent
     * a Files path from a Memories, Talk, album, or search route.
     */
    suspend fun resolveFilesById(
        session: NextcloudSession,
        userId: String,
        fileIds: Collection<Long>,
    ): Map<Long, NextcloudFile> = emptyMap()

    /** Lists the server-wide system tags visible to the authenticated account. */
    suspend fun listSystemTags(session: NextcloudSession): List<NextcloudSystemTag>

    suspend fun loadPreview(
        session: NextcloudSession,
        fileId: Long,
        width: Int = DEFAULT_PREVIEW_DIMENSION,
        height: Int = DEFAULT_PREVIEW_DIMENSION,
    ): ByteArray

    /**
     * Produces one bounded, display-oriented preview with a platform decoder when server preview
     * providers cannot decode the original format.
     *
     * Implementations must pin reads to the remote generation, keep original bytes out of memory,
     * cache only bounded generated output, and return null for unsupported formats.
     */
    suspend fun loadNativeMediaPreview(
        session: NextcloudSession,
        userId: String?,
        file: NextcloudFile,
        maximumDimension: Int = DEFAULT_PREVIEW_DIMENSION,
    ): ByteArray? = null

    /**
     * Loads bounded, read-only media information for display.
     *
     * Implementations may inspect generation-pinned byte ranges, but must not download an
     * unbounded original, persist embedded metadata outside an account-scoped cache, or mutate the
     * remote object.
     */
    suspend fun loadMediaInformation(
        session: NextcloudSession,
        userId: String,
        file: NextcloudFile,
    ): MediaInformation = file.basicMediaInformation()

    suspend fun downloadFile(
        session: NextcloudSession,
        userId: String,
        path: String,
        maxBytes: Long = DEFAULT_FILE_DOWNLOAD_LIMIT_BYTES,
    ): NextcloudFileContent

    /**
     * Reads one exact, bounded byte range from a Files WebDAV object.
     *
     * Platforms must send [expectedEtag] through If-Match, require an HTTP 206 response, and reject
     * servers that ignore the Range header. This keeps both reads pinned to one remote generation
     * and keeps large media containers out of memory when only an embedded preview is needed. The
     * returned bytes are detached and may never be written back automatically.
     */
    suspend fun downloadFileRange(
        session: NextcloudSession,
        userId: String,
        path: String,
        offset: Long,
        length: Int,
        expectedEtag: String,
    ): ByteArray = error("Bounded file range reads are not supported on this platform.")

    /**
     * Opens one cancellable generation-pinned range-reading session.
     *
     * A platform implementation should cancel an active transport request when the returned
     * session is closed. The default keeps existing platforms functional but cannot interrupt a
     * request that is already inside [downloadFileRange].
     */
    fun openFileRangeSession(
        session: NextcloudSession,
        userId: String,
        path: String,
        size: Long,
        expectedEtag: String,
    ): NextcloudFileRangeSession = NextcloudFileRangeSession(
        size = size,
        readBlock = { offset, length ->
            downloadFileRange(
                session = session,
                userId = userId,
                path = path,
                offset = offset,
                length = length,
                expectedEtag = expectedEtag,
            )
        },
    )

    /**
     * Reads one exact byte range from the official Memories file-ID stream.
     *
     * Timeline and album records can provide a stable file ID without an authoritative DAV path.
     * This route permits read-only decoder access without inventing a path. Implementations must
     * pin the request to [expectedEtag], require HTTP 206, and validate Content-Range exactly.
     */
    suspend fun downloadMemoriesFileRange(
        session: NextcloudSession,
        fileId: Long,
        offset: Long,
        length: Int,
        expectedEtag: String,
        expectedSourceSize: Long,
    ): ByteArray = error("Bounded Memories range reads are not supported on this platform.")

    /**
     * Lists immutable historical generations for the exact Files record.
     *
     * Implementations authenticate against the active account and use the official versions DAV
     * collection. This read must never restore or alter the current server object.
     */
    suspend fun listFileVersions(
        session: NextcloudSession,
        userId: String,
        file: NextcloudFile,
    ): FileVersionHistory = error("File version history is not supported on this platform.")

    /**
     * Downloads at most [maximumBytes] from one generation belonging to [file].
     *
     * Both the request range and the transport reader must enforce the bound. The result is a
     * detached copy and must never be written back to the current file automatically.
     */
    suspend fun downloadFileVersion(
        session: NextcloudSession,
        userId: String,
        file: NextcloudFile,
        version: NextcloudFileVersion,
        maximumBytes: Long = MAX_FILE_VERSION_PREVIEW_BYTES,
    ): NextcloudFileContent = error("Historical file downloads are not supported on this platform.")

    /**
     * Replaces the current file with the selected historical generation through versions DAV.
     *
     * Callers must obtain explicit user confirmation immediately before invoking this mutation.
     * Implementations must keep both source and destination on the authenticated account origin.
     */
    suspend fun restoreFileVersion(
        session: NextcloudSession,
        userId: String,
        file: NextcloudFile,
        version: NextcloudFileVersion,
    ) {
        error("File version restoration is not supported on this platform.")
    }

    /**
     * Opens a detached historical copy in the platform's export/share flow.
     *
     * The default deliberately does nothing. Platforms must stage a distinct read-only copy and
     * must not point another app at the live DAV object.
     */
    suspend fun handoffFileVersionToExternalApp(
        session: NextcloudSession,
        userId: String,
        file: NextcloudFile,
        version: NextcloudFileVersion,
        action: ExternalFileHandoffAction,
    ): ExternalFileHandoffResult = ExternalFileHandoffResult.Unsupported(
        "Historical file export is not supported on this platform.",
    )

    suspend fun saveTextFile(
        session: NextcloudSession,
        userId: String,
        path: String,
        text: String,
        expectedEtag: String,
    ): SavedTextFile

    /** Creates a text file only when the destination does not already exist. */
    suspend fun createTextFileIfAbsent(
        session: NextcloudSession,
        userId: String,
        path: String,
        text: String,
    ): SavedTextFile

    /** Creates a WebDAV collection only when the destination does not already exist. */
    suspend fun createDirectoryIfAbsent(
        session: NextcloudSession,
        userId: String,
        path: String,
    ): Boolean

    suspend fun executeFileMutation(
        session: NextcloudSession,
        userId: String,
        mutation: NextcloudFileMutation,
    ): NextcloudFileMutationResult

    suspend fun executeNextcloudApi(
        session: NextcloudSession,
        request: NextcloudApiRequest,
    ): NextcloudApiResponse

    /**
     * Opens the platform document picker. Only a file explicitly selected by the user can produce
     * an opaque [LocalUploadFile] capability.
     */
    suspend fun chooseLocalUploadFile(
        acceptedMimeTypes: List<String> = listOf("*/*"),
        maximumBytes: Long = DEFAULT_LOCAL_UPLOAD_LIMIT_BYTES,
    ): LocalUploadSelectionResult = LocalUploadSelectionResult.Unavailable(
        "Local file selection is unavailable on this platform.",
    )

    /** Releases an opaque picker capability without changing or deleting the local file. */
    fun releaseLocalUploadFile(file: LocalUploadFile) = Unit

    /**
     * Streams one picker-authorized file to a reviewed same-origin multipart endpoint.
     *
     * Implementations attach the active account credentials, reject redirects, enforce both
     * request and response limits, and never accept an arbitrary local path from shared code.
     */
    suspend fun executeNextcloudMultipartUpload(
        session: NextcloudSession,
        request: NextcloudMultipartUploadRequest,
    ): NextcloudApiResponse {
        error("Multipart upload is unavailable on this platform.")
    }

    /**
     * Takes ownership of a picker capability and schedules a lifecycle-independent upload.
     *
     * The default implementation completes synchronously for platforms without a background
     * scheduler. Android overrides this with app-private durable state and WorkManager.
     */
    suspend fun enqueueDurableMultipartUpload(
        session: NextcloudSession,
        scope: DurableUploadScope,
        request: NextcloudMultipartUploadRequest,
    ): DurableUploadEnqueueResult {
        val status = DurableUploadStatus(
            id = request.file.selectionId,
            scope = scope,
            displayName = request.file.displayName,
            state = DurableUploadState.Uploading,
        )
        return runCatching {
            val response = executeNextcloudMultipartUpload(session, request)
            require(response.status in 200..299) {
                "The attachment upload failed (HTTP ${response.status})."
            }
            DurableUploadEnqueueResult.Completed(status.copy(state = DurableUploadState.Completed))
        }.getOrElse { error ->
            DurableUploadEnqueueResult.Rejected(
                error.message?.take(MAX_DURABLE_UPLOAD_MESSAGE_CHARACTERS)
                    ?: "The attachment upload failed.",
            )
        }
    }

    /** Returns bounded persisted upload state for the active account and resource. */
    suspend fun durableMultipartUploadStatuses(
        session: NextcloudSession,
        scope: DurableUploadScope,
    ): List<DurableUploadStatus> = emptyList()

    /** Dismisses one terminal upload status. Active work cannot be removed through the UI. */
    suspend fun dismissDurableMultipartUpload(
        session: NextcloudSession,
        scope: DurableUploadScope,
        uploadId: String,
    ): Boolean = false

    /**
     * Dedicated same-origin CalDAV/CardDAV transport.
     *
     * DAV needs methods and conditional headers that are intentionally unavailable to dynamic
     * app contracts. Implementations must keep authentication on the account origin and reject
     * redirects.
     */
    suspend fun executeGroupwareDav(
        session: NextcloudSession,
        request: GroupwareDavRequest,
    ): NextcloudApiResponse {
        error("Groupware DAV transport is unavailable on this platform.")
    }

    /**
     * Dedicated same-origin transport for reviewed Photos DAV collection mutations.
     *
     * Implementations must reject redirects and may only attach the request's conflict and
     * destination headers after rebuilding both URLs against the authenticated account origin.
     */
    suspend fun executeMediaCollectionMutation(
        session: NextcloudSession,
        request: NativeMediaCollectionTransportRequest,
    ): NextcloudApiResponse {
        error("Media collection mutation transport is unavailable on this platform.")
    }

    /**
     * Dedicated same-origin transport for a reviewed people mutation.
     *
     * Implementations must not redirect, persist the Recognize token, or expose it to generic API
     * request hooks. Only [PeopleMutationService] should call this after explicit confirmation.
     */
    suspend fun executePeopleMutation(
        session: NextcloudSession,
        request: PeopleTransportRequest,
    ): NextcloudApiResponse {
        error("People mutation transport is unavailable on this platform.")
    }

    suspend fun acquireSignedOpenApiContract(
        appId: String,
        serverVersion: String,
        installedAppVersion: String?,
    ): AcquiredOpenApiContract?

    suspend fun listActivities(session: NextcloudSession, limit: Int = DEFAULT_ACTIVITY_LIMIT): List<NextcloudActivity>

    suspend fun listNotes(session: NextcloudSession): List<NextcloudNote>

    /**
     * Reads the server-wide direct-editing inventory without creating an edit token.
     *
     * Implementations should preserve the response ETag so repeated discovery can use a
     * conditional request. A token-producing document open is a separate explicit action.
     */
    suspend fun loadDocumentEditingCapabilities(
        session: NextcloudSession,
        expectedEtag: String? = null,
    ): NextcloudConditionalRead<NextcloudDocumentEditingCapabilities> =
        NextcloudConditionalRead.Modified(
            NextcloudDocumentEditingCapabilities.Unavailable,
            responseEtag = null,
        )

    /** Lists token-free template metadata for an advertised document creator. */
    suspend fun listDocumentTemplates(
        session: NextcloudSession,
        editorId: String,
        creatorId: String,
    ): List<NextcloudDocumentTemplate> = emptyList()

    /**
     * Creates one short-lived, same-origin edit handoff after a visible user action.
     *
     * The request must have been produced by a trusted file-integration planner. Platform
     * implementations must reject redirects and validate the returned URL against the account
     * origin.
     */
    suspend fun beginDocumentEditSession(
        session: NextcloudSession,
        request: NextcloudDocumentEditSessionRequest,
    ): NextcloudDocumentEditSession = error("Document direct editing is not supported on this platform.")

    suspend fun listNotesConditionally(
        session: NextcloudSession,
        expectedEtag: String?,
    ): NextcloudConditionalRead<List<NextcloudNote>> =
        NextcloudConditionalRead.Modified(listNotes(session), responseEtag = null)

    suspend fun loadNote(session: NextcloudSession, noteId: Long): NextcloudNote

    /** Returns [Absent] only when an authoritative detail request answers 404 or 410. */
    suspend fun inspectNotePresence(
        session: NextcloudSession,
        noteId: Long,
    ): NextcloudNotePresence = NextcloudNotePresence.Present(loadNote(session, noteId))

    suspend fun loadNoteConditionally(
        session: NextcloudSession,
        noteId: Long,
        expectedEtag: String?,
    ): NextcloudConditionalRead<NextcloudNote> =
        NextcloudConditionalRead.Modified(loadNote(session, noteId), responseEtag = null)

    suspend fun updateNote(
        session: NextcloudSession,
        noteId: Long,
        content: String,
        category: String,
        favorite: Boolean,
        expectedEtag: String?,
        title: String? = null,
    ): NextcloudNote

    suspend fun createNote(
        session: NextcloudSession,
        title: String,
        content: String,
        category: String,
    ): NextcloudNote = error("Creating notes is not supported on this platform.")

    suspend fun deleteNote(
        session: NextcloudSession,
        noteId: Long,
        expectedEtag: String? = null,
    ) {
        error("Deleting notes is not supported on this platform.")
    }

    suspend fun renameNoteCategory(
        session: NextcloudSession,
        oldCategory: String,
        newCategory: String,
    ) {
        val source = normalizeNoteCategory(oldCategory)
        val destination = normalizeNoteCategory(newCategory)
        require(source.isNotEmpty() && destination.isNotEmpty() && source != destination) {
            "Choose a valid destination folder."
        }
        require(!destination.startsWith("$source/")) { "A folder cannot be moved inside itself." }
        val summaries = listNotes(session)
        executeNoteFolderRename(
            summaries = summaries,
            oldCategory = source,
            newCategory = destination,
            loadNote = { noteId -> loadNote(session, noteId) },
            updateNote = { mutation ->
                val note = mutation.note
                updateNote(
                    session = session,
                    noteId = note.id,
                    content = requireNotNull(note.content),
                    category = requireNotNull(mutation.destinationCategory),
                    favorite = note.favorite,
                    expectedEtag = requireNotNull(note.etag),
                    title = note.title,
                )
            },
            reloadSummaries = { listNotes(session) },
        )
    }

    suspend fun deleteNoteCategory(
        session: NextcloudSession,
        category: String,
    ) {
        val path = normalizeNoteCategory(category)
        require(path.isNotEmpty()) { "The root note folder cannot be deleted." }
        val summaries = listNotes(session)
        executeNoteFolderDelete(
            summaries = summaries,
            category = path,
            loadNote = { noteId -> loadNote(session, noteId) },
            deleteNote = { mutation ->
                deleteNote(
                    session = session,
                    noteId = mutation.note.id,
                    expectedEtag = requireNotNull(mutation.note.etag),
                )
            },
            reloadSummaries = { listNotes(session) },
        )
    }

    suspend fun listPeople(session: NextcloudSession, backend: String = "recognize"): List<NextcloudPerson>

    suspend fun loadPersonCover(session: NextcloudSession, person: NextcloudPerson): ByteArray

    suspend fun listPersonMedia(session: NextcloudSession, person: NextcloudPerson): List<NextcloudFile>

    suspend fun listTalkRooms(session: NextcloudSession): List<TalkRoom>

    suspend fun listTalkMessages(session: NextcloudSession, token: String): List<TalkMessage>

    suspend fun listTalkMessagePage(
        session: NextcloudSession,
        token: String,
        olderCursor: Long? = null,
        limit: Int = DEFAULT_TALK_MESSAGE_PAGE_SIZE,
    ): TalkMessagePage = TalkMessagePage(
        messages = listTalkMessages(session, token),
        olderCursor = null,
        hasMoreHistory = false,
    )

    suspend fun sendTalkMessage(session: NextcloudSession, token: String, message: String)

    suspend fun revokeSession(session: NextcloudSession)
}

/**
 * Read-only handle for one immutable remote file generation.
 *
 * Closing the handle is idempotent from the caller's perspective. Platform implementations own
 * the synchronization needed to reject new reads and cancel an active request.
 */
class NextcloudFileRangeSession(
    val size: Long,
    private val readBlock: suspend (offset: Long, length: Int) -> ByteArray,
    private val closeBlock: () -> Unit = {},
) : AutoCloseable {
    init {
        require(size > 0L) { "A file range session must have a positive size." }
    }

    suspend fun read(offset: Long, length: Int): ByteArray = readBlock(offset, length)

    override fun close() = closeBlock()
}

const val DEFAULT_PREVIEW_DIMENSION = 512
const val MIN_PREVIEW_DIMENSION = 32
const val MAX_PREVIEW_DIMENSION = 2048
const val DEFAULT_FILE_DOWNLOAD_LIMIT_BYTES = 64L * 1024L * 1024L
const val MAX_OFFLINE_FILE_BYTES = 512L * 1024L * 1024L
const val MAX_EDITABLE_TEXT_BYTES = 4L * 1024L * 1024L
const val DEFAULT_ACTIVITY_LIMIT = 50
const val DEFAULT_TALK_MESSAGE_PAGE_SIZE = 100
const val MAX_TALK_MESSAGE_PAGE_SIZE = 200
const val MAX_ACTIVITY_LIMIT = 200
const val MAX_NOTE_BYTES = 4L * 1024L * 1024L
const val DEFAULT_DYNAMIC_API_RESPONSE_LIMIT_BYTES = 4L * 1024L * 1024L
const val MAX_DYNAMIC_API_RESPONSE_LIMIT_BYTES = 16L * 1024L * 1024L
const val MAX_FILE_RANGE_ETAG_LENGTH = 1_024

fun requireSafeFileRangeEtag(value: String): String {
    require(value == value.trim() && value.isNotEmpty() && value.length <= MAX_FILE_RANGE_ETAG_LENGTH) {
        "A safe current strong ETag is required for a file range read."
    }
    if (value.first() == '"' || value.last() == '"') {
        require(
            value.length >= 2 &&
                value.first() == '"' &&
                value.last() == '"' &&
                value.substring(1, value.lastIndex).all(::isHttpEntityTagCharacter),
        ) {
            "A safe current strong ETag is required for a file range read."
        }
        return value
    }
    require(
        value != "*" &&
            value.length <= MAX_FILE_RANGE_ETAG_LENGTH - 2 &&
            value.all(::isHttpEntityTagCharacter),
    ) {
        "A safe current strong ETag is required for a file range read."
    }
    return "\"$value\""
}

private fun isHttpEntityTagCharacter(character: Char): Boolean =
    character.code == 0x21 ||
        character.code in 0x23..0x7E ||
        character.code in 0x80..0xFF

fun NextcloudApiRequest.requireSafe(): NextcloudApiRequest {
    require(relativePath.startsWith('/') && !relativePath.startsWith("//")) {
        "Dynamic API paths must be relative to the connected Nextcloud server."
    }
    require(relativePath.none { it == '\\' || it == '?' || it == '#' || it.isWhitespace() }) {
        "Dynamic API paths cannot contain a query, fragment, backslash, or whitespace."
    }
    require(relativePath.split('/').none { segment ->
        val decodedDots = segment.replace("%2e", ".", ignoreCase = true)
        decodedDots == "." || decodedDots == ".."
    }) { "Dynamic API paths cannot traverse directories." }
    require(queryParameters.keys.all { key ->
        key.isNotBlank() && key.none { it.isWhitespace() || it == '&' || it == '=' || it == '#' }
    }) { "Dynamic API query parameter names are invalid." }
    val maximumAllowedResponse = if (
        relativePath.matches(Regex("^/index\\.php/apps/memories/api/image/decodable/[1-9][0-9]*$"))
    ) {
        DEFAULT_FILE_DOWNLOAD_LIMIT_BYTES
    } else {
        MAX_DYNAMIC_API_RESPONSE_LIMIT_BYTES
    }
    require(maximumResponseBytes in 1..maximumAllowedResponse) {
        "Dynamic API response limit is outside the allowed range."
    }
    require(contentType == null || contentType.length <= 160) { "Dynamic API content type is invalid." }
    multipartBody?.let { multipart ->
        require(method in setOf(NextcloudApiMethod.POST, NextcloudApiMethod.PUT, NextcloudApiMethod.PATCH)) {
            "Multipart uploads require POST, PUT, or PATCH."
        }
        require(body == null && contentType == null) {
            "A typed multipart body cannot be combined with a raw request body or content type."
        }
        multipart.requireSafe()
    }
    return this
}

fun buildNextcloudApiUrl(serverUrl: String, request: NextcloudApiRequest): String {
    request.requireSafe()
    val base = serverUrl.trimEnd('/') + request.relativePath
    if (request.queryParameters.isEmpty()) return base
    val query = request.queryParameters.toSortedMap().entries.joinToString("&") { (key, value) ->
        "${encodeUrlComponent(key)}=${encodeUrlComponent(value)}"
    }
    return "$base?$query"
}

fun boundedActivityLimit(value: Int): Int = value.coerceIn(1, MAX_ACTIVITY_LIMIT)

fun boundedPreviewDimension(value: Int): Int = value.coerceIn(MIN_PREVIEW_DIMENSION, MAX_PREVIEW_DIMENSION)

fun buildNextcloudFileUrl(serverUrl: String, userId: String, path: String): String {
    val encodedUserId = encodeUrlPathSegment(userId)
    val encodedPath = path
        .split('/')
        .filter(String::isNotEmpty)
        .onEach { segment ->
            require(segment != "." && segment != "..") { "The file path contains an invalid segment." }
        }
        .joinToString("/") { encodeUrlPathSegment(it) }
    return serverUrl.trimEnd('/') + "/remote.php/dav/files/$encodedUserId/" + encodedPath
}

private fun encodeUrlPathSegment(value: String): String = encodeUrlComponent(value)

private fun encodeUrlComponent(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val unsigned = byte.toInt() and 0xff
        val isUnreserved = unsigned in 'a'.code..'z'.code ||
            unsigned in 'A'.code..'Z'.code ||
            unsigned in '0'.code..'9'.code ||
            unsigned == '-'.code || unsigned == '.'.code || unsigned == '_'.code || unsigned == '~'.code
        if (isUnreserved) {
            append(unsigned.toChar())
        } else {
            append('%')
            append(HEX_DIGITS[unsigned ushr 4])
            append(HEX_DIGITS[unsigned and 0x0f])
        }
    }
}

private const val HEX_DIGITS = "0123456789ABCDEF"
