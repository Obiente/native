package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The subset of the standard Nextcloud sharing capability contract this client can prove and use.
 *
 * Missing fields remain unavailable instead of being guessed from an installed-app navigation
 * entry. The Share API remains authoritative when a server applies a more specific policy.
 */
data class NextcloudFileSharingCapabilities(
    val apiEnabled: Boolean = false,
    val publicLinks: Boolean = false,
    val userShares: Boolean = false,
    val groupShares: Boolean = false,
    /**
     * Whether the standard sharee endpoint can be queried for email recipients.
     *
     * This is transport capability only. It does not prove that an email share provider exists.
     */
    val emailRecipientQuery: Boolean = false,
    /** Whether the server explicitly advertises its share-by-mail provider. */
    val emailProviderAdvertised: Boolean = false,
    /** Whether a filtered sharee response actually returned an email provider result. */
    val emailProviderObserved: Boolean = false,
    val remoteShares: Boolean = false,
    val publicPasswordSupported: Boolean = false,
    val publicPasswordEnforced: Boolean = false,
    val emailPasswordSupported: Boolean = false,
    val emailPasswordEnforced: Boolean = false,
    val publicExpirationSupported: Boolean = false,
    val publicExpirationEnforced: Boolean = false,
    val userExpirationSupported: Boolean = false,
    val groupExpirationSupported: Boolean = false,
    val emailExpirationSupported: Boolean = false,
    val emailExpirationEnforced: Boolean = false,
    val remoteExpirationSupported: Boolean = false,
    val defaultPermissions: Int? = null,
) {
    val emailShares: Boolean
        get() = emailProviderAdvertised || emailProviderObserved

    val supportsAnyCreation: Boolean
        get() = apiEnabled && (publicLinks || userShares || groupShares || emailShares || remoteShares)

    companion object {
        val Unavailable = NextcloudFileSharingCapabilities()
    }
}

