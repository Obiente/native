package dev.obiente.nextcloudnative

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidNetworkSecurityConfigTest {
    @Test
    fun manifestUsesAnAppWideSystemAndUserCertificatePolicy() {
        val sourceDirectory = androidMainSourceDirectory()
        val manifest = parseXml(sourceDirectory.resolve("AndroidManifest.xml"))
        val application = manifest.getElementsByTagName("application").item(0)
        assertEquals(
            "@xml/network_security_config",
            application.attributes.getNamedItemNS(ANDROID_XML_NAMESPACE, "networkSecurityConfig").nodeValue,
        )

        val configuration = parseXml(sourceDirectory.resolve("res/xml/network_security_config.xml"))
        val baseConfig = configuration.getElementsByTagName("base-config")
        assertEquals(1, baseConfig.length)
        assertEquals(
            "true",
            baseConfig.item(0).attributes.getNamedItem("cleartextTrafficPermitted").nodeValue,
        )
        assertEquals(0, configuration.getElementsByTagName("domain-config").length)
        assertEquals(0, configuration.getElementsByTagName("debug-overrides").length)
        val certificateSources = configuration.getElementsByTagName("certificates")
            .let { nodes ->
                (0 until nodes.length).map { index ->
                    nodes.item(index).attributes.getNamedItem("src").nodeValue
                }
            }
        assertEquals(listOf("system", "user"), certificateSources)
    }

    private fun androidMainSourceDirectory(): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val candidates = listOf(
            workingDirectory.resolve("src/main"),
            workingDirectory.resolve("androidApp/src/main"),
        )
        return candidates.firstOrNull { it.resolve("AndroidManifest.xml").isFile }
            ?: error("Could not locate the Android main source directory.")
    }

    private fun parseXml(file: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(file).also { document ->
        assertTrue(document.documentElement != null, "${file.name} must contain a root element.")
    }

    private companion object {
        const val ANDROID_XML_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
