package dev.obiente.nextcloudnative.app

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

internal fun handleDesktopFileVersionRestoreStatus(status: Int, onRestored: () -> Unit) {
    when (status) {
        in 200..299 -> onRestored()
        403 -> error("You do not have permission to restore this file version.")
        404 -> error("This historical version no longer exists.")
        409 -> error("The server could not restore this version to the current file.")
        else -> error("Restoring the file version failed (HTTP $status).")
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