fun parseNextcloudFileSharingCapabilities(json: String): NextcloudFileSharingCapabilities {
    val parsed = runCatching { fileSharingJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
        ?: return NextcloudFileSharingCapabilities.Unavailable
    val capabilities = parsed.objectAt("ocs")
        ?.objectAt("data")
        ?.objectAt("capabilities")
        ?: parsed.objectAt("capabilities")
        ?: parsed
    val sharing = capabilities.objectAt("files_sharing")
        ?: return NextcloudFileSharingCapabilities.Unavailable
    val apiEnabled = sharing.booleanAt("api_enabled") == true
    if (!apiEnabled) return NextcloudFileSharingCapabilities.Unavailable
    val public = sharing.objectAt("public")
    val group = sharing.objectAt("group")
    val publicLinks = public?.booleanAt("enabled") == true
    val publicPassword = public?.objectAt("password")
    val publicExpiration = public?.objectAt("expire_date")
    val publicExpirationEnabled = publicLinks && publicExpiration?.booleanAt("enabled") == true
    val shareByMail = sharing.objectAt("sharebymail")
    val emailPassword = shareByMail?.objectAt("password")
    val emailExpiration = shareByMail?.objectAt("expire_date")
    val federation = sharing.objectAt("federation")
    return NextcloudFileSharingCapabilities(
        apiEnabled = true,
        publicLinks = publicLinks,
        userShares = sharing["user"] is JsonObject,
        groupShares = group?.booleanAt("enabled") == true || sharing.booleanAt("group_sharing") == true,
        emailRecipientQuery = true,
        emailProviderAdvertised = publicLinks &&
            shareByMail?.booleanAt("enabled") == true,
        remoteShares = federation?.booleanAt("outgoing") == true,
        publicPasswordSupported = publicLinks && publicPassword != null,
        publicPasswordEnforced = publicPassword?.booleanAt("enforced") == true,
        emailPasswordSupported = shareByMail?.booleanAt("enabled") == true &&
            emailPassword?.booleanAt("enabled") == true,
        emailPasswordEnforced = emailPassword?.booleanAt("enforced") == true,
        publicExpirationSupported = publicExpirationEnabled,
        publicExpirationEnforced = publicExpirationEnabled &&
            publicExpiration?.booleanAt("enforced") == true,
        userExpirationSupported = sharing.objectAt("user")
            ?.objectAt("expire_date")
            ?.booleanAt("enabled") == true,
        groupExpirationSupported = group?.objectAt("expire_date")?.booleanAt("enabled") == true,
        emailExpirationSupported = shareByMail?.booleanAt("enabled") == true &&
            emailExpiration?.booleanAt("enabled") == true,
        emailExpirationEnforced = emailExpiration?.booleanAt("enforced") == true,
        remoteExpirationSupported = federation
            ?.objectAt("expire_date_supported")
            ?.booleanAt("enabled") == true,
        defaultPermissions = sharing.intAt("default_permissions")?.takeIf { it in 1..31 },
    )
}

fun NextcloudFileSharingCapabilities.supports(target: FileShareTarget): Boolean =
    apiEnabled && when (target) {
        FileShareTarget.PublicLink -> publicLinks
        FileShareTarget.User -> userShares
        FileShareTarget.Group -> groupShares
        FileShareTarget.Email -> emailShares
        FileShareTarget.Remote -> remoteShares
    }

fun NextcloudFileSharingCapabilities.withObservedRecipientProvider(
    target: FileShareTarget,
    recipients: List<FileShareRecipient>,
): NextcloudFileSharingCapabilities =
    if (
        target == FileShareTarget.Email &&
        recipients.any { it.target == FileShareTarget.Email }
    ) {
        copy(emailProviderObserved = true)
    } else {
        this
    }

data class FileShareFeaturePolicy(
    val supported: Boolean,
    val enforced: Boolean = false,
)

fun NextcloudFileSharingCapabilities.passwordPolicy(target: FileShareTarget): FileShareFeaturePolicy =
    when (target) {
        FileShareTarget.PublicLink -> FileShareFeaturePolicy(publicPasswordSupported, publicPasswordEnforced)
        FileShareTarget.Email -> FileShareFeaturePolicy(emailPasswordSupported, emailPasswordEnforced)
        FileShareTarget.User,
        FileShareTarget.Group,
        FileShareTarget.Remote,
        -> FileShareFeaturePolicy(supported = false)
    }

fun NextcloudFileSharingCapabilities.expirationPolicy(target: FileShareTarget): FileShareFeaturePolicy =
    when (target) {
        FileShareTarget.PublicLink -> FileShareFeaturePolicy(publicExpirationSupported, publicExpirationEnforced)
        FileShareTarget.User -> FileShareFeaturePolicy(userExpirationSupported)
        FileShareTarget.Group -> FileShareFeaturePolicy(groupExpirationSupported)
        FileShareTarget.Email -> FileShareFeaturePolicy(emailExpirationSupported, emailExpirationEnforced)
        FileShareTarget.Remote -> FileShareFeaturePolicy(remoteExpirationSupported)
    }

fun NextcloudFileSharingCapabilities.canOffer(target: FileShareTarget): Boolean =
    supports(target) || target == FileShareTarget.Email && emailRecipientQuery

enum class FileShareItemType(val wireValue: String) {
    File("file"),
    Folder("folder"),
}

fun NextcloudFile.fileShareItemType(): FileShareItemType =
    if (isDirectory) FileShareItemType.Folder else FileShareItemType.File

data class ListFileSharesRequest(
    val path: String,
    val includeReshares: Boolean = true,
)

data class SearchFileShareRecipientsRequest(
    val query: String,
    val target: FileShareTarget,
    val itemType: FileShareItemType = FileShareItemType.File,
    val limit: Int = DEFAULT_FILE_SHARE_RECIPIENT_LIMIT,
)

data class FileShareRecipient(
    val id: String,
    val displayName: String,
    val target: FileShareTarget,
    val exact: Boolean = false,
)

fun SearchFileShareRecipientsRequest.toNextcloudApiRequest(): NextcloudApiRequest {
    val safeQuery = query.trim()
    require(safeQuery.length >= MIN_FILE_SHARE_RECIPIENT_QUERY_LENGTH) {
        "Enter at least $MIN_FILE_SHARE_RECIPIENT_QUERY_LENGTH characters."
    }
    require(
        safeQuery.length <= MAX_FILE_SHARE_RECIPIENT_QUERY_LENGTH &&
            safeQuery.none(Char::isISOControl),
    ) { "The recipient search is invalid or too long." }
    require(target.requiresRecipient) {
        "Public links do not use recipient discovery."
    }
    require(limit in 1..MAX_FILE_SHARE_RECIPIENT_LIMIT) {
        "The recipient result limit is invalid."
    }
    return NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = FILE_SHAREES_RELATIVE_PATH,
        queryParameters = mapOf(
            "format" to "json",
            "search" to safeQuery,
            "itemType" to itemType.wireValue,
            "shareType" to target.wireValue.toString(),
            "lookup" to "false",
            "perPage" to limit.toString(),
        ),
        ocsApiRequest = true,
        maximumResponseBytes = MAX_FILE_SHARE_RECIPIENT_RESPONSE_BYTES,
    )
}

