package dev.obiente.nextcloudnative.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

fun parseJvmNextcloudChunkCollection(bytes: ByteArray): Map<Int, Long> {
    require(bytes.isNotEmpty() && bytes.size <= MAX_NEXTCLOUD_CHUNK_COLLECTION_RESPONSE_BYTES)
    require(!bytes.toString(StandardCharsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true))
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        isExpandEntityReferences = false
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }
    val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    val responses = document.getElementsByTagNameNS(DAV_NAMESPACE, "response")
    require(responses.length <= MAX_NEXTCLOUD_UPLOAD_CHUNKS + 1)
    val chunks = linkedMapOf<Int, Long>()
    for (index in 0 until responses.length) {
        val response = responses.item(index)
        val href = response.childNodes.firstText(DAV_NAMESPACE, "href") ?: continue
        val name = URLDecoder.decode(href.replace("+", "%2B"), StandardCharsets.UTF_8)
            .trimEnd('/').substringAfterLast('/')
        if (!name.matches(CHUNK_NAME)) continue
        val number = name.toInt()
        require(number in 1..MAX_NEXTCLOUD_UPLOAD_CHUNKS)
        val size = requireNotNull(response.childNodes.firstText(DAV_NAMESPACE, "getcontentlength")?.toLongOrNull())
        require(size > 0L && chunks.put(number, size) == null)
    }
    return chunks
}

fun InputStream.readBoundedNextcloudChunkCollection(): Map<Int, Long> {
    val output = ByteArrayOutputStream()
    copyBoundedNetworkResponseTo(
        output = output,
        maxBytes = MAX_NEXTCLOUD_CHUNK_COLLECTION_RESPONSE_BYTES.toLong(),
        onLimitExceeded = { error("The chunk collection response exceeds its in-memory safety budget.") },
        onNetworkReadFailure = {},
    )
    return parseJvmNextcloudChunkCollection(output.toByteArray())
}

private fun org.w3c.dom.NodeList.firstText(namespace: String, localName: String): String? {
    for (index in 0 until length) {
        val node = item(index)
        if (node.namespaceURI == namespace && node.localName == localName) return node.textContent.trim()
        node.childNodes.firstText(namespace, localName)?.let { return it }
    }
    return null
}

const val MAX_NEXTCLOUD_CHUNK_COLLECTION_RESPONSE_BYTES = 4 * 1024 * 1024
private const val DAV_NAMESPACE = "DAV:"
private val CHUNK_NAME = Regex("^[0-9]{1,5}$")
