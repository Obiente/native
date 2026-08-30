package dev.obiente.nextcloudnative.app

fun groupwareDavAddressBookObjectListingRequest(addressBookHref: String): GroupwareDavRequest =
    GroupwareDavRequest(
        method = "PROPFIND",
        relativePath = addressBookHref.requireDavCollectionHref(),
        depth = 1,
        contentType = DAV_XML_CONTENT_TYPE,
        body = ADDRESS_BOOK_OBJECT_LISTING_BODY.encodeToByteArray(),
        maximumResponseBytes = DAV_OBJECT_LISTING_RESPONSE_BYTES,
    )

fun parseGroupwareAddressBookObjectHrefs(
    addressBookHref: String,
    response: NextcloudApiResponse,
): List<String> = parseGroupwareAddressBookObjects(addressBookHref, response).map(GroupwareAddressBookObject::href)

private data class GroupwareAddressBookObject(
    val href: String,
    val etag: String,
)

private fun parseGroupwareAddressBookObjects(
    addressBookHref: String,
    response: NextcloudApiResponse,
): List<GroupwareAddressBookObject> {
    require(response.status in 200..299) { "Contact discovery failed (HTTP ${response.status})." }
    val collectionHref = addressBookHref.requireDavCollectionHref()
    val objects = response.body.decodeToString().xmlElements("response").mapNotNull { block ->
        val href = block.xmlText("href")?.decodeXmlEntities()?.trim()?.requireSafeDavHref()
            ?: error("The CardDAV listing response omitted an object href.")
        if (href == collectionHref) return@mapNotNull null
        require(href.isDirectDavChildOf(collectionHref)) {
            "The CardDAV listing response contained an object outside its address book."
        }
        val successfulProperty = block.xmlElements("propstat").singleOrNull { property ->
            property.xmlElements("getetag").isNotEmpty() &&
                property.xmlText("status")?.davStatusCode() in 200..299
        } ?: error("The CardDAV listing response contained a failed or malformed object.")
        val etag = successfulProperty.xmlText("getetag")?.decodeXmlEntities()?.trim()?.takeIf(String::isNotBlank)
            ?: error("The CardDAV listing response omitted an object ETag.")
        GroupwareAddressBookObject(href, etag)
    }
    require(objects.map(GroupwareAddressBookObject::href).distinct().size == objects.size) {
        "The CardDAV listing response contained a duplicate object."
    }
    return objects
}

fun groupwareDavAddressBookMultiGetRequest(
    addressBookHref: String,
    objectHrefs: List<String>,
): GroupwareDavRequest {
    val collectionHref = addressBookHref.requireDavCollectionHref()
    require(objectHrefs.size in 1..MAX_DAV_MULTIGET_ITEMS) { "The CardDAV multiget batch is out of range." }
    val safeHrefs = objectHrefs.map { href ->
        href.requireSafeDavHref().also {
            require(it.isDirectDavChildOf(collectionHref)) { "The CardDAV object is outside its address book." }
        }
    }
    require(safeHrefs.distinct().size == safeHrefs.size) { "The CardDAV multiget batch contains duplicates." }
    val hrefElements = safeHrefs.joinToString("\n") { href -> "  <d:href>${href.escapeDavXml()}</d:href>" }
    val body = """
        <?xml version="1.0" encoding="UTF-8"?>
        <card:addressbook-multiget xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
          <d:prop><d:getetag /><card:address-data /></d:prop>
        $hrefElements
        </card:addressbook-multiget>
    """.trimIndent()
    return GroupwareDavRequest(
        method = "REPORT",
        relativePath = collectionHref,
        depth = 1,
        contentType = DAV_XML_CONTENT_TYPE,
        body = body.encodeToByteArray(),
        maximumResponseBytes = DAV_MULTIGET_RESPONSE_BYTES,
    )
}

internal suspend fun loadGroupwareContactsInBatches(
    addressBookHref: String,
    retentionBudget: GroupwareContactRetentionBudget = GroupwareContactRetentionBudget(),
    onConcurrentDeletion: (Int) -> Unit = {},
    execute: suspend (GroupwareDavRequest) -> NextcloudApiResponse,
): List<GroupwareContact> {
    val objects = parseGroupwareAddressBookObjects(
        addressBookHref,
        execute(groupwareDavAddressBookObjectListingRequest(addressBookHref)),
    )
    require(objects.size <= MAX_RETAINED_CONTACTS) {
        "The address book contains more contacts than can be held safely."
    }
    return buildList {
        objects.chunked(MAX_DAV_MULTIGET_ITEMS).forEach { batch ->
            val loaded = loadGroupwareContactBatch(addressBookHref, batch, execute)
            if (loaded.concurrentlyDeletedObjectCount > 0) {
                onConcurrentDeletion(loaded.concurrentlyDeletedObjectCount)
            }
            loaded.contacts.forEach { contact ->
                add(retentionBudget.retain(contact))
            }
        }
    }
}