suspend fun NextcloudPlatformServices.searchFileShareRecipients(
    session: NextcloudSession,
    query: String,
    target: FileShareTarget,
    file: NextcloudFile,
    limit: Int = DEFAULT_FILE_SHARE_RECIPIENT_LIMIT,
): List<FileShareRecipient> {
    val request = SearchFileShareRecipientsRequest(query, target, file.fileShareItemType(), limit)
    return parseFileShareRecipientsResponse(executeNextcloudApi(session, request.toNextcloudApiRequest()), target)
}

fun parseFileShareRecipientsResponse(
    response: NextcloudApiResponse,
    target: FileShareTarget,
): List<FileShareRecipient> {
    require(target.requiresRecipient) {
        "Public links do not use recipient discovery."
    }
    if (response.status !in 200..299) throw fileOperationException(response.status)
    val root = runCatching {
        fileSharingJson.parseToJsonElement(response.body.decodeToString()) as? JsonObject
    }.getOrNull() ?: error("The recipient response is not valid JSON.")
    val ocs = root.objectAt("ocs") ?: error("The recipient response is missing OCS data.")
    requireSuccessfulShareOcsMeta(ocs)
    val data = ocs.objectAt("data") ?: error("The recipient response has no results.")
    val collectionName = when (target) {
        FileShareTarget.User -> "users"
        FileShareTarget.Group -> "groups"
        FileShareTarget.Email -> "emails"
        FileShareTarget.Remote -> "remotes"
        FileShareTarget.PublicLink -> error("Public links do not use recipient discovery.")
    }
    val exactResults = data.objectAt("exact")
        ?.get(collectionName) as? JsonArray
        ?: data["exact_$collectionName"] as? JsonArray
        ?: JsonArray(emptyList())
    val regularResults = data[collectionName] as? JsonArray ?: JsonArray(emptyList())
    val seen = mutableSetOf<String>()
    return (exactResults.map { it to true } + regularResults.map { it to false })
        .mapNotNull { (element, exact) -> parseFileShareRecipient(element, target, exact) }
        .filter { seen.add(it.id) }
        .take(MAX_FILE_SHARE_RECIPIENT_LIMIT)
}

fun ListFileSharesRequest.toNextcloudApiRequest(): NextcloudApiRequest {
    val safePath = requireSafeFilePath(path, allowRoot = false)
    require(safePath.encodeToByteArray().size <= MAX_LIST_FILE_SHARE_PATH_BYTES) {
        "The shared file path is too long."
    }
    return NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = FILE_SHARES_RELATIVE_PATH,
        queryParameters = mapOf(
            "format" to "json",
            "path" to "/$safePath",
            "reshares" to includeReshares.toString(),
        ),
        ocsApiRequest = true,
        maximumResponseBytes = MAX_LIST_FILE_SHARES_RESPONSE_BYTES,
    )
}

