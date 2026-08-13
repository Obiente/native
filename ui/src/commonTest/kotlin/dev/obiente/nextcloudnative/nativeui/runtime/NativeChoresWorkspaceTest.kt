package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.Evidence
import dev.obiente.nextcloudnative.nativeui.model.EvidenceSource
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeChoresWorkspaceTest {
    @Test
    fun `only exact signed Chores reads enter the workspace`() {
        val fixture = fixture()

        assertEquals(NativeChoresWorkspaceKind.Team, nativeChoresWorkspaceKind(fixture.schema, fixture.teamView))
        assertEquals(NativeChoresWorkspaceKind.Chores, nativeChoresWorkspaceKind(fixture.schema, fixture.choresView))
        assertEquals(NativeChoresWorkspaceKind.History, nativeChoresWorkspaceKind(fixture.schema, fixture.historyView))

        assertNull(
            nativeChoresWorkspaceKind(
                fixture.schema.copy(app = AppIdentity("other", "Other", "1.0")),
                fixture.teamView,
            ),
        )
        assertNull(
            nativeChoresWorkspaceKind(
                fixture.schema.copy(app = fixture.schema.app.copy(version = "0.1.1")),
                fixture.teamView,
            ),
        )
        val inferred = fixture.schema.actions.first().copy(
            id = "inferred",
            confidence = Confidence.medium,
            evidence = emptyList(),
        )
        val inferredView = fixture.teamView.copy(id = "inferred", sourceActionId = inferred.id)
        assertNull(
            nativeChoresWorkspaceKind(
                fixture.schema.copy(actions = fixture.schema.actions + inferred, views = fixture.schema.views + inferredView),
                inferredView,
            ),
        )
    }

    @Test
    fun `signed chore records retain assignment points due date and recurrence`() {
        val fixture = fixture()
        val presentation = requireNotNull(
            nativeChoresPresentation(
                fixture.schema,
                fixture.choresView,
                fixture.choresResource,
                NativeScreenState.Ready(
                    listOf(
                        NativeRecord(
                            id = "7",
                            values = mapOf(
                                "name" to "Clean the kitchen",
                                "assignee" to "alex",
                                "points" to "3",
                                "due" to "2026-08-14T18:00:00Z",
                                "repeat" to "w:1:-",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val item = assertIs<NativeChoresContent.Ready>(presentation.content).items.single()

        assertEquals("Clean the kitchen", item.title)
        assertEquals("Assigned to alex", item.subtitle)
        assertEquals("3", item.metrics.first { it.label == "Points" }.value)
        assertTrue(item.metrics.any { it.label == "Due" })
        assertEquals("Weekly", item.metrics.first { it.label == "Repeats" }.value)
    }

    @Test
    fun `empty and failed reads remain actionable Chores states`() {
        val fixture = fixture()
        val empty = requireNotNull(
            nativeChoresPresentation(
                fixture.schema,
                fixture.choresView,
                fixture.choresResource,
                NativeScreenState.Ready(emptyList()),
            ),
        )
        assertEquals("Nothing to do", assertIs<NativeChoresContent.Empty>(empty.content).title)

        var retries = 0
        val failed = requireNotNull(
            nativeChoresPresentation(
                fixture.schema,
                fixture.choresView,
                fixture.choresResource,
                NativeScreenState.Error("Offline", { retries += 1 }, "Try again"),
            ),
        )
        val error = assertIs<NativeChoresContent.Error>(failed.content)
        requireNotNull(error.retry).invoke()
        assertEquals(1, retries)
    }

    @Test
    fun `team presentation expands members and pending invitations`() {
        val fixture = fixture()
        fun scalar(value: String) = NativeStructuredValue.Scalar(value, NativeStructuredScalarKind.string)
        fun objectValue(vararg values: Pair<String, String>) = NativeStructuredValue.ObjectValue(
            values.map { (key, value) -> NativeStructuredEntry(key, key, scalar(value)) },
        )
        val presentation = requireNotNull(
            nativeChoresPresentation(
                fixture.schema,
                fixture.teamView,
                fixture.teamResource,
                NativeScreenState.Ready(
                    listOf(
                        NativeRecord(
                            id = "12",
                            values = mapOf("name" to "Home", "owner" to "alex"),
                            structuredValues = mapOf(
                                "members" to NativeStructuredValue.ListValue(
                                    listOf(
                                        objectValue(
                                            "member" to "alex",
                                            "displayName" to "Alex",
                                            "points" to "8",
                                        ),
                                        objectValue(
                                            "member" to "sam",
                                            "displayName" to "Sam",
                                            "points" to "3",
                                        ),
                                    ),
                                ),
                                "invites" to NativeStructuredValue.ListValue(
                                    listOf(objectValue("userId" to "taylor")),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val team = requireNotNull(nativeRosterPresentation((presentation.content as NativeChoresContent.Ready).items.single().record))
        assertEquals("12", team.id)
        assertEquals("Home", team.name)
        assertEquals(listOf("alex", "sam"), team.people.map(NativeRosterPerson::userId))
        assertTrue(team.people.single { it.userId == "alex" }.owner)
        assertEquals(8, team.people.single { it.userId == "alex" }.score)
        assertEquals(listOf("taylor"), team.invitations.map(NativeRosterInvitation::userId))
    }

    private fun fixture(): Fixture {
        val team = ResourceSpec("team", "Team", Confidence.verified)
        val chores = ResourceSpec("chores", "Chores", Confidence.verified)
        val history = ResourceSpec("work", "History", Confidence.verified)
        val teamAction = action("team", team.id, "/apps/chores/api/v1.0/team")
        val choresAction = action("chores", chores.id, "/apps/chores/api/v1.0/team/{teamId}/chores")
        val historyAction = action("history", history.id, "/apps/chores/api/v1.0/team/{teamId}/work")
        val teamView = view(team, teamAction)
        val choresView = view(chores, choresAction)
        val historyView = view(history, historyAction)
        return Fixture(
            NativeAppSchema(
                schemaVersion = "1",
                app = AppIdentity("chores", "Chores", "0.1.0"),
                confidence = Confidence.verified,
                resources = listOf(team, chores, history),
                views = listOf(teamView, choresView, historyView),
                actions = listOf(teamAction, choresAction, historyAction),
            ),
            team,
            chores,
            teamView,
            choresView,
            historyView,
        )
    }

    private fun action(id: String, resourceId: String, path: String) = ActionSpec(
        id = id,
        label = id,
        resourceId = resourceId,
        binding = ApiBinding(HttpMethod.GET, path, id),
        intent = ActionIntent.list,
        risk = ActionRisk.readOnly,
        requiresConfirmation = false,
        confidence = Confidence.high,
        evidence = listOf(Evidence(EvidenceSource.verifiedAppPackage, "Signed Chores package")),
    )

    private fun view(resource: ResourceSpec, action: ActionSpec) = ViewSpec(
        id = "${resource.id}.view",
        title = resource.name,
        resourceId = resource.id,
        component = NativeComponent.collectionList,
        sourceActionId = action.id,
        confidence = Confidence.high,
    )

    private data class Fixture(
        val schema: NativeAppSchema,
        val teamResource: ResourceSpec,
        val choresResource: ResourceSpec,
        val teamView: ViewSpec,
        val choresView: ViewSpec,
        val historyView: ViewSpec,
    )
}