internal suspend fun loadGroupwareContactForEditing(
    addressBookHref: String,
    objectHref: String,
    knownEtag: String? = null,
    execute: suspend (GroupwareDavRequest) -> NextcloudApiResponse,
): GroupwareContact {
    val response = execute(groupwareDavDetailRequest(objectHref))
    require(response.status in 200..299) { "Contact loading failed (HTTP ${response.status})." }
    return requireNotNull(
        parseGroupwareContact(
            addressBookHref = addressBookHref,
            href = objectHref,
            etag = response.etag ?: knownEtag,
            content = response.body.decodeToString(),
        ),
    ) { "The selected contact is malformed." }
}

private data class GroupwareContactBatchLoadResult(
    val contacts: List<GroupwareContact>,
    val concurrentlyDeletedObjectCount: Int = 0,
) {
    operator fun plus(other: GroupwareContactBatchLoadResult): GroupwareContactBatchLoadResult =
        GroupwareContactBatchLoadResult(
            contacts = contacts + other.contacts,
            concurrentlyDeletedObjectCount = concurrentlyDeletedObjectCount + other.concurrentlyDeletedObjectCount,
        )
}

private suspend fun loadGroupwareContactBatch(
    addressBookHref: String,
    objects: List<GroupwareAddressBookObject>,
    execute: suspend (GroupwareDavRequest) -> NextcloudApiResponse,
): GroupwareContactBatchLoadResult {
    val response = try {
        execute(
            groupwareDavAddressBookMultiGetRequest(
                addressBookHref,
                objects.map(GroupwareAddressBookObject::href),
            ),
        )
    } catch (failure: NextcloudResponseTooLargeException) {
        if (failure.responseStatus?.let { it in 200..299 } != true) throw failure
        return loadGroupwareContactBatchWithoutOversizedReport(addressBookHref, objects, execute)
    }
    if (response.status in 200..299) {
        return parseGroupwareAddressBookMultiGetResponse(
            addressBookHref = addressBookHref,
            requestedHrefs = objects.map(GroupwareAddressBookObject::href),
            response = response,
        )
    }
    if (response.status in setOf(405, 501)) {
        return loadGroupwareContactsIndividually(addressBookHref, objects, execute)
    }
    error("Contact loading failed (HTTP ${response.status}).")
}

private suspend fun loadGroupwareContactBatchWithoutOversizedReport(
    addressBookHref: String,
    objects: List<GroupwareAddressBookObject>,
    execute: suspend (GroupwareDavRequest) -> NextcloudApiResponse,
): GroupwareContactBatchLoadResult {
    if (objects.size == 1) {
        return loadGroupwareContactsIndividually(addressBookHref, objects, execute)
    }
    val midpoint = objects.size / 2
    return loadGroupwareContactBatch(addressBookHref, objects.take(midpoint), execute) +
        loadGroupwareContactBatch(addressBookHref, objects.drop(midpoint), execute)
}

private suspend fun loadGroupwareContactsIndividually(
    addressBookHref: String,
    objects: List<GroupwareAddressBookObject>,
    execute: suspend (GroupwareDavRequest) -> NextcloudApiResponse,
): GroupwareContactBatchLoadResult {
    var concurrentlyDeletedObjectCount = 0
    val contacts = objects.mapNotNull { objectMetadata ->
        val response = execute(groupwareDavDetailRequest(objectMetadata.href))
        if (response.status == 404 || response.status == 410) {
            concurrentlyDeletedObjectCount += 1
            return@mapNotNull null
        }
        require(response.status in 200..299) { "Contact loading failed (HTTP ${response.status})." }
        requireNotNull(
            parseGroupwareContact(
                addressBookHref = addressBookHref,
                href = objectMetadata.href,
                etag = response.etag ?: objectMetadata.etag,
                content = response.body.decodeToString(),
            ),
        ) { "The selected contact is malformed." }
    }
    return GroupwareContactBatchLoadResult(contacts, concurrentlyDeletedObjectCount)
}