suspend fun NextcloudPlatformServices.listFileShares(
    session: NextcloudSession,
    path: String,
): List<NextcloudFileShare> = parseNextcloudFileSharesResponse(
    executeNextcloudApi(session, ListFileSharesRequest(path).toNextcloudApiRequest()),
)

data class UpdateFileSharePermissionsRequest(
    val shareId: String,
    val permissions: FileSharePermissions,
)

data class UpdateFileShareRequest(
    val shareId: String,
    val target: FileShareTarget?,
    val permissions: FileSharePermissions? = null,
    val password: String? = null,
    val expirationDate: String? = null,
    val note: String? = null,
)

data class RevokeFileShareRequest(val shareId: String)

fun UpdateFileSharePermissionsRequest.toNextcloudApiRequest(): NextcloudApiRequest {
    return UpdateFileShareRequest(
        shareId = shareId,
        target = null,
        permissions = permissions,
    ).toNextcloudApiRequest()
}

fun UpdateFileShareRequest.toNextcloudApiRequest(): NextcloudApiRequest =
    toNextcloudApiRequest(DeviceLocalFileShareDateSource)

internal fun UpdateFileShareRequest.toNextcloudApiRequest(
    dateSource: FileShareDateSource,
): NextcloudApiRequest {
    val safeId = requireSafeFileShareId(shareId)
    require(permissions == null || permissions.mask != 0) { "At least one share permission is required." }
    require(password == null || password.length <= MAX_UPDATE_FILE_SHARE_PASSWORD_LENGTH &&
        password.none(Char::isISOControl)
    ) { "The share password is invalid or too long." }
    require(
        password == null ||
            target == FileShareTarget.PublicLink ||
            target == FileShareTarget.Email,
    ) { "Passwords are only available for link and email shares." }
    val safeExpiration = expirationDate?.let {
        if (it.isEmpty()) "" else requireNonPastFileShareDate(it, dateSource)
    }
    require(note == null || note.length <= MAX_UPDATE_FILE_SHARE_NOTE_LENGTH && note.none(Char::isISOControl)) {
        "The share note is invalid or too long."
    }
    val fields = buildList {
        permissions?.let { add("permissions" to it.mask.toString()) }
        password?.let { add("password" to it) }
        safeExpiration?.let { add("expireDate" to it) }
        note?.let { add("note" to it) }
    }
    require(fields.isNotEmpty()) { "At least one share setting must change." }
    val body = fields.joinToString("&") { (name, value) ->
        "${encodeFileShareFormComponent(name)}=${encodeFileShareFormComponent(value)}"
    }.encodeToByteArray()
    require(body.size <= MAX_UPDATE_FILE_SHARE_REQUEST_BYTES) { "The share update is too large." }
    return NextcloudApiRequest(
        method = NextcloudApiMethod.PUT,
        relativePath = "$FILE_SHARES_RELATIVE_PATH/$safeId",
        queryParameters = mapOf("format" to "json"),
        contentType = "application/x-www-form-urlencoded; charset=utf-8",
        body = body,
        ocsApiRequest = true,
        maximumResponseBytes = MAX_FILE_SHARE_MUTATION_RESPONSE_BYTES,
    )
}

fun RevokeFileShareRequest.toNextcloudApiRequest(): NextcloudApiRequest =
    NextcloudApiRequest(
        method = NextcloudApiMethod.DELETE,
        relativePath = "$FILE_SHARES_RELATIVE_PATH/${requireSafeFileShareId(shareId)}",
        queryParameters = mapOf("format" to "json"),
        ocsApiRequest = true,
        maximumResponseBytes = MAX_FILE_SHARE_MUTATION_RESPONSE_BYTES,
    )

