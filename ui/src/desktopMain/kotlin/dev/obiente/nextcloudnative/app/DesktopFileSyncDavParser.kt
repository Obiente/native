package dev.obiente.nextcloudnative.app

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

internal fun parseDesktopSyncDav(
    bytes: ByteArray,
    userId: String,
    maximumDocuments: Int = MAX_PARSED_DAV_DOCUMENTS,
): List<DesktopRemoteSyncDocument> =
    parseDesktopSyncDav(ByteArrayInputStream(bytes), userId, bytes.size.toLong(), maximumDocuments)

internal fun parseDesktopSyncDav(
    input: InputStream,
    userId: String,
    maximumBytes: Long,
    maximumDocuments: Int,
): List<DesktopRemoteSyncDocument> {
    require(maximumBytes > 0L && maximumDocuments > 0)
    val factory = XMLInputFactory.newFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty("javax.xml.stream.isSupportingExternalEntities", false)
        setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
        setProperty(XMLInputFactory.IS_COALESCING, false)
    }
    val reader = factory.createXMLStreamReader(
        GuardedXmlInputStream(BoundedInputStream(input, maximumBytes)),
        StandardCharsets.UTF_8.name(),
    )
    val documents = ArrayList<DesktopRemoteSyncDocument>()
    var responseCount = 0
    var response: DesktopDavResponseBuilder? = null
    var textField: String? = null
    val text = StringBuilder()
    try {
        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    if (reader.namespaceURI == DAV_NAMESPACE) {
                        when (reader.localName) {
                            "response" -> {
                                require(responseCount < maximumDocuments) {
                                    "A Nextcloud folder contains too many entries."
                                }
                                responseCount += 1
                                response = DesktopDavResponseBuilder()
                            }
                            "href", "getetag", "getcontentlength", "getlastmodified" -> if (response != null) {
                                textField = reader.localName
                                text.clear()
                            }
                            "collection" -> response?.isDirectory = true
                        }
                    } else if (reader.namespaceURI == OWNCLOUD_NAMESPACE && reader.localName == "permissions") {
                        if (response != null) {
                            textField = reader.localName
                            text.clear()
                        }
                    }
                }
                XMLStreamConstants.CHARACTERS,
                XMLStreamConstants.CDATA,
                -> if (textField != null) {
                    val eventLength = reader.textLength
                    require(eventLength <= MAX_DAV_PROPERTY_CHARS - text.length) {
                        "A DAV property is too large."
                    }
                    text.append(reader.textCharacters, reader.textStart, eventLength)
                }
                XMLStreamConstants.END_ELEMENT -> {
                    if (
                        reader.localName == textField &&
                        (reader.namespaceURI == DAV_NAMESPACE || reader.namespaceURI == OWNCLOUD_NAMESPACE)
                    ) {
                        response?.set(reader.localName, text.toString())
                        textField = null
                        text.clear()
                    }
                    if (reader.namespaceURI == DAV_NAMESPACE && reader.localName == "response") {
                        response?.toDocument(userId)?.let { document ->
                            require(documents.size < maximumDocuments) {
                                "A Nextcloud folder contains too many entries."
                            }
                            documents.add(document)
                        }
                        response = null
                    }
                }
            }
        }
    } finally {
        reader.close()
    }
    return documents
}

private class DesktopDavResponseBuilder {
    private var href: String? = null
    private var etag: String? = null
    private var contentLength: String? = null
    private var lastModified: String? = null
    private var permissions: String? = null
    var isDirectory: Boolean = false

    fun set(name: String, value: String) {
        when (name) {
            "href" -> href = value
            "getetag" -> etag = value
            "getcontentlength" -> contentLength = value
            "getlastmodified" -> lastModified = value
            "permissions" -> permissions = value
        }
    }

    fun toDocument(userId: String): DesktopRemoteSyncDocument? {
        val encodedHref = href ?: return null
        val decoded = URLDecoder.decode(encodedHref.replace("+", "%2B"), StandardCharsets.UTF_8)
        val path = decoded.substringAfter("/files/$userId/", "").trim('/')
        if (path.isBlank()) return null
        val revision = etag?.takeIf(String::isNotBlank) ?: error("A server item has no usable revision.")
        val modifiedEpochMillis = lastModified?.let(::parseDesktopSyncDavTimestamp)
        return DesktopRemoteSyncDocument(
            RemoteSyncEntry(
                relativePath = path,
                kind = if (isDirectory) SyncEntryKind.Directory else SyncEntryKind.File,
                etag = revision,
                size = contentLength?.toLongOrNull()?.takeIf { !isDirectory },
                modifiedEpochMillis = modifiedEpochMillis,
            ),
            isDirectory,
            lastModifiedEpochMillis = modifiedEpochMillis,
            permissions = permissions?.trim()?.takeIf(String::isNotEmpty),
        )
    }
}

internal fun parseDesktopSyncDavTimestamp(value: String): Long? = runCatching {
    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        .takeIf { it >= 0L }
}.getOrNull()

private const val MAX_DAV_PROPERTY_CHARS = 16_384
private const val MAX_PARSED_DAV_DOCUMENTS = 50_032
private const val DAV_NAMESPACE = "DAV:"
private const val OWNCLOUD_NAMESPACE = "http://owncloud.org/ns"
