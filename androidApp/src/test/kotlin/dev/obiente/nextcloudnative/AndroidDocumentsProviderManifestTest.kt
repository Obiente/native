package dev.obiente.nextcloudnative

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AndroidDocumentsProviderManifestTest {
    @Test
    fun `documents provider stays in the application process for thread confined recovery`() {
        val manifest = parseXml(androidMainSourceDirectory().resolve("AndroidManifest.xml"))
        val providers = manifest.getElementsByTagName("provider")
        val documentsProvider = (0 until providers.length)
            .map(providers::item)
            .firstOrNull { provider ->
                provider.attributes.getNamedItemNS(ANDROID_XML_NAMESPACE, "name")?.nodeValue ==
                    ".NextcloudDocumentsProvider"
            }

        assertNotNull(documentsProvider)
        assertNull(documentsProvider.attributes.getNamedItemNS(ANDROID_XML_NAMESPACE, "process"))
    }

    private fun androidMainSourceDirectory(): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(workingDirectory.resolve("src/main"), workingDirectory.resolve("androidApp/src/main"))
            .firstOrNull { candidate -> candidate.resolve("AndroidManifest.xml").isFile }
            ?: error("Could not locate the Android main source directory.")
    }

    private fun parseXml(file: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(file)

    private companion object {
        const val ANDROID_XML_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
