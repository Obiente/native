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
): List<String> {
    require(response.status in 200..299) { "Contact discovery failed (HTTP ${response.status})." }
    val collectionHref = addressBookHref.requireDavCollectionHref()
    return response.body.decodeToString().xmlElements("response").mapNotNull { block ->
        if (block.xmlText("getetag").isNullOrBlank()) return@mapNotNull null
        val href = block.xmlText("href")?.decodeXmlEntities()?.trim()?.requireSafeDavHref()
            ?: return@mapNotNull null
        href.takeIf { it.isDirectDavChildOf(collectionHref) }
    }.distinct()
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

suspend fun loadGroupwareContactsInBatches(
    addressBookHref: String,
    execute: suspend (GroupwareDavRequest) -> NextcloudApiResponse,
): List<GroupwareContact> {
    val objectHrefs = parseGroupwareAddressBookObjectHrefs(
        addressBookHref,
        execute(groupwareDavAddressBookObjectListingRequest(addressBookHref)),
    )
    return objectHrefs.chunked(MAX_DAV_MULTIGET_ITEMS).flatMap { batch ->
        val requested = batch.toSet()
        parseGroupwareContacts(
            addressBookHref,
            execute(groupwareDavAddressBookMultiGetRequest(addressBookHref, batch)),
        ).also { contacts ->
            require(contacts.all { contact -> contact.href in requested }) {
                "The CardDAV multiget response contained an unrequested object."
            }
        }
    }.distinctBy(GroupwareContact::href)
}

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

private val ADDRESS_BOOK_OBJECT_LISTING_BODY = """
    <?xml version="1.0" encoding="UTF-8"?>
    <d:propfind xmlns:d="DAV:">
      <d:prop><d:getetag /></d:prop>
    </d:propfind>
""".trimIndent()