suspend fun NextcloudPlatformServices.updateFileSharePermissions(
    session: NextcloudSession,
    shareId: String,
    permissions: FileSharePermissions,
) {
    requireSuccessfulFileShareMutation(
        executeNextcloudApi(
            session,
            UpdateFileSharePermissionsRequest(shareId, permissions).toNextcloudApiRequest(),
        ),
    )
}

suspend fun NextcloudPlatformServices.updateFileShare(
    session: NextcloudSession,
    request: UpdateFileShareRequest,
): NextcloudFileShare = parseNextcloudFileShareResponse(
    executeNextcloudApi(session, request.toNextcloudApiRequest()),
)

suspend fun NextcloudPlatformServices.revokeFileShare(
    session: NextcloudSession,
    shareId: String,
) {
    requireSuccessfulFileShareMutation(
        executeNextcloudApi(session, RevokeFileShareRequest(shareId).toNextcloudApiRequest()),
    )
}

fun fileSharePermissionsFromMask(mask: Int?): FileSharePermissions = FileSharePermissions(
    read = mask == null || mask and 1 != 0,
    update = mask != null && mask and 2 != 0,
    create = mask != null && mask and 4 != 0,
    delete = mask != null && mask and 8 != 0,
    reshare = mask != null && mask and 16 != 0,
)

fun fileSharePermissionsLabel(mask: Int?): String {
    if (mask == null) return "Permissions set by server"
    val permissions = fileSharePermissionsFromMask(mask)
    return buildList {
        if (permissions.read) add("View")
        if (permissions.update) add("Edit")
        if (permissions.create) add("Create")
        if (permissions.delete) add("Delete")
        if (permissions.reshare) add("Reshare")
    }.joinToString(" · ").ifEmpty { "No access" }
}

fun NextcloudFileShare.target(): FileShareTarget? = FileShareTarget.entries.firstOrNull {
    it.wireValue == shareType
}

fun parseNextcloudFileSharesResponse(response: NextcloudApiResponse): List<NextcloudFileShare> {
    if (response.status !in 200..299) throw fileOperationException(response.status)
    val root = runCatching {
        fileSharingJson.parseToJsonElement(response.body.decodeToString()) as? JsonObject
    }.getOrNull() ?: error("The share response is not valid JSON.")
    val ocs = root.objectAt("ocs") ?: error("The share response is missing OCS data.")
    requireSuccessfulShareOcsMeta(ocs)
    val records = ocs["data"] as? JsonArray ?: error("The share response has no share list.")
    return records.take(MAX_LIST_FILE_SHARE_RECORDS).mapNotNull(::parseFileShareRecord)
}

private fun requireSuccessfulFileShareMutation(response: NextcloudApiResponse) {
    if (response.status !in 200..299) throw fileOperationException(response.status)
    if (response.body.isEmpty()) return
    val root = runCatching {
        fileSharingJson.parseToJsonElement(response.body.decodeToString()) as? JsonObject
    }.getOrNull() ?: error("The share response is not valid JSON.")
    val ocs = root.objectAt("ocs") ?: error("The share response is missing OCS data.")
    requireSuccessfulShareOcsMeta(ocs)
}

private fun requireSafeFileShareId(id: String): String {
    val safeId = id.trim()
    require(
        safeId.isNotEmpty() &&
            safeId.length <= MAX_LIST_FILE_SHARE_ID_LENGTH &&
            safeId.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' },
    ) { "The share identifier is invalid." }
    return safeId
}

private fun encodeFileShareFormComponent(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val unsigned = byte.toInt() and 0xff
        val unreserved = unsigned in 'a'.code..'z'.code || unsigned in 'A'.code..'Z'.code ||
            unsigned in '0'.code..'9'.code || unsigned == '-'.code || unsigned == '.'.code ||
            unsigned == '_'.code || unsigned == '~'.code
        if (unreserved) {
            append(unsigned.toChar())
        } else {
            append('%')
            append(FILE_SHARE_HEX_DIGITS[unsigned ushr 4])
            append(FILE_SHARE_HEX_DIGITS[unsigned and 0x0f])
        }
    }
}

