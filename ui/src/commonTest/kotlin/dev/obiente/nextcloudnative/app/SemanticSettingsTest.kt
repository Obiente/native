package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.runtime.boardRoutes
import dev.obiente.nextcloudnative.nativeui.runtime.forApp
import dev.obiente.nextcloudnative.nativeui.runtime.notesRoutes
import dev.obiente.nextcloudnative.nativeui.runtime.resolveSemanticBoardContract
import dev.obiente.nextcloudnative.nativeui.runtime.resolveSemanticNotesContract
import dev.obiente.nextcloudnative.nativeui.runtime.semanticDescriptor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemanticSettingsTest {
    @Test
    fun `keyed settings write exactly the key the server returned`() {
        val settings = boardSettings()
        val entries = settings.parse(response("""{"ocs":{"meta":{"statuscode":200},"data":{"calendar":true,"groupLimit":[]}}}"""))

        val calendar = entries.single()
        assertEquals(SemanticSettingKind.Toggle, calendar.kind)
        assertTrue(calendar.writable)
        val request = settings.writeRequest(calendar, "false", entries)
        assertEquals("/apps/planner/api/v2/config/calendar", request.relativePath)
        assertEquals("false", Json.parseToJsonElement(requireNotNull(request.body).decodeToString()).jsonObject["value"]?.let {
            (it as JsonPrimitive).content
        })
    }

    @Test
    fun `a settings document replacement keeps every other declared value`() {
        val settings = notesSettings()
        val entries = settings.parse(response("""{"notesPath":"Notes","fileSuffix":".md","noteMode":"edit"}"""))

        val path = entries.single { it.key == "notesPath" }
        assertEquals(SemanticSettingKind.Folder, path.kind)
        assertFalse(entries.single { it.key == "noteMode" }.writable)
        val body = Json.parseToJsonElement(
            requireNotNull(settings.writeRequest(path, "Journal", entries).body).decodeToString(),
        ).jsonObject
        assertEquals(setOf("notesPath", "fileSuffix"), body.keys)
        assertEquals(JsonPrimitive(".md"), body["fileSuffix"])
        assertFailsWith<IllegalArgumentException> {
            settings.writeRequest(entries.single { it.key == "noteMode" }, "x", entries)
        }
    }

    @Test
    fun `a replacement stays read-only when a declared setting cannot be sent back unchanged`() {
        val settings = notesSettings()
        for (document in listOf(
            """{"notesPath":"Notes","fileSuffix":null}""",
            """{"notesPath":"Notes","fileSuffix":[".md"]}""",
            """{"notesPath":"Notes"}""",
        )) {
            val entries = settings.parse(response(document))
            val path = entries.single { it.key == "notesPath" }
            assertFalse(path.writable, document)
            assertFailsWith<IllegalArgumentException>(document) { settings.writeRequest(path, "Journal", entries) }
        }
    }

    @Test
    fun `a replacement is not sent when another setting changed since the dialog loaded`() = runBlocking<Unit> {
        var reads = 0
        var writes = 0
        val controller = SemanticSettingsController(
            execute = { request ->
                if (request.method == NextcloudApiMethod.GET) {
                    reads += 1
                    response(if (reads == 1) """{"notesPath":"Notes","fileSuffix":".md"}""" else """{"notesPath":"Notes","fileSuffix":".txt"}""")
                } else {
                    writes += 1
                    response("{}")
                }
            },
            settings = notesSettings(),
        )
        controller.load()
        controller.change(controller.state.entries.single { it.key == "notesPath" }, "Journal")

        assertEquals(0, writes, "The stale suffix is not written over the change made elsewhere.")
        assertEquals(".txt", controller.state.entries.single { it.key == "fileSuffix" }.value)
        assertTrue(controller.state.errorMessage.orEmpty().contains("changed elsewhere"))
        assertTrue(controller.state.canChange)
    }

    @Test
    fun `a replacement that changes another setting is reported and pauses further changes`() = runBlocking<Unit> {
        var written = false
        val changed = mutableListOf<String>()
        val controller = SemanticSettingsController(
            execute = { request ->
                if (request.method == NextcloudApiMethod.GET) {
                    response(if (written) """{"notesPath":"Journal","fileSuffix":".txt"}""" else """{"notesPath":"Notes","fileSuffix":".md"}""")
                } else {
                    written = true
                    response("{}")
                }
            },
            settings = notesSettings(),
            onChanged = { changed += it },
        )
        controller.load()
        controller.change(controller.state.entries.single { it.key == "notesPath" }, "Journal")

        assertNull(controller.state.notice, "A write that reset another setting is not reported as saved.")
        assertTrue(controller.state.errorMessage.orEmpty().contains("also changed"), controller.state.errorMessage)
        assertEquals(".txt", controller.state.entries.single { it.key == "fileSuffix" }.value)
        assertFalse(controller.state.canChange)
        controller.load()
        assertFalse(controller.state.canChange, "Reloading does not make the route trustworthy again.")
    }

    @Test
    fun `the controller reports a value the server did not keep`() = runBlocking<Unit> {
        val controller = SemanticSettingsController(
            execute = { request ->
                if (request.method == NextcloudApiMethod.GET) response("""{"notesPath":"Notes","fileSuffix":".md"}""")
                else response("{}")
            },
            settings = notesSettings(),
        )
        controller.load()
        controller.change(controller.state.entries.single { it.key == "fileSuffix" }, ".txt")

        assertEquals("The app kept the previous value.", controller.state.errorMessage)
        assertTrue(controller.state.canChange)
    }

    @Test
    fun `an unconfirmed write blocks further changes until settings load again`() = runBlocking<Unit> {
        var reads = 0
        val controller = SemanticSettingsController(
            execute = { request ->
                if (request.method == NextcloudApiMethod.GET) {
                    reads += 1
                    // The load and the pre-write comparison answer; the verification read does not.
                    if (reads > 2) throw IllegalStateException("offline")
                    response("""{"notesPath":"Notes","fileSuffix":".md"}""")
                } else {
                    throw IllegalStateException("connection reset")
                }
            },
            settings = notesSettings(),
        )
        controller.load()
        controller.change(controller.state.entries.single { it.key == "fileSuffix" }, ".txt")

        assertTrue(controller.state.outcomeUnknown)
        assertFalse(controller.state.canChange)
    }

    @Test
    fun `a refused write is reported without a verification read`() = runBlocking<Unit> {
        var reads = 0
        val controller = SemanticSettingsController(
            execute = { request ->
                if (request.method == NextcloudApiMethod.GET) {
                    reads += 1
                    response("""{"notesPath":"Notes","fileSuffix":".md"}""")
                } else {
                    response("{}", status = 400)
                }
            },
            settings = notesSettings(),
        )
        controller.load()
        controller.change(controller.state.entries.single { it.key == "fileSuffix" }, ".txt")

        assertEquals(2, reads, "The load and the pre-write comparison, with no read after the refusal.")
        assertFalse(controller.state.outcomeUnknown)
        assertTrue(controller.state.errorMessage.orEmpty().contains("HTTP 400"))
    }

    private fun boardSettings(): SemanticSettings {
        val descriptor = semanticDescriptor("planner", "9.0.0", boardRoutes().forApp("planner"))
        val contract = requireNotNull(resolveSemanticBoardContract(descriptor, writesAuthorized = true))
        return SemanticSettings(descriptor, requireNotNull(contract.settings))
    }

    private fun notesSettings(): SemanticSettings {
        val descriptor = semanticDescriptor("jotter", "0.0.1", notesRoutes().forApp("jotter"))
        val contract = requireNotNull(resolveSemanticNotesContract(descriptor, writesAuthorized = true))
        return SemanticSettings(descriptor, requireNotNull(contract.settings))
    }

    private fun response(body: String, status: Int = 200) =
        NextcloudApiResponse(status, body.encodeToByteArray(), "application/json", null)
}
