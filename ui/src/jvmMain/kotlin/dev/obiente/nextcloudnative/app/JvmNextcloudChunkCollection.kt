package dev.obiente.nextcloudnative.app

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

fun parseJvmNextcloudChunkCollection(bytes: ByteArray): Map<Int, Long> {
    require(bytes.isNotEmpty())
    return ByteArrayInputStream(bytes).readNextcloudChunkCollection()
}

/**
 * Streams a chunk collection without imposing an aggregate response-size limit.
 *
 * Nextcloud's chunking-v2 protocol already limits a collection to 10,000 numbered chunks. The
 * parser enforces that structural limit and bounds the only individual text fields it retains, so
 * verbose DAV XML or a long server URL cannot turn into either a file-size limit or an unbounded
 * in-memory document.
 */
fun InputStream.readNextcloudChunkCollection(
    maximumResponseBytes: Long = MAX_NEXTCLOUD_CHUNK_COLLECTION_METADATA_BYTES,
): Map<Int, Long> {
    require(maximumResponseBytes > 0L)
    val factory = SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }
    val handler = NextcloudChunkCollectionHandler()
    val reader = factory.newSAXParser().xmlReader.apply {
        entityResolver = org.xml.sax.EntityResolver { _, _ ->
            throw SAXException("External XML entities are not allowed in a chunk collection.")
        }
        contentHandler = handler
        errorHandler = handler
    }
    reader.parse(InputSource(JvmBoundedChunkCollectionInputStream(this, maximumResponseBytes)).apply {
        encoding = StandardCharsets.UTF_8.name()
    })
    return handler.chunks
}

private class JvmBoundedChunkCollectionInputStream(
    private val source: InputStream,
    private val maximumBytes: Long,
) : InputStream() {
    private var observedBytes = 0L

    override fun read(): Int = source.read().also { value ->
        if (value >= 0) addObservedBytes(1)
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
        source.read(bytes, offset, length).also { count ->
            if (count > 0) addObservedBytes(count)
        }

    override fun close() = source.close()

    private fun addObservedBytes(count: Int) {
        observedBytes = Math.addExact(observedBytes, count.toLong())
        require(observedBytes <= maximumBytes) {
            "The chunk collection metadata response exceeds its protocol safety budget."
        }
    }
}

private class NextcloudChunkCollectionHandler : DefaultHandler() {
    val chunks = linkedMapOf<Int, Long>()
    private var responseCount = 0
    private var inResponse = false
    private var capturedField: CapturedField? = null
    private var capturedText = StringBuilder()
    private var href: String? = null
    private var contentLength: String? = null

    override fun startElement(uri: String, localName: String, qName: String, attributes: org.xml.sax.Attributes) {
        if (uri == DAV_NAMESPACE && localName == "response") {
            require(!inResponse) { "Nested DAV responses are invalid." }
            responseCount += 1
            require(responseCount <= MAX_NEXTCLOUD_UPLOAD_CHUNKS + 1) {
                "The chunk collection contains more records than the protocol allows."
            }
            inResponse = true
            href = null
            contentLength = null
            return
        }
        if (!inResponse || uri != DAV_NAMESPACE) return
        capturedField = when (localName) {
            "href" -> CapturedField.Href
            "getcontentlength" -> CapturedField.ContentLength
            else -> null
        }
        if (capturedField != null) capturedText = StringBuilder()
    }

    override fun characters(chars: CharArray, start: Int, length: Int) {
        val maximum = when (capturedField) {
            CapturedField.Href -> MAX_CHUNK_HREF_CHARACTERS
            CapturedField.ContentLength -> MAX_CHUNK_LENGTH_CHARACTERS
            null -> return
        }
        require(capturedText.length + length <= maximum) {
            "A chunk collection metadata field is too long."
        }
        capturedText.append(chars, start, length)
    }

    override fun endElement(uri: String, localName: String, qName: String) {
        if (inResponse && uri == DAV_NAMESPACE) {
            when {
                localName == "href" && capturedField == CapturedField.Href -> href = capturedText.toString().trim()
                localName == "getcontentlength" && capturedField == CapturedField.ContentLength ->
                    contentLength = capturedText.toString().trim()
                localName == "response" -> {
                    retainChunk()
                    inResponse = false
                }
            }
        }
        capturedField = null
    }

    private fun retainChunk() {
        val name = href?.let {
            URLDecoder.decode(it.replace("+", "%2B"), StandardCharsets.UTF_8)
                .trimEnd('/').substringAfterLast('/')
        } ?: return
        if (!name.matches(CHUNK_NAME)) return
        val number = name.toInt()
        require(number in 1..MAX_NEXTCLOUD_UPLOAD_CHUNKS)
        val size = requireNotNull(contentLength?.toLongOrNull())
        require(size > 0L && chunks.put(number, size) == null)
    }

    private enum class CapturedField {
        Href,
        ContentLength,
    }
}

private const val DAV_NAMESPACE = "DAV:"
private const val MAX_CHUNK_HREF_CHARACTERS = 64 * 1024
private const val MAX_CHUNK_LENGTH_CHARACTERS = 32
private const val MAX_NEXTCLOUD_CHUNK_COLLECTION_METADATA_BYTES = 256L * 1024L * 1024L
private val CHUNK_NAME = Regex("^[0-9]{1,5}$")