sealed interface FileShareCreationPlan {
    data class Ready(val request: CreateFileShareRequest) : FileShareCreationPlan
    data class Blocked(val reason: String) : FileShareCreationPlan
}

fun planFileShareCreation(
    file: NextcloudFile,
    target: FileShareTarget,
    recipient: String?,
    permissions: FileSharePermissions,
    capabilities: NextcloudFileSharingCapabilities,
    details: FileShareCreationDetails = FileShareCreationDetails(),
): FileShareCreationPlan = planFileShareCreation(
    file = file,
    target = target,
    recipient = recipient,
    permissions = permissions,
    capabilities = capabilities,
    details = details,
    dateSource = DeviceLocalFileShareDateSource,
)

internal fun planFileShareCreation(
    file: NextcloudFile,
    target: FileShareTarget,
    recipient: String?,
    permissions: FileSharePermissions,
    capabilities: NextcloudFileSharingCapabilities,
    details: FileShareCreationDetails,
    dateSource: FileShareDateSource,
): FileShareCreationPlan {
    val unavailableReason = fileNativeSharingDisabledReason(file, capabilities)
    if (unavailableReason != null) return FileShareCreationPlan.Blocked(unavailableReason)
    val targetReason = when (target) {
        FileShareTarget.PublicLink ->
            if (capabilities.publicLinks) null else "Public links are disabled on this server."
        FileShareTarget.User ->
            if (capabilities.userShares) null else "Sharing with users is unavailable on this server."
        FileShareTarget.Group ->
            if (capabilities.groupShares) null else "Sharing with groups is unavailable on this server."
        FileShareTarget.Email ->
            if (capabilities.emailShares) null else "Sharing by email is unavailable on this server."
        FileShareTarget.Remote ->
            if (capabilities.remoteShares) null else "Federated sharing is unavailable on this server."
    }
    if (targetReason != null) return FileShareCreationPlan.Blocked(targetReason)
    val passwordPolicy = capabilities.passwordPolicy(target)
    if (details.password.isNotEmpty() && !passwordPolicy.supported) {
        return FileShareCreationPlan.Blocked("Passwords are unavailable for this share type.")
    }
    if (passwordPolicy.enforced && details.password.isEmpty()) {
        return FileShareCreationPlan.Blocked("This server requires a password for this share.")
    }
    val expirationPolicy = capabilities.expirationPolicy(target)
    if (details.expiration != FileShareExpiration.ServerDefault && !expirationPolicy.supported) {
        return FileShareCreationPlan.Blocked("Expiration settings are unavailable for this share type.")
    }
    if (details.expiration == FileShareExpiration.NoExpiration && expirationPolicy.enforced) {
        return FileShareCreationPlan.Blocked("This server requires an expiration date for this share.")
    }
    val request = CreateFileShareRequest(
        path = file.path,
        target = target,
        shareWith = recipient,
        permissions = permissions,
        details = details,
    )
    val validationFailure = runCatching { request.toNextcloudApiRequest(dateSource) }.exceptionOrNull()
    return if (validationFailure == null) {
        FileShareCreationPlan.Ready(request)
    } else {
        FileShareCreationPlan.Blocked(validationFailure.message ?: "The share details are invalid.")
    }
}

fun fileNativeSharingDisabledReason(
    file: NextcloudFile,
    capabilities: NextcloudFileSharingCapabilities,
): String? = when {
    !capabilities.apiEnabled -> "File sharing is unavailable for this account."
    file.permissions != null && 'R' !in file.permissions ->
        "You do not have permission to share this item."
    else -> null
}

/**
 * Returns a copy-safe, same-instance URL. Credentials, cross-origin links and control characters
 * are never accepted from server responses.
 */
