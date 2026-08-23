package dev.obiente.nextcloudnative

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidIncomingShareManifestTest {
    @Test
    fun exportedShareTargetAcceptsSingleAndMultipleFiles() {
        val manifest = parseXml(androidMainSourceDirectory().resolve("AndroidManifest.xml"))
        val activities = manifest.getElementsByTagName("activity")
        val shareActivity = (0 until activities.length)
            .map(activities::item)
            .firstOrNull { activity ->
                activity.attributes.getNamedItemNS(ANDROID_XML_NAMESPACE, "name")?.nodeValue ==
                    ".AndroidShareUploadActivity"
            }
        assertNotNull(shareActivity)
        assertEquals("true", shareActivity.attributes.getNamedItemNS(ANDROID_XML_NAMESPACE, "exported").nodeValue)
        assertEquals("", shareActivity.attributes.getNamedItemNS(ANDROID_XML_NAMESPACE, "taskAffinity").nodeValue)
        val actions = shareActivity.childNodes.let { children ->
            buildList {
                for (childIndex in 0 until children.length) {
                    val filter = children.item(childIndex).takeIf { it.nodeName == "intent-filter" } ?: continue
                    val filterChildren = filter.childNodes
                    for (filterIndex in 0 until filterChildren.length) {
                        val action = filterChildren.item(filterIndex).takeIf { it.nodeName == "action" }
                            ?: continue
                        action.attributes.getNamedItemNS(ANDROID_XML_NAMESPACE, "name")?.nodeValue?.let(::add)
                    }
                }
            }
        }
        assertEquals(
            setOf("android.intent.action.SEND", "android.intent.action.SEND_MULTIPLE"),
            actions.toSet(),
        )
    }

    private fun androidMainSourceDirectory(): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(workingDirectory.resolve("src/main"), workingDirectory.resolve("androidApp/src/main"))
            .firstOrNull { it.resolve("AndroidManifest.xml").isFile }
            ?: error("Could not locate the Android main source directory.")
    }

    private fun parseXml(file: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(file).also { document ->
        assertTrue(document.documentElement != null)
    }

    private companion object {
        const val ANDROID_XML_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
