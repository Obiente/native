package dev.obiente.nextcloudnative.app

/**
 * Sharing a calendar or address book with people and groups, and publishing a calendar as a
 * read-only link, through Nextcloud's DAV sharing protocol:
 * - the `{http://owncloud.org/ns}invite` property lists current shares;
 * - a POST of `{http://owncloud.org/ns}share` adds (`set`) or removes shares, at most 10 at once;
 * - a POST of `{http://calendarserver.org/ns/}publish-calendar` or `unpublish-calendar` toggles
 *   the public link, which `{http://calendarserver.org/ns/}publish-url` then reports.
 * See apps/dav/lib/DAV/Sharing/Plugin.php and apps/dav/lib/CalDAV/Publishing/PublishPlugin.php
 * in nextcloud/server.
 */
internal enum class DavShareeKind { User, Group, Other }

internal data class DavSharee(
    /** The principal path, such as `principals/users/bob`, as the server names it. */
    val principal: String,
    val displayName: String,
    val kind: DavShareeKind,
) {
    val shareHref: String get() = "principal:$principal"
}

internal data class DavShare(
    val sharee: DavSharee,
    val writable: Boolean,
)

internal data class DavSharingState(
    val shares: List<DavShare>,
    /** The public link while the calendar is published; always null for address books. */
    val publishUrl: String?,
    /** Whether the server offers public links for this collection. */
    val canPublish: Boolean,
)

internal fun davSharingStateRequest(collectionHref: String): GroupwareDavRequest = GroupwareDavRequest(
    method = "PROPFIND",
    relativePath = collectionHref.requireDavCollectionHrefForSharing(),
    depth = 0,
    contentType = DAV_SHARING_XML,
    body = (
        """<?xml version="1.0" encoding="UTF-8"?><d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" """ +
            """xmlns:cs="http://calendarserver.org/ns/"><d:prop><oc:invite/><cs:publish-url/><cs:allowed-sharing-modes/>""" +
            "</d:prop></d:propfind>"
        ).encodeToByteArray(),
    maximumResponseBytes = DAV_SHARING_RESPONSE_BYTES,
)

internal fun parseDavSharingState(response: NextcloudApiResponse): DavSharingState {
    require(response.status in 200..299) { "Sharing details could not be read (HTTP ${response.status})." }
    val xml = response.body.decodeToString()
    val okProperties = xml.xmlElements("propstat")
        .filter { it.xmlText("status")?.split(' ')?.getOrNull(1)?.toIntOrNull() in 200..299 }
        .joinToString("")
    val shares = okProperties.xmlElements("invite").flatMap { it.xmlElements("user") }.mapNotNull { user ->
        val href = user.xmlText("href")?.decodeXmlEntities()?.trim() ?: return@mapNotNull null
        val principal = href.removePrefix("principal:").trim('/').takeIf { href.startsWith("principal:") && it.isSafeDavPrincipal() }
            ?: return@mapNotNull null
        val access = user.xmlElements("access").firstOrNull().orEmpty()
        DavShare(
            sharee = DavSharee(
                principal = principal,
                displayName = user.xmlText("common-name")?.decodeXmlEntities()?.trim()?.takeIf(String::isNotBlank)
                    ?: principal.substringAfterLast('/').decodePercentEncoding(),
                kind = principal.davShareeKind(),
            ),
            writable = access.containsXmlElement("read-write"),
        )
    }.distinctBy { it.sharee.principal }
    val publishUrl = okProperties.xmlElements("publish-url").firstOrNull()?.xmlText("href")
        ?.decodeXmlEntities()?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    val canPublish = publishUrl != null || okProperties.xmlElements("allowed-sharing-modes").any { it.containsXmlElement("can-be-published") }
    return DavSharingState(shares, publishUrl, canPublish)
}

/** Adds or changes ([set]) and removes shares. The server accepts at most 10 entries per request. */
internal fun davShareUpdateRequest(
    collectionHref: String,
    set: List<DavShare> = emptyList(),
    remove: List<DavSharee> = emptyList(),
): GroupwareDavRequest {
    require(set.size + remove.size in 1..MAX_DAV_SHARE_CHANGES) { "A sharing change must contain 1 to 10 entries." }
    require((set.map { it.sharee } + remove).all { it.principal.isSafeDavPrincipal() }) { "The sharee is invalid." }
    val body = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?><oc:share xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">""")
        set.forEach { share ->
            append("<oc:set><d:href>").append(share.sharee.shareHref.escapeDavXml()).append("</d:href>")
            if (share.writable) append("<oc:read-write/>")
            append("</oc:set>")
        }
        remove.forEach { sharee ->
            append("<oc:remove><d:href>").append(sharee.shareHref.escapeDavXml()).append("</d:href></oc:remove>")
        }
        append("</oc:share>")
    }
    return davSharingPost(collectionHref, body)
}