fun safeFileShareUrl(session: NextcloudSession, share: NextcloudFileShare): String? {
    val candidate = share.url?.trim()?.takeIf {
        it.isNotEmpty() && it.length <= MAX_SAFE_FILE_SHARE_URL_LENGTH && it.none(Char::isISOControl)
    } ?: return null
    val server = session.serverUrl.trim().trimEnd('/')
    val schemeEnd = server.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = server.substring(0, schemeEnd).lowercase()
    if (scheme !in setOf("https", "http")) return null
    val authorityStart = schemeEnd + 3
    val authorityEnd = server.indexOf('/', authorityStart).let { if (it < 0) server.length else it }
    val authority = server.substring(authorityStart, authorityEnd)
    if (authority.isBlank() || '@' in authority) return null
    val serverPrefix = server.substring(0, authorityEnd)
    val candidateSchemeEnd = candidate.indexOf("://")
    if (candidateSchemeEnd <= 0 || candidate.substring(0, candidateSchemeEnd).lowercase() != scheme) return null
    val candidateAuthorityStart = candidateSchemeEnd + 3
    val candidatePrefixEnd = candidate.indexOf('/', candidateAuthorityStart)
        .let { if (it < 0) candidate.length else it }
    if ('@' in candidate.substring(candidateAuthorityStart, candidatePrefixEnd)) return null
    val candidatePrefix = candidate.substring(0, candidatePrefixEnd)
    if (!candidatePrefix.equals(serverPrefix, ignoreCase = true)) return null
    val serverPath = server.substring(authorityEnd)
    val candidatePath = candidate.substring(candidatePrefixEnd)
    if (serverPath.isNotEmpty() && candidatePath != serverPath && !candidatePath.startsWith("$serverPath/")) {
        return null
    }
    return candidate
}

private fun parseFileShareRecord(element: JsonElement): NextcloudFileShare? {
    val data = element as? JsonObject ?: return null
    val id = data.textAt("id")
        ?.takeIf { it.length <= MAX_LIST_FILE_SHARE_ID_LENGTH }
        ?: return null
    return NextcloudFileShare(
        id = id,
        url = data.textAt("url")?.takeIf { it.length <= MAX_SAFE_FILE_SHARE_URL_LENGTH },
        token = data.textAt("token")?.takeIf { it.length <= MAX_LIST_FILE_SHARE_TOKEN_LENGTH },
        shareType = data.intAt("share_type"),
        shareWith = data.textAt("share_with")?.takeIf { it.length <= MAX_LIST_FILE_SHARE_LABEL_LENGTH },
        displayName = data.textAt("share_with_displayname")
            ?.takeIf { it.length <= MAX_LIST_FILE_SHARE_LABEL_LENGTH },
        permissions = data.intAt("permissions")?.takeIf { it in 1..31 },
        expiration = data.textAt("expiration")
            ?.takeIf { it.length <= MAX_LIST_FILE_SHARE_DATE_LENGTH }
            ?.take(10)
            ?.takeIf { runCatching { requireValidFileShareDate(it) }.isSuccess },
        note = data.textAt("note")?.takeIf { it.length <= MAX_LIST_FILE_SHARE_NOTE_LENGTH },
        passwordProtected = data.textAt("password") != null,
    )
}