private fun parseGroupwareAddressBookMultiGetResponse(
    addressBookHref: String,
    requestedHrefs: List<String>,
    response: NextcloudApiResponse,
): GroupwareContactBatchLoadResult {
    require(response.status in 200..299) { "Contact loading failed (HTTP ${response.status})." }
    val requested = requestedHrefs.toSet()
    require(requested.size == requestedHrefs.size)
    val returned = mutableSetOf<String>()
    var concurrentlyDeletedObjectCount = 0
    val contacts = response.body.decodeToString().xmlElements("response").mapNotNull { block ->
        val href = block.xmlText("href")?.decodeXmlEntities()?.trim()?.requireSafeDavHref()
            ?: error("The CardDAV multiget response omitted an object href.")
        require(href in requested && returned.add(href)) {
            "The CardDAV multiget response contained an unrequested or duplicate object."
        }
        val properties = block.xmlElements("propstat")
        // A property's 404 does not prove that the resource was deleted.
        val resourceStatuses = properties.fold(block) { remainder, property -> remainder.replace(property, "") }
            .xmlElements("status")
        require(resourceStatuses.size <= 1 && (resourceStatuses.isEmpty() || properties.isEmpty())) {
            "The CardDAV multiget response contained conflicting object statuses."
        }
        resourceStatuses.singleOrNull()?.let { statusElement ->
            val status = requireNotNull(statusElement.xmlText("status")?.davStatusCode()) {
                "The CardDAV multiget response contained a malformed object status."
            }
            if (status == 404 || status == 410) {
                concurrentlyDeletedObjectCount += 1
                return@mapNotNull null
            }
            require(status in 200..299) { "The CardDAV multiget response contained a failed object." }
        }
        val successfulProperty = properties.singleOrNull { property ->
            property.xmlElements("address-data").isNotEmpty() &&
                property.xmlText("status")?.davStatusCode() in 200..299
        } ?: error("The CardDAV multiget response did not return a contact successfully.")
        val etag = successfulProperty.xmlText("getetag")?.decodeXmlEntities()?.trim()?.takeIf(String::isNotBlank)
            ?: error("The CardDAV multiget response omitted an object ETag.")
        val content = successfulProperty.xmlText("address-data")?.decodeXmlEntities()
            ?: error("The CardDAV multiget response omitted contact data.")
        requireNotNull(
            parseGroupwareContact(
                addressBookHref = addressBookHref,
                href = href,
                etag = etag,
                content = content,
            ),
        ) { "The CardDAV multiget response contained a malformed contact." }
    }
    require(returned == requested) {
        "The CardDAV multiget response did not account for every requested contact."
    }
    return GroupwareContactBatchLoadResult(contacts, concurrentlyDeletedObjectCount)
}

internal class GroupwareContactRetentionBudget(
    private val maximumContacts: Int = MAX_RETAINED_CONTACTS,
    private val maximumEstimatedBytes: Long = MAX_RETAINED_CONTACT_BYTES,
) {
    private var retainedContacts = 0
    private var retainedBytes = 0L

    init {
        require(maximumContacts > 0 && maximumEstimatedBytes > 0L)
    }

    fun retain(contact: GroupwareContact): GroupwareContact {
        val summary = contact.copy(rawVCard = "")
        val nextCount = retainedContacts + 1
        val nextBytes = retainedBytes + summary.estimatedRetainedBytes()
        require(nextCount <= maximumContacts && nextBytes <= maximumEstimatedBytes) {
            "The address book contains more contact data than can be held safely."
        }
        retainedContacts = nextCount
        retainedBytes = nextBytes
        return summary
    }
}

private fun GroupwareContact.estimatedRetainedBytes(): Long =
    CONTACT_SUMMARY_FIXED_BYTES + listOfNotNull(
        href,
        etag,
        addressBookHref,
        uid,
        displayName,
        organization,
        address,
        birthday,
        notes,
    ).sumOf(String::estimatedRetainedBytes) +
        emails.sumOf(String::estimatedRetainedBytes) +
        phones.sumOf(String::estimatedRetainedBytes)

private fun String.estimatedRetainedBytes(): Long = STRING_FIXED_BYTES + length.toLong() * BYTES_PER_CHARACTER

private fun String.davStatusCode(): Int? = trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()

private fun String.requireDavCollectionHref(): String = requireSafeDavHref().also {
    require(it.endsWith('/')) { "The DAV collection href is invalid." }
}

private fun String.isDirectDavChildOf(collectionHref: String): Boolean {
    if (!startsWith(collectionHref) || endsWith('/')) return false
    val childName = removePrefix(collectionHref)
    return childName.isNotBlank() && '/' !in childName
}

private const val DAV_XML_CONTENT_TYPE = "application/xml; charset=utf-8"
private const val DAV_OBJECT_LISTING_RESPONSE_BYTES = 16L * 1024L * 1024L
private const val DAV_MULTIGET_RESPONSE_BYTES = 16L * 1024L * 1024L
private const val MAX_DAV_MULTIGET_ITEMS = 10
private const val MAX_RETAINED_CONTACTS = 10_000
private const val MAX_RETAINED_CONTACT_BYTES = 8L * 1024L * 1024L
private const val CONTACT_SUMMARY_FIXED_BYTES = 256L
private const val STRING_FIXED_BYTES = 32L
private const val BYTES_PER_CHARACTER = 2L

private val ADDRESS_BOOK_OBJECT_LISTING_BODY = """
    <?xml version="1.0" encoding="UTF-8"?>
    <d:propfind xmlns:d="DAV:">
      <d:prop><d:getetag /></d:prop>
    </d:propfind>
""".trimIndent()