/** Turns the calendar's public read-only link on or off. */
internal fun calendarPublishRequest(calendarHref: String, publish: Boolean): GroupwareDavRequest {
    val element = if (publish) "publish-calendar" else "unpublish-calendar"
    return davSharingPost(
        calendarHref,
        """<?xml version="1.0" encoding="UTF-8"?><cs:$element xmlns:cs="http://calendarserver.org/ns/"/>""",
    )
}

/**
 * Finds users and groups to share with by name or email (WebDAV ACL principal-property-search,
 * RFC 3744 section 9.4). Rooms and resources are left out: they cannot be share targets.
 */
internal fun davPrincipalSearchRequest(query: String): GroupwareDavRequest {
    val term = query.trim()
    require(term.length in MIN_PRINCIPAL_SEARCH_LENGTH..MAX_PRINCIPAL_SEARCH_LENGTH && term.none(Char::isISOControl)) {
        "The search is too short or too long."
    }
    val match = term.escapeDavXml()
    return GroupwareDavRequest(
        method = "REPORT",
        relativePath = "/remote.php/dav/principals/",
        depth = 0,
        contentType = DAV_SHARING_XML,
        body = (
            """<?xml version="1.0" encoding="UTF-8"?><d:principal-property-search xmlns:d="DAV:" """ +
                """xmlns:s="http://sabredav.org/ns" test="anyof">""" +
                "<d:property-search><d:prop><d:displayname/></d:prop><d:match>$match</d:match></d:property-search>" +
                "<d:property-search><d:prop><s:email-address/></d:prop><d:match>$match</d:match></d:property-search>" +
                "<d:prop><d:displayname/><s:email-address/></d:prop><d:apply-to-principal-collection-set/>" +
                "</d:principal-property-search>"
            ).encodeToByteArray(),
        maximumResponseBytes = DAV_SHARING_RESPONSE_BYTES,
    )
}

internal fun parseDavPrincipalSearch(response: NextcloudApiResponse, limit: Int = MAX_PRINCIPAL_RESULTS): List<DavSharee> {
    require(response.status in 200..299) { "Searching people failed (HTTP ${response.status})." }
    return response.body.decodeToString().xmlElements("response").mapNotNull { block ->
        val href = block.xmlDirectChildText("href")?.decodeXmlEntities()?.trim() ?: return@mapNotNull null
        val principal = href.removePrefix("/remote.php/dav/").trim('/')
        if (!href.startsWith("/remote.php/dav/principals/") || !principal.isSafeDavPrincipal()) return@mapNotNull null
        val kind = principal.davShareeKind()
        if (kind == DavShareeKind.Other) return@mapNotNull null
        DavSharee(
            principal = principal,
            displayName = block.xmlText("displayname")?.decodeXmlEntities()?.trim()?.takeIf(String::isNotBlank)
                ?: principal.substringAfterLast('/').decodePercentEncoding(),
            kind = kind,
        )
    }.distinctBy(DavSharee::principal).take(limit)
}

/** Whether a POST that changed sharing was handled; Sabre reports it with a 2xx status. */
internal fun davSharingResponseProvesRejection(status: Int): Boolean = groupwareMutationResponseProvesRejection(status)

private fun davSharingPost(collectionHref: String, body: String): GroupwareDavRequest = GroupwareDavRequest(
    method = "POST",
    relativePath = collectionHref.requireDavCollectionHrefForSharing(),
    contentType = DAV_SHARING_XML,
    body = body.encodeToByteArray(),
    maximumResponseBytes = DAV_SHARING_RESPONSE_BYTES,
)

private fun String.davShareeKind(): DavShareeKind = when {
    startsWith("principals/users/") -> DavShareeKind.User
    startsWith("principals/groups/") -> DavShareeKind.Group
    else -> DavShareeKind.Other
}

private fun String.isSafeDavPrincipal(): Boolean {
    val parts = split('/')
    return parts.size == 3 && parts[0] == "principals" && parts.all(String::isNotEmpty) && length <= 512 &&
        none { it.isISOControl() || it == '\\' || it == '?' || it == '#' || it == '"' || it == '<' || it == '>' } &&
        parts.none { it == "." || it == ".." }
}

private fun String.requireDavCollectionHrefForSharing(): String {
    requireSafeDavHref()
    require(endsWith('/') && (startsWith("/remote.php/dav/calendars/") || startsWith("/remote.php/dav/addressbooks/"))) {
        "Only a calendar or an address book can be shared."
    }
    return this
}

internal const val MAX_DAV_SHARE_CHANGES = 10
internal const val MIN_PRINCIPAL_SEARCH_LENGTH = 2
private const val MAX_PRINCIPAL_SEARCH_LENGTH = 100
private const val MAX_PRINCIPAL_RESULTS = 25
private const val DAV_SHARING_XML = "application/xml; charset=utf-8"
private const val DAV_SHARING_RESPONSE_BYTES = 512L * 1024L
