package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.app.design.NextcloudPresentation
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.runtime.nativeRelationOptionWindow
import dev.obiente.nextcloudnative.nativeui.runtime.nativeRelationOptions
import dev.obiente.nextcloudnative.nativeui.runtime.nativeFormDisplayFields
import dev.obiente.nextcloudnative.nativeui.runtime.nativeDatasetInsights
import dev.obiente.nextcloudnative.nativeui.runtime.nativeRecordActions
import dev.obiente.nextcloudnative.nativeui.runtime.nativeScalarRelationClearChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MarketingDynamicUiCaptureScenarioTest {
    @Test
    fun `Tables visual evidence is paired across desktop and phone`() {
        val pairs = listOf(
            MarketingCaptureScenario.AdaptiveApp to
                MarketingCaptureScenario.AdaptiveAppContextMenuMobile,
            MarketingCaptureScenario.TablesRowsDesktop to
                MarketingCaptureScenario.AdaptiveAppCollectionMobile,
            MarketingCaptureScenario.TablesRowFormDesktop to
                MarketingCaptureScenario.AdaptiveAppMobile,
        )

        pairs.forEach { (desktop, phone) ->
            assertEquals(NextcloudPresentation.Desktop, desktop.presentation)
            assertEquals(NextcloudPresentation.Adaptive, phone.presentation)
            assertEquals(desktop.surface, phone.surface)
            assertEquals(desktop.state, phone.state)
            assertEquals("Synthetic Tables data", desktop.state)
            assertEquals(1_440 to 900, desktop.width to desktop.height)
            assertEquals(1_024 to 2_216, phone.width to phone.height)
        }
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
                MarketingDynamicUiFeature.DatasetInsights,
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
        val relationField = resource.fields.single { it.id == "locationId" }
        val options = nativeRelationOptions(
            field = relationField,
            formResource = resource,
            schema = marketingDynamicUiSchema,
            context = marketingDynamicDatasetContext,
        )
        val window = nativeRelationOptionWindow(options, query = "")
        val create = assertNotNull(marketingDynamicUiSchema.action("rows.create"))
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
                FieldKind.enumeration,
                FieldKind.integer,
                FieldKind.integer,
                FieldKind.string,
                FieldKind.boolean,
            ),
            resource.fields.map { it.kind },
        )
        assertEquals(
            listOf("tools", "materials", "safety"),
            resource.fields.single { it.id == "category" }.enumValues,
        )
        assertFalse(relationField.required)
        assertEquals("None", nativeScalarRelationClearChoice(relationField)?.label)
        assertEquals(marketingDynamicUiFixture.relationOptionCount, options.size)
        assertEquals(40, window.options.size)
        assertTrue(window.hasMore)
        assertTrue(options.any { option -> option.label == "Workshop" })
        assertTrue(marketingDynamicWorkItemRecords.all { record ->
            record.values.getValue("quantity")?.toIntOrNull() != null
        })
        val insights = assertNotNull(nativeDatasetInsights(resource, marketingDynamicWorkItemRecords))
        assertEquals("quantity", insights.measure.id)
        assertEquals("category", insights.dimension?.id)
    }

    @Test
    fun `semantic forms present identity content choices and advanced controls in task order`() {
        val editableFields = marketingDynamicWorkItemsResource.fields.filterNot { it.readOnly }

        assertEquals(
            listOf("item", "quantity", "reorderLevel", "category", "locationId", "active"),
            nativeFormDisplayFields(
                fields = editableFields,
                relationFieldIds = setOf("locationId"),
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
        assertEquals("tables", marketingDynamicUiSchema.app.id)
        assertEquals("Tables", marketingDynamicUiSchema.app.name)
        assertTrue(marketingDynamicUiSchema.actions.all { action ->
            "://" !in action.binding.path
        })
    }
}