private fun parseFileShareRecipient(
    element: JsonElement,
    expectedTarget: FileShareTarget,
    exact: Boolean,
): FileShareRecipient? {
    val record = element as? JsonObject ?: return null
    val value = record.objectAt("value") ?: return null
    val resultTarget = when (value.intAt("shareType") ?: value.textAt("shareType")?.toIntOrNull()) {
        FileShareTarget.User.wireValue -> FileShareTarget.User
        FileShareTarget.Group.wireValue -> FileShareTarget.Group
        FileShareTarget.Email.wireValue -> FileShareTarget.Email
        FileShareTarget.Remote.wireValue -> FileShareTarget.Remote
        else -> return null
    }
    if (resultTarget != expectedTarget) return null
    val id = value.textAt("shareWith")
        ?.takeIf { it.length <= MAX_FILE_SHARE_RECIPIENT_ID_LENGTH }
        ?: return null
    val label = record.textAt("label")
        ?.takeIf { it.length <= MAX_LIST_FILE_SHARE_LABEL_LENGTH }
        ?: value.textAt("shareWithDisplayName")
        ?: id
    return FileShareRecipient(
        id = id,
        displayName = label.take(MAX_LIST_FILE_SHARE_LABEL_LENGTH),
        target = resultTarget,
        exact = exact,
    )
}

private fun requireSuccessfulShareOcsMeta(ocs: JsonObject) {
    val meta = ocs.objectAt("meta") ?: return
    val status = meta.intAt("statuscode")
    if (status != null && status !in setOf(100, 200)) {
        val message = meta.textAt("message")
            ?.take(MAX_LIST_FILE_SHARE_ERROR_LENGTH)
            ?: "Nextcloud rejected the share request."
        throw NextcloudFileOperationException(NextcloudFileOperationError.ServerFailure, status, message)
    }
}

private fun JsonObject.objectAt(name: String): JsonObject? = get(name) as? JsonObject
private fun JsonObject.booleanAt(name: String): Boolean? = (get(name) as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.intAt(name: String): Int? = (get(name) as? JsonPrimitive)?.intOrNull
private fun JsonObject.textAt(name: String): String? = (get(name) as? JsonPrimitive)
    ?.contentOrNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() && it.none(Char::isISOControl) }

private val fileSharingJson = Json { ignoreUnknownKeys = true }
private const val FILE_SHARES_RELATIVE_PATH = "/ocs/v2.php/apps/files_sharing/api/v1/shares"
private const val FILE_SHAREES_RELATIVE_PATH = "/ocs/v2.php/apps/files_sharing/api/v1/sharees"
internal const val MIN_FILE_SHARE_RECIPIENT_QUERY_LENGTH = 2
private const val DEFAULT_FILE_SHARE_RECIPIENT_LIMIT = 30
private const val MAX_FILE_SHARE_RECIPIENT_LIMIT = 50
private const val MAX_FILE_SHARE_RECIPIENT_QUERY_LENGTH = 200
private const val MAX_FILE_SHARE_RECIPIENT_ID_LENGTH = 512
private const val MAX_FILE_SHARE_RECIPIENT_RESPONSE_BYTES = 512L * 1024L
private const val MAX_FILE_SHARE_MUTATION_RESPONSE_BYTES = 256L * 1024L
private const val MAX_UPDATE_FILE_SHARE_PASSWORD_LENGTH = 1_024
private const val MAX_UPDATE_FILE_SHARE_NOTE_LENGTH = 2_048
private const val MAX_UPDATE_FILE_SHARE_REQUEST_BYTES = 16 * 1024
private const val MAX_LIST_FILE_SHARE_PATH_BYTES = 4_096
private const val MAX_LIST_FILE_SHARES_RESPONSE_BYTES = 512L * 1024L
private const val MAX_LIST_FILE_SHARE_RECORDS = 500
private const val MAX_LIST_FILE_SHARE_ID_LENGTH = 256
private const val MAX_LIST_FILE_SHARE_TOKEN_LENGTH = 2_048
private const val MAX_LIST_FILE_SHARE_LABEL_LENGTH = 512
private const val MAX_LIST_FILE_SHARE_DATE_LENGTH = 64
private const val MAX_LIST_FILE_SHARE_NOTE_LENGTH = 2_048
private const val MAX_LIST_FILE_SHARE_ERROR_LENGTH = 320
private const val MAX_SAFE_FILE_SHARE_URL_LENGTH = 8_192
private const val FILE_SHARE_HEX_DIGITS = "0123456789ABCDEF"
