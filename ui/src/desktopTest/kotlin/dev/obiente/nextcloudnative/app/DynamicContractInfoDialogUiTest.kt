package dev.obiente.nextcloudnative.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The contract information dialog's write safety section, with synthetic routes only. */
@OptIn(ExperimentalComposeUiApi::class)
class DynamicContractInfoDialogUiTest {
    @Test
    fun aPausedRouteIsExplainedAndOffersResumingChanges() = onSceneThread {
        var resumed = 0
        val entries = listOf(
            DynamicWriteSafetyEntry("PUT /apps/notebook/api/notes/{id}", DynamicWriteRuleStatus.quarantined,
                DynamicWriteQuarantineReason.notAppliedAfterSuccess, "contract-declared v1"),
        )
        scene({ DynamicContractInfoDialog(INFO, DynamicWriteSafetyView.Ready(entries), { resumed++ }, {}) }) {
            val texts = texts()
            assertTrue(texts.any { "paused on this device" in it }, "$texts")
            assertTrue(texts.any { "reported success but did not apply" in it && "{id}" in it }, "$texts")
            clickText("Resume changes")
            assertEquals(1, resumed)
        }
    }

    @Test
    fun withoutRecordedStateNothingIsOfferedToResume() = onSceneThread {
        scene({ DynamicContractInfoDialog(INFO, DynamicWriteSafetyView.Ready(emptyList()), {}, {}) }) {
            assertTrue(texts().any { "No paused or probationary changes" in it })
            assertTrue(texts().none { it == "Resume changes" })
        }
        scene({ DynamicContractInfoDialog(INFO, DynamicWriteSafetyView.Loading, {}, {}) }) {
            assertTrue(texts().none { it == "Resume changes" }, "Nothing is cleared before the state is known.")
        }
    }

    @Test
    fun anUnreadableStateIsExplainedAndCanBeReset() = onSceneThread {
        var resumed = 0
        scene({ DynamicContractInfoDialog(INFO, DynamicWriteSafetyView.Unreadable, { resumed++ }, {}) }) {
            assertTrue(texts().any { "could not be read" in it })
            clickText("Resume changes")
            assertEquals(1, resumed)
        }
    }

    @Test
    fun theHostLoadsTheStoreAndResumingClearsOnlyThisApp() = onSceneThread {
        val store = MemoryDynamicWriteQuarantineStore()
        val account = "c".repeat(64)
        val key = DynamicWriteRuleKey(account, "notebook", "2.0.0", "PUT /apps/notebook/api/notes/{id}", "contract-declared", 1)
        store.quarantine(key, DynamicWriteQuarantineReason.untouchedFieldDrift, 1)
        store.beginProbation(key.copy(appId = "ledger", route = "POST /apps/ledger/api/items"), 1)
        store.beginProbation(key.copy(route = "PATCH /apps/notebook/api/notes/{id}", ruleId = "item-state-pair"), 1)
        scene({ DynamicContractInfoHost(INFO, store, account, "notebook", {}) }) {
            val texts = texts()
            assertTrue(texts.any { "other values changed" in it }, "$texts")
            assertTrue(texts.any { "In probation" in it && "item-state-pair v1" in it }, "$texts")
            clickText("Resume changes")
            settle()
            assertTrue(texts().any { "No paused or probationary changes" in it }, "${texts()}")
        }
        assertTrue(store.records(account, "notebook").isEmpty())
        assertEquals(1, store.records(account, "ledger").size, "Another app's state is untouched.")
    }

    private suspend fun scene(content: @androidx.compose.runtime.Composable () -> Unit, block: suspend ImageComposeScene.() -> Unit) {
        val scene = ImageComposeScene(900, 1000, Density(1f), coroutineContext = kotlin.coroutines.coroutineContext) {
            MaterialTheme { Surface { content() } }
        }
        try {
            scene.settle()
            scene.block()
        } finally {
            scene.close()
        }
    }

    private fun onSceneThread(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher -> runBlocking(dispatcher, block) }
    }

    private suspend fun ImageComposeScene.settle() { repeat(16) { render(System.nanoTime()).close(); yield() } }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun SemanticsNode.descendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.descendants() }
        return semanticsOwners.flatMap { it.rootSemanticsNode.descendants() }
    }

    private fun ImageComposeScene.texts(): List<String> = nodes().flatMap { node ->
        node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } +
            node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
    }

    private suspend fun ImageComposeScene.clickText(label: String) {
        val node = nodes().first { SemanticsActions.OnClick in it.config &&
            (it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == label } ||
                it.children.any { child -> child.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == label } }) }
        assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke())
        settle()
    }

    private companion object {
        val INFO = DynamicContractInfo(
            acquisition = "Signed App Store package", appVersion = "2.0.0", sourceSpecFile = "openapi.json",
            diagnosticCodes = emptyList(), resourceIds = listOf("notes"), layoutIds = emptyList(), linkIds = emptyList(),
            actionIds = listOf("notes-update"), childCandidates = emptyList(),
        )
    }
}
