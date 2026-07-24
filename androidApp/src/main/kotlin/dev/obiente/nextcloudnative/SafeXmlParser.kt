package dev.obiente.nextcloudnative

import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import org.w3c.dom.Document
import org.xml.sax.InputSource

/**
 * Parses server XML without relying on every Android XML provider supporting the
 * same optional hardening features.
 *
 * Android's bundled parser does not support disallow-doctype-decl on every API
 * level. We still reject document type declarations before parsing, disable
 * entity expansion, and install an entity resolver which never accesses an
 * external resource. Supported provider hardening flags are enabled as an
 * additional layer.
 */
internal object SafeXmlParser {
    fun parse(xml: ByteArray): Document {
        require(!containsDocumentType(xml)) {
            "Nextcloud returned XML containing a prohibited document type."
        }

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            trySetXIncludeAware(false)
            trySetFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            trySetFeature(DISALLOW_DOCTYPE, true)
            trySetFeature(EXTERNAL_GENERAL_ENTITIES, false)
            trySetFeature(EXTERNAL_PARAMETER_ENTITIES, false)
            trySetFeature(LOAD_EXTERNAL_DTD, false)
            trySetAttribute(ACCESS_EXTERNAL_DTD, "")
            trySetAttribute(ACCESS_EXTERNAL_SCHEMA, "")
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
        return builder.parse(ByteArrayInputStream(xml))
    }

    /**
     * XML markup keywords are ASCII in UTF-8, UTF-16, and UTF-32. Removing NUL
     * bytes lets the same check cover all of those encodings before a provider
     * gets a chance to resolve an entity.
     */
    internal fun containsDocumentType(xml: ByteArray): Boolean {
        val asciiMarkup = buildString(xml.size) {
            xml.forEach { byte ->
                val value = byte.toInt() and 0xff
                if (value != 0) append(value.toChar())
            }
        }
        return asciiMarkup.contains("<!DOCTYPE", ignoreCase = true)
    }

    private fun DocumentBuilderFactory.trySetFeature(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: ParserConfigurationException) {
            // Optional provider feature. The pre-parse guard and entity resolver remain active.
        } catch (_: AbstractMethodError) {
            // Older Android providers may not implement a feature exposed by the API surface.
        }
    }

    private fun DocumentBuilderFactory.trySetAttribute(name: String, value: String) {
        try {
            setAttribute(name, value)
        } catch (_: IllegalArgumentException) {
            // Optional JAXP property which is unavailable on some Android providers.
        }
    }

    private fun DocumentBuilderFactory.trySetXIncludeAware(value: Boolean) {
        try {
            isXIncludeAware = value
        } catch (_: UnsupportedOperationException) {
            // XInclude is not implemented by every Android provider.
        } catch (_: AbstractMethodError) {
            // Older Android providers may not implement this API.
        }
    }

    private const val DISALLOW_DOCTYPE =
        "http://apache.org/xml/features/disallow-doctype-decl"
    private const val EXTERNAL_GENERAL_ENTITIES =
        "http://xml.org/sax/features/external-general-entities"
    private const val EXTERNAL_PARAMETER_ENTITIES =
        "http://xml.org/sax/features/external-parameter-entities"
    private const val LOAD_EXTERNAL_DTD =
        "http://apache.org/xml/features/nonvalidating/load-external-dtd"
    // These JAXP properties are not exposed as XMLConstants fields by Android's API stubs.
    private const val ACCESS_EXTERNAL_DTD =
        "http://javax.xml.XMLConstants/property/accessExternalDTD"
    private const val ACCESS_EXTERNAL_SCHEMA =
        "http://javax.xml.XMLConstants/property/accessExternalSchema"
}
