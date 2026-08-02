package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.app.design.NextcloudPresentation
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.runtime.nativeRelationOptionWindow
import dev.obiente.nextcloudnative.nativeui.runtime.nativeRelationOptions
import dev.obiente.nextcloudnative.nativeui.runtime.nativeFormDisplayFields
import dev.obiente.nextcloudnative.nativeui.runtime.nativeRecordActions
import dev.obiente.nextcloudnative.nativeui.runtime.nativeRecordPresentation
import dev.obiente.nextcloudnative.nativeui.runtime.nativeScalarRelationClearChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MarketingDynamicUiCaptureScenarioTest {
    @Test
    fun `synthetic dynamic visual QA is paired across desktop and phone`() {
        val desktop = MarketingCaptureScenario.AdaptiveApp
        val phone = MarketingCaptureScenario.AdaptiveAppMobile
        val phoneCollection = MarketingCaptureScenario.AdaptiveAppCollectionMobile
        val phoneContextMenu = MarketingCaptureScenario.AdaptiveAppContextMenuMobile

        assertEquals(NextcloudPresentation.Desktop, desktop.presentation)
        assertEquals(NextcloudPresentation.Adaptive, phone.presentation)
        assertEquals("Nested collection and semantic form", desktop.surface)
        assertEquals(desktop.surface, phone.surface)
        assertEquals("Synthetic visual QA", desktop.state)
        assertEquals(desktop.state, phone.state)
        assertEquals(MarketingCapturePurpose.Showcase, desktop.purpose)
        assertEquals(MarketingCapturePurpose.StateCoverage, phone.purpose)
        assertEquals(1_440 to 900, desktop.width to desktop.height)
        assertEquals(1_080 to 1_800, phone.width to phone.height)
        assertEquals(NextcloudPresentation.Adaptive, phoneCollection.presentation)
        assertEquals("Nested collection actions", phoneCollection.surface)
        assertEquals(1_080 to 1_800, phoneCollection.width to phoneCollection.height)
        assertEquals(NextcloudPresentation.Adaptive, phoneContextMenu.presentation)
        assertEquals("Context workspace menu", phoneContextMenu.surface)
    }

    @Test
    fun `fixture declares every intended generic visual QA state`() {
        assertEquals(
            setOf(
                MarketingDynamicUiFeature.ContractIdentity,
                MarketingDynamicUiFeature.RecordVisualIdentity,
                MarketingDynamicUiFeature.NestedCollection,
                MarketingDynamicUiFeature.EnumField,
                MarketingDynamicUiFeature.OptionalRelationClear,
                MarketingDynamicUiFeature.LargeRelationSearch,
                MarketingDynamicUiFeature.BooleanControl,
                MarketingDynamicUiFeature.RecurrenceControl,
                MarketingDynamicUiFeature.SemanticForm,
                MarketingDynamicUiFeature.StaleMutationSuppression,
                MarketingDynamicUiFeature.RelationRetry,
            ),
            marketingDynamicUiFixture.features,
        )
        assertEquals(3, marketingDynamicUiFixture.breadcrumbs.size)
        assertEquals(240, marketingDynamicUiFixture.relationOptionCount)
        assertTrue(marketingDynamicUiFixture.appName.isNotBlank())
        assertTrue(marketingDynamicUiFixture.description.isNotBlank())
        assertTrue(marketingDynamicUiFixture.iconText.isNotBlank())
    }

    @Test
    fun `fixture drives real generic field and relationship semantics`() {
        val resource = marketingDynamicWorkItemsResource
        val relationField = resource.fields.single { it.id == "groupId" }
        val options = nativeRelationOptions(
            field = relationField,
            formResource = resource,
            schema = marketingDynamicUiSchema,
            context = marketingDynamicDatasetContext,
        )
        val window = nativeRelationOptionWindow(options, query = "")
        val create = assertNotNull(marketingDynamicUiSchema.action("work-items.create"))
        val collectionCreate = assertNotNull(
            nativeRecordActions(
                schema = marketingDynamicUiSchema,
                resource = resource,
            ).create,
        )

        assertEquals(ActionIntent.create, create.intent)
        assertEquals(create.id, collectionCreate.action.id)
        assertEquals(
            listOf(
                FieldKind.string,
                FieldKind.string,
                FieldKind.string,
                FieldKind.string,
                FieldKind.enumeration,
                FieldKind.string,
                FieldKind.boolean,
                FieldKind.string,
            ),
            resource.fields.map { it.kind },
        )
        assertEquals(
            listOf("planned", "in-progress", "ready"),
            resource.fields.single { it.id == "status" }.enumValues,
        )
        assertFalse(relationField.required)
        assertEquals("None", nativeScalarRelationClearChoice(relationField)?.label)
        assertEquals(marketingDynamicUiFixture.relationOptionCount, options.size)
        assertEquals(40, window.options.size)
        assertTrue(window.hasMore)
        assertEquals("Garden team", options.first().label)
        assertTrue(resource.fields.any { it.id == "rrule" })
        assertEquals(
            listOf("garden", "calendar", "tools", "truck", "notes", "water", "checklist"),
            marketingDynamicWorkItemRecords.map { record ->
                nativeRecordPresentation(resource, record).iconKey
            },
        )
        assertEquals(
            listOf(
                "Prepare a clear layout for volunteers.",
                "Check availability for the next work day.",
                "Verify the shared tools and supplies list.",
                "Coordinate the shared trailer and delivery window.",
                "Share arrival details, safety notes, and contacts.",
                "Verify the outdoor tap and backup water containers.",
                "Collect the final setup and cleanup responsibilities.",
            ),
            marketingDynamicWorkItemRecords.map { record ->
                nativeRecordPresentation(resource, record).subtitle
            },
        )
        assertTrue(marketingDynamicWorkItemRecords.all { record ->
            nativeRecordPresentation(resource, record).colorArgb != null
        })
    }

    @Test
    fun `semantic forms present identity content choices and advanced controls in task order`() {
        val editableFields = marketingDynamicWorkItemsResource.fields.filterNot { it.readOnly }

        assertEquals(
            listOf("title", "status", "groupId", "sendReminders", "rrule"),
            nativeFormDisplayFields(
                fields = editableFields,
                relationFieldIds = setOf("groupId"),
            ).map { field -> field.id },
        )
    }

    @Test
    fun `fixture remains neutral deterministic and offline`() {
        val fixtureText = buildList {
            add(marketingDynamicUiFixture.appName)
            add(marketingDynamicUiFixture.description)
            addAll(marketingDynamicUiFixture.breadcrumbs)
            marketingDynamicWorkItemRecords.flatMapTo(this) { record ->
                record.values.values.filterNotNull()
            }
            marketingDynamicRelatedGroupRecords.flatMapTo(this) { record ->
                record.values.values.filterNotNull()
            }
        }.joinToString("\n").lowercase()
        val forbidden = listOf(
            "http://",
            "https://",
            "@",
            "/" + "home/",
            "pantry",
            "nextcloud deck",
            "personal",
        )

        forbidden.forEach { token ->
            assertFalse(token in fixtureText, "Synthetic fixture must not contain '$token'.")
        }
        assertTrue(marketingDynamicUiSchema.app.id.startsWith("synthetic-"))
        assertTrue(marketingDynamicUiSchema.actions.all { action ->
            "://" !in action.binding.path
        })
    }
}
