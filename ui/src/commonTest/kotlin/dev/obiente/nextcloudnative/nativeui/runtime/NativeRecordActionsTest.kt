package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionEffect
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ResourceRelationshipSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeRecordActionsTest {
    @Test
    fun `structural record actions bind exact parent and item identities`() {
        val resource = resource(
            fields = listOf(
                field("id", "ID", FieldKind.string, readOnly = true),
                field("title", "Title", FieldKind.string),
                field("notes", "Notes", FieldKind.longText),
                field("done", "Done", FieldKind.boolean),
            ),
        )
        val actions = listOf(
            action(
                id = "add",
                intent = ActionIntent.create,
                method = HttpMethod.POST,
                pathNames = listOf("containerId"),
                bodyNames = listOf("title", "notes"),
                requiredBodyNames = listOf("title"),
            ),
            action(
                id = "change",
                intent = ActionIntent.update,
                method = HttpMethod.PATCH,
                pathNames = listOf("recordId"),
                bodyNames = listOf("title", "notes"),
                requiredBodyNames = listOf("title"),
            ),
            action(
                id = "remove",
                intent = ActionIntent.delete,
                risk = ActionRisk.destructive,
                method = HttpMethod.DELETE,
                pathNames = listOf("recordId"),
                confirmation = true,
            ),
            action(
                id = "set-state",
                intent = ActionIntent.update,
                method = HttpMethod.PATCH,
                pathNames = listOf("recordId"),
                bodyNames = listOf("done"),
                requiredBodyNames = listOf("done"),
            ),
            action(
                id = "archive",
                intent = ActionIntent.execute,
                effect = ActionEffect.archive,
                method = HttpMethod.POST,
                pathNames = listOf("recordId"),
            ).withRecordPath("archive"),
        )
        val schema = schema(resource, actions)
        val record = NativeRecord(
            id = "item-9",
            values = mapOf(
                "id" to "item-9",
                "title" to "Prepare room",
                "notes" to "Before noon",
                "done" to "false",
            ),
            displayValues = mapOf("notes" to "Formatted notes must not become a write value"),
            bindingContext = mapOf("containerId" to "collection-4"),
        )

        val plans = nativeRecordActions(schema, resource, record)

        assertEquals(listOf("title", "notes"), plans.create?.fields?.map(FieldSpec::id))
        assertEquals(
            mapOf("containerId" to "collection-4", "title" to "New item"),
            requireNotNull(plans.create).request(mapOf("title" to "New item")).values,
        )
        assertEquals(
            mapOf("title" to "Prepare room", "notes" to "Before noon"),
            plans.edit?.initialValues,
        )
        assertEquals(
            mapOf("recordId" to "item-9", "title" to "Updated", "notes" to ""),
            requireNotNull(plans.edit).request(mapOf("title" to "Updated", "notes" to "")).values,
        )
        assertEquals(
            mapOf("recordId" to "item-9"),
            requireNotNull(plans.delete).request(confirmed = true).values,
        )
        val completion = requireNotNull(plans.completion)
        assertFalse(completion.currentlyCompleted)
        assertEquals(
            mapOf("recordId" to "item-9", "done" to "true"),
            completion.request(completed = true).values,
        )
        assertEquals(
            mapOf("recordId" to "item-9", "done" to "false"),
            completion.request(completed = false).values,
        )

        val emptyCollectionCreate = nativeRecordActions(
            schema = schema,
            resource = resource,
            navigationContext = mapOf("containerId" to "collection-4"),
        ).create
        assertEquals(
            mapOf("containerId" to "collection-4", "title" to "First item"),
            requireNotNull(emptyCollectionCreate).request(mapOf("title" to "First item")).values,
        )
    }

    @Test
    fun `explicit record capability denials withhold writes while preserving collection create`() {
        val resource = resource(
            fields = listOf(
                field("id", "ID", FieldKind.string, readOnly = true),
                field("title", "Title", FieldKind.string),
                field("done", "Done", FieldKind.boolean),
                field("readOnly", "Read only", FieldKind.boolean, readOnly = true),
                field("canEdit", "Can edit", FieldKind.boolean, readOnly = true),
                field("canDelete", "Can delete", FieldKind.boolean, readOnly = true),
            ),
        )
        val actions = listOf(
            action(
                id = "add",
                intent = ActionIntent.create,
                method = HttpMethod.POST,
                pathNames = listOf("containerId"),
                bodyNames = listOf("title"),
                requiredBodyNames = listOf("title"),
            ),
            action(
                id = "change",
                intent = ActionIntent.update,
                method = HttpMethod.PATCH,
                pathNames = listOf("recordId"),
                bodyNames = listOf("title"),
                requiredBodyNames = listOf("title"),
            ),
            action(
                id = "remove",
                intent = ActionIntent.delete,
                risk = ActionRisk.destructive,
                method = HttpMethod.DELETE,
                pathNames = listOf("recordId"),
                confirmation = true,
            ),
            action(
                id = "set-state",
                intent = ActionIntent.update,
                method = HttpMethod.PATCH,
                pathNames = listOf("recordId"),
                bodyNames = listOf("done"),
                requiredBodyNames = listOf("done"),
            ),
            action(
                id = "archive",
                intent = ActionIntent.execute,
                effect = ActionEffect.archive,
                method = HttpMethod.POST,
                pathNames = listOf("recordId"),
            ).withRecordPath("archive"),
        )
        val schema = schema(resource, actions)
        val readOnlyRecord = NativeRecord(
            id = "item-9",
            values = mapOf(
                "id" to "item-9",
                "title" to "Prepare room",
                "done" to "false",
                "readOnly" to "true",
            ),
            bindingContext = mapOf("containerId" to "collection-4"),
        )

        val readOnlyPlans = nativeRecordActions(schema, resource, readOnlyRecord)

        assertTrue(readOnlyPlans.create != null)
        assertNull(readOnlyPlans.edit)
        assertNull(readOnlyPlans.delete)
        assertNull(readOnlyPlans.completion)
        assertTrue(readOnlyPlans.commands.isEmpty())

        val unknownPlans = nativeRecordActions(
            schema,
            resource,
            readOnlyRecord.copy(
                values = readOnlyRecord.values + ("readOnly" to "false"),
            ),
        )

        assertTrue(unknownPlans.create != null)
        assertNull(unknownPlans.edit)
        assertNull(unknownPlans.delete)
        assertNull(unknownPlans.completion)
        assertTrue(unknownPlans.commands.isEmpty())

        val deleteOnlyRecord = readOnlyRecord.copy(
            values = readOnlyRecord.values + mapOf(
                "readOnly" to "false",
                "canEdit" to "false",
                "canDelete" to "true",
            ),
        )
        val deleteOnlyPlans = nativeRecordActions(schema, resource, deleteOnlyRecord)

        assertNull(deleteOnlyPlans.edit)
        assertNull(deleteOnlyPlans.completion)
        assertTrue(deleteOnlyPlans.delete != null)
        assertTrue(deleteOnlyPlans.commands.isEmpty())

        val writablePlans = nativeRecordActions(
            schema,
            resource,
            deleteOnlyRecord.copy(
                values = deleteOnlyRecord.values + ("canEdit" to "true"),
            ),
        )
        assertEquals(listOf(ActionEffect.archive), writablePlans.commands.map { command -> command.effect })
    }

    @Test
    fun `bodyless semantic toggle becomes a reversible completion action`() {
        val resource = resource(
            fields = listOf(
                field("id", "ID", FieldKind.string, readOnly = true),
                field("done", "Done", FieldKind.boolean, readOnly = true),
            ),
        )
        val toggle = action(
            id = "toggle-state",
            intent = ActionIntent.execute,
            effect = ActionEffect.toggle,
            method = HttpMethod.POST,
            pathNames = listOf("recordId"),
        ).let { action ->
            action.copy(
                binding = action.binding.copy(path = "/api/records/{recordId}/toggle"),
            )
        }
        val incomplete = NativeRecord(
            id = "record-14",
            values = mapOf("id" to "record-14", "done" to "false"),
        )

        val plan = requireNotNull(
            nativeRecordActions(schema(resource, listOf(toggle)), resource, incomplete).completion,
        )

        assertEquals(NativeRecordCompletionActionKind.Toggle, plan.kind)
        assertFalse(plan.currentlyCompleted)
        assertEquals(
            mapOf("recordId" to "record-14"),
            plan.request(completed = true).values,
        )
        assertFailsWith<IllegalArgumentException> {
            plan.request(completed = false)
        }

        val completed = incomplete.copy(values = incomplete.values + ("done" to "true"))
        val reversePlan = requireNotNull(
            nativeRecordActions(schema(resource, listOf(toggle)), resource, completed).completion,
        )
        assertEquals(
            mapOf("recordId" to "record-14"),
            reversePlan.request(completed = false).values,
        )

        val dedicatedPutToggle = toggle.copy(
            binding = toggle.binding.copy(method = HttpMethod.PUT),
        )
        assertEquals(
            NativeRecordCompletionActionKind.Toggle,
            requireNotNull(
                nativeRecordActions(
                    schema(resource, listOf(dedicatedPutToggle)),
                    resource,
                    incomplete,
                ).completion,
            ).kind,
        )
    }

    @Test
    fun `record transition commands bind exact identities and declared values`() {
        val resource = resource(
            fields = listOf(
                field("id", "ID", FieldKind.string, readOnly = true),
                field("destinationId", "Destination", FieldKind.string, readOnly = true),
            ),
        )
        val archive = action(
            id = "archive-record",
            intent = ActionIntent.execute,
            effect = ActionEffect.archive,
            method = HttpMethod.POST,
            pathNames = listOf("recordId"),
        ).withRecordPath("archive")
        val copy = action(
            id = "copy-record",
            intent = ActionIntent.execute,
            effect = ActionEffect.copy,
            method = HttpMethod.POST,
            pathNames = listOf("recordId"),
            bodyNames = listOf("destinationId"),
            requiredBodyNames = listOf("destinationId"),
        ).withRecordPath("copy")
        val record = NativeRecord(
            id = "record-20",
            values = mapOf(
                "id" to "record-20",
                "destinationId" to "destination-4",
            ),
        )

        val commands = nativeRecordActions(
            schema(resource, listOf(copy, archive)),
            resource,
            record,
        ).commands

        assertEquals(
            listOf(ActionEffect.archive, ActionEffect.copy),
            commands.map(NativeRecordCommandActionPlan::effect),
        )
        assertEquals(
            mapOf("recordId" to "record-20"),
            commands.first().request().values,
        )
        assertEquals(
            mapOf(
                "recordId" to "record-20",
                "destinationId" to "destination-4",
            ),
            commands.last().request().values,
        )
        assertFalse(commands.any(NativeRecordCommandActionPlan::requiresConfirmation))
    }

    @Test
    fun `destructive record commands require explicit confirmation`() {
        val resource = resource(fields = listOf(field("id", "ID", FieldKind.string, readOnly = true)))
        val permanentDelete = action(
            id = "delete-record-permanently",
            intent = ActionIntent.delete,
            effect = ActionEffect.permanentDelete,
            risk = ActionRisk.destructive,
            method = HttpMethod.DELETE,
            pathNames = listOf("recordId"),
            confirmation = true,
        ).withRecordPath("permanent")
        val record = NativeRecord(
            "record-21",
            mapOf("id" to "record-21", "deletedAt" to "2026-07-30T12:00:00Z"),
        )

        val plan = requireNotNull(
            nativeRecordActions(
                schema(resource, listOf(permanentDelete)),
                resource,
                record,
            ).commands.singleOrNull(),
        )

        assertTrue(plan.requiresConfirmation)
        assertFailsWith<IllegalArgumentException> {
            plan.request()
        }
        assertEquals(
            mapOf("recordId" to "record-21"),
            plan.request(confirmed = true).values,
        )
        assertTrue(plan.request(confirmed = true).confirmed)
    }

    @Test
    fun `record state gates reversible and permanent transition commands`() {
        val resource = resource(fields = listOf(field("id", "ID", FieldKind.string, readOnly = true)))
        val archive = action(
            id = "archive-record",
            intent = ActionIntent.execute,
            effect = ActionEffect.archive,
            method = HttpMethod.POST,
            pathNames = listOf("recordId"),
        ).withRecordPath("archive")
        val unarchive = archive.copy(
            id = "unarchive-record",
            effect = ActionEffect.unarchive,
            binding = archive.binding.copy(path = "/api/records/{recordId}/unarchive"),
        )
        val restore = archive.copy(
            id = "restore-record",
            effect = ActionEffect.restore,
            binding = archive.binding.copy(path = "/api/records/{recordId}/restore"),
        )
        val permanentDelete = action(
            id = "delete-record-permanently",
            intent = ActionIntent.delete,
            effect = ActionEffect.permanentDelete,
            risk = ActionRisk.destructive,
            method = HttpMethod.DELETE,
            pathNames = listOf("recordId"),
            confirmation = true,
        ).withRecordPath("permanent")
        val schema = schema(resource, listOf(archive, unarchive, restore, permanentDelete))

        assertEquals(
            listOf(ActionEffect.archive),
            nativeRecordActions(
                schema,
                resource,
                NativeRecord("record-active", mapOf("id" to "record-active")),
            ).commands.map(NativeRecordCommandActionPlan::effect),
        )
        assertEquals(
            listOf(ActionEffect.unarchive),
            nativeRecordActions(
                schema,
                resource,
                NativeRecord(
                    "record-archived",
                    mapOf("id" to "record-archived", "archivedAt" to "2026-07-30T12:00:00Z"),
                ),
            ).commands.map(NativeRecordCommandActionPlan::effect),
        )
        assertEquals(
            listOf(ActionEffect.restore, ActionEffect.permanentDelete),
            nativeRecordActions(
                schema,
                resource,
                NativeRecord(
                    "record-deleted",
                    mapOf("id" to "record-deleted", "deletedAt" to "2026-07-30T12:00:00Z"),
                ),
            ).commands.map(NativeRecordCommandActionPlan::effect),
        )
    }

    @Test
    fun `record commands reject ambiguous unsupported and unresolved inputs`() {
        val resource = resource(
            fields = listOf(field("id", "ID", FieldKind.string, readOnly = true)),
        )
        val archive = action(
            id = "archive-record",
            intent = ActionIntent.execute,
            effect = ActionEffect.archive,
            method = HttpMethod.POST,
            pathNames = listOf("recordId"),
        ).withRecordPath("archive")
        val unresolvedQuery = action(
            id = "restore-record",
            intent = ActionIntent.execute,
            effect = ActionEffect.restore,
            method = HttpMethod.POST,
            pathNames = listOf("recordId"),
            queryNames = listOf("revision"),
        ).withRecordPath("restore")
        val unsupportedBatch = action(
            id = "batch-record",
            intent = ActionIntent.execute,
            effect = ActionEffect.batch,
            method = HttpMethod.POST,
            pathNames = listOf("recordId"),
        ).withRecordPath("batch")
        val opaqueBody = action(
            id = "unarchive-record",
            intent = ActionIntent.execute,
            effect = ActionEffect.unarchive,
            method = HttpMethod.POST,
            pathNames = listOf("recordId"),
        ).withRecordPath("unarchive").let { candidate ->
            candidate.copy(binding = candidate.binding.copy(bodyContentType = "application/json"))
        }
        val unsafeLeave = action(
            id = "leave-record",
            intent = ActionIntent.execute,
            effect = ActionEffect.leave,
            risk = ActionRisk.destructive,
            method = HttpMethod.DELETE,
            pathNames = listOf("recordId"),
            confirmation = false,
        ).withRecordPath("leave")
        val duplicateArchive = archive.copy(id = "archive-record-alias")
        val record = NativeRecord("record-22", mapOf("id" to "record-22"))

        val commands = nativeRecordActions(
            schema(
                resource,
                listOf(
                    archive,
                    duplicateArchive,
                    unresolvedQuery,
                    unsupportedBatch,
                    opaqueBody,
                    unsafeLeave,
                ),
            ),
            resource,
            record,
        ).commands

        assertTrue(commands.isEmpty())
    }

    @Test
    fun `normalized parent id binds one proven documented parent placeholder`() {
        val resource = resource(
            id = "lists",
            fields = listOf(field("name", "Name", FieldKind.string)),
        )
        val create = action(
            id = "create-list",
            intent = ActionIntent.create,
            method = HttpMethod.POST,
            pathNames = listOf("houseId"),
            bodyNames = listOf("name"),
            requiredBodyNames = listOf("name"),
        ).let { action ->
            action.copy(
                resourceId = "lists",
                binding = action.binding.copy(
                    path = "/api/houses/{houseId}/lists",
                ),
            )
        }

        val plan = nativeRecordActions(
            schema = schema(resource, listOf(create)),
            resource = resource,
            navigationContext = mapOf("id" to "house-7"),
        ).create

        assertEquals(
            mapOf("houseId" to "house-7", "name" to "Shopping"),
            requireNotNull(plan).request(mapOf("name" to "Shopping")).values,
        )
        assertNull(
            nativeRecordActions(
                schema = schema(
                    resource,
                    listOf(
                        create.copy(
                            binding = create.binding.copy(
                                path = "/api/accounts/{houseId}/lists",
                            ),
                        ),
                    ),
                ),
                resource = resource,
                navigationContext = mapOf("id" to "house-7"),
            ).create,
        )
    }

    @Test
    fun `safe record plans become reachable card actions without changing their targets`() {
        val resource = resource(
            fields = listOf(
                field("id", "ID", FieldKind.string, readOnly = true),
                field("title", "Title", FieldKind.string),
            ),
        )
        val schema = schema(
            resource,
            listOf(
                action(
                    id = "change",
                    intent = ActionIntent.update,
                    method = HttpMethod.PATCH,
                    pathNames = listOf("recordId"),
                    bodyNames = listOf("title"),
                    requiredBodyNames = listOf("title"),
                ),
                action(
                    id = "remove",
                    intent = ActionIntent.delete,
                    risk = ActionRisk.destructive,
                    method = HttpMethod.DELETE,
                    pathNames = listOf("recordId"),
                    confirmation = true,
                ),
            ),
        )
        val record = NativeRecord(
            id = "item-12",
            values = mapOf("id" to "item-12", "title" to "Reachable item"),
        )
        val capabilities = nativeRecordActions(schema, resource, record)
        var editTarget: Pair<NativeRecord, NativeRecordFormActionPlan>? = null
        var deleteTarget: Pair<NativeRecord, NativeRecordDeleteActionPlan>? = null
        var commandTarget: Pair<NativeRecord, NativeRecordCommandActionPlan>? = null

        val actions = nativeRecordCardActions(
            capabilities = capabilities,
            record = record,
            onEditRecord = { target, plan -> editTarget = target to plan },
            onDeleteRecord = { target, plan -> deleteTarget = target to plan },
            onCommandRecord = { target, plan -> commandTarget = target to plan },
        )

        assertEquals(listOf("Edit", "Delete"), actions.map { action -> action.label })
        assertFalse(actions.first().destructive)
        assertTrue(actions.last().destructive)

        actions.first().onClick()
        actions.last().onClick()

        assertEquals(record, editTarget?.first)
        assertEquals(capabilities.edit, editTarget?.second)
        assertEquals(record, deleteTarget?.first)
        assertEquals(capabilities.delete, deleteTarget?.second)
        assertNull(commandTarget)
    }

    @Test
    fun `enumerated status is reversible only when both states are declared`() {
        val resource = resource(
            fields = listOf(
                field("uuid", "UUID", FieldKind.string, readOnly = true),
                field("summary", "Summary", FieldKind.string),
                field(
                    "state",
                    "State",
                    FieldKind.enumeration,
                    enumValues = listOf("active", "finished", "paused"),
                ),
            ),
        )
        val update = action(
            id = "update-state",
            intent = ActionIntent.update,
            method = HttpMethod.PATCH,
            pathNames = listOf("recordId"),
            bodyNames = listOf("state"),
            requiredBodyNames = listOf("state"),
        )
        val record = NativeRecord(
            id = "entry-a",
            values = mapOf("uuid" to "entry-a", "summary" to "Lock up", "state" to "active"),
        )

        val plan = requireNotNull(nativeRecordActions(schema(resource, listOf(update)), resource, record).completion)

        assertEquals("finished", plan.completedWireValue)
        assertEquals("active", plan.incompleteWireValue)
        assertEquals("finished", plan.request(completed = true).values["state"])

        val oneWayResource = resource.copy(
            fields = resource.fields.map { field ->
                if (field.id == "state") field.copy(enumValues = listOf("finished", "paused")) else field
            },
        )
        assertNull(
            nativeRecordActions(
                schema(oneWayResource, listOf(update)),
                oneWayResource,
                record,
            ).completion,
        )
    }

    @Test
    fun `partial completion update is withheld from replacement style put`() {
        val resource = resource(
            fields = listOf(
                field("id", "ID", FieldKind.string, readOnly = true),
                field("title", "Title", FieldKind.string),
                field("done", "Done", FieldKind.boolean),
            ),
        )
        val putUpdate = action(
            id = "replace-record",
            intent = ActionIntent.update,
            method = HttpMethod.PUT,
            pathNames = listOf("recordId"),
            bodyNames = listOf("title", "done"),
            requiredBodyNames = listOf("done"),
        )
        val record = NativeRecord(
            id = "record-22",
            values = mapOf(
                "id" to "record-22",
                "title" to "Authoritative title",
                "done" to "false",
            ),
        )

        assertNull(
            nativeRecordActions(
                schema(resource, listOf(putUpdate)),
                resource,
                record,
            ).completion,
        )

        val patchUpdate = putUpdate.copy(
            id = "patch-record",
            binding = putUpdate.binding.copy(
                method = HttpMethod.PATCH,
                operationId = "patch-record",
            ),
        )
        assertEquals(
            mapOf("recordId" to "record-22", "done" to "true"),
            requireNotNull(
                nativeRecordActions(
                    schema(resource, listOf(patchUpdate)),
                    resource,
                    record,
                ).completion,
            ).request(completed = true).values,
        )
    }

    @Test
    fun `ambiguous actions and completion fields fail closed`() {
        val resource = resource(
            fields = listOf(
                field("title", "Title", FieldKind.string),
                field("done", "Done", FieldKind.boolean),
            ),
        )
        val edit = action(
            id = "edit-one",
            intent = ActionIntent.update,
            method = HttpMethod.PATCH,
            pathNames = listOf("recordId"),
            bodyNames = listOf("title"),
        )
        val duplicateEdit = edit.copy(id = "edit-two")
        val complete = action(
            id = "complete-one",
            intent = ActionIntent.update,
            method = HttpMethod.PATCH,
            pathNames = listOf("recordId"),
            bodyNames = listOf("done"),
        )
        val duplicateComplete = complete.copy(id = "complete-two")
        val record = NativeRecord(
            id = "record-8",
            values = mapOf("title" to "Example", "done" to "false"),
        )

        val plans = nativeRecordActions(
            schema(resource, listOf(edit, duplicateEdit, complete, duplicateComplete)),
            resource,
            record,
        )

        assertNull(plans.edit)
        assertNull(plans.completion)

        val ambiguousResource = resource.copy(
            fields = resource.fields + field("completed", "Completed", FieldKind.boolean),
        )
        assertNull(
            nativeRecordActions(
                schema(ambiguousResource, listOf(complete)),
                ambiguousResource,
                record.copy(values = record.values + ("completed" to "false")),
            ).completion,
        )
    }

    @Test
    fun `unsafe identity unresolved context and low confidence expose no writes`() {
        val resource = resource(
            fields = listOf(field("title", "Title", FieldKind.string)),
        )
        val create = action(
            id = "create",
            intent = ActionIntent.create,
            method = HttpMethod.POST,
            pathNames = listOf("parentToken"),
            bodyNames = listOf("title"),
            requiredBodyNames = listOf("title"),
        )
        val edit = action(
            id = "edit",
            intent = ActionIntent.update,
            method = HttpMethod.PATCH,
            pathNames = listOf("recordId"),
            bodyNames = listOf("title"),
        )
        val delete = action(
            id = "delete",
            intent = ActionIntent.delete,
            risk = ActionRisk.destructive,
            method = HttpMethod.DELETE,
            pathNames = listOf("recordId"),
            confirmation = true,
        )
        val unsafeRecord = NativeRecord(
            id = "response-row",
            values = mapOf("title" to "Observed only"),
            actionSafeIdentity = false,
        )

        val plans = nativeRecordActions(
            schema(resource, listOf(create, edit, delete)),
            resource,
            unsafeRecord,
        )

        assertNull(plans.create)
        assertNull(plans.edit)
        assertNull(plans.delete)

        val lowConfidence = create.copy(confidence = Confidence.medium)
        assertNull(
            nativeRecordActions(
                schema(resource, listOf(lowConfidence)),
                resource,
                navigationContext = mapOf("parentToken" to "parent-1"),
            ).create,
        )
    }

    @Test
    fun `unknown required body and unconfirmed destructive action fail closed`() {
        val resource = resource(
            fields = listOf(field("title", "Title", FieldKind.string)),
        )
        val incompleteCreate = action(
            id = "create",
            intent = ActionIntent.create,
            method = HttpMethod.POST,
            bodyNames = listOf("title", "policyToken"),
            requiredBodyNames = listOf("title", "policyToken"),
        )
        val unconfirmedDelete = action(
            id = "delete",
            intent = ActionIntent.delete,
            risk = ActionRisk.destructive,
            method = HttpMethod.DELETE,
            pathNames = listOf("recordId"),
            confirmation = false,
        )
        val record = NativeRecord("row-2", mapOf("title" to "Example"))
        val plans = nativeRecordActions(
            schema(resource, listOf(incompleteCreate, unconfirmedDelete)),
            resource,
            record,
        )

        assertNull(plans.create)
        assertNull(plans.delete)
    }

    @Test
    fun `unsupported optional structured bodies never become empty record forms`() {
        val resource = resource(
            fields = listOf(field("configuration", "Configuration", FieldKind.objectValue)),
        )
        val create = action(
            id = "create",
            intent = ActionIntent.create,
            method = HttpMethod.POST,
            bodyNames = listOf("configuration"),
        )

        assertNull(
            nativeRecordActions(schema(resource, listOf(create)), resource).create,
        )
    }

    @Test
    fun `form plans reject undeclared invalid and missing inputs`() {
        val resource = resource(
            fields = listOf(
                field("title", "Title", FieldKind.string),
                field("priority", "Priority", FieldKind.integer),
            ),
        )
        val create = action(
            id = "create",
            intent = ActionIntent.create,
            method = HttpMethod.POST,
            bodyNames = listOf("title", "priority"),
            requiredBodyNames = listOf("title"),
        )
        val plan = requireNotNull(nativeRecordActions(schema(resource, listOf(create)), resource).create)

        assertFailsWith<IllegalArgumentException> {
            plan.request(mapOf("unknown" to "value"))
        }
        assertFailsWith<IllegalArgumentException> {
            plan.request(mapOf("title" to ""))
        }
        assertFailsWith<IllegalArgumentException> {
            plan.request(mapOf("title" to "Example", "priority" to "high"))
        }
    }

    @Test
    fun `delete plan requires a fresh explicit confirmation`() {
        val resource = resource(fields = emptyList())
        val delete = action(
            id = "delete",
            intent = ActionIntent.delete,
            risk = ActionRisk.destructive,
            method = HttpMethod.DELETE,
            pathNames = listOf("recordId"),
            confirmation = true,
        )
        val plan = requireNotNull(
            nativeRecordActions(
                schema(resource, listOf(delete)),
                resource,
                NativeRecord("row-3", emptyMap()),
            ).delete,
        )

        assertFailsWith<IllegalArgumentException> {
            plan.request(confirmed = false)
        }
        assertTrue(plan.request(confirmed = true).confirmed)
    }

    @Test
    fun `inline completion with confirmation requirement fails closed`() {
        val resource = resource(
            fields = listOf(field("done", "Done", FieldKind.boolean)),
        )
        val completion = action(
            id = "complete",
            intent = ActionIntent.update,
            method = HttpMethod.PATCH,
            pathNames = listOf("recordId"),
            bodyNames = listOf("done"),
            confirmation = true,
        )

        assertNull(
            nativeRecordActions(
                schema(resource, listOf(completion)),
                resource,
                NativeRecord("row-4", mapOf("done" to "false")),
            ).completion,
        )
    }

    @Test
    fun `conflicting binding provenance and overlapping channels fail closed`() {
        val resource = resource(
            fields = listOf(
                field("containerId", "Container", FieldKind.string),
                field("title", "Title", FieldKind.string),
            ),
        )
        val create = action(
            id = "create",
            intent = ActionIntent.create,
            method = HttpMethod.POST,
            pathNames = listOf("containerId"),
            bodyNames = listOf("title"),
        )
        val record = NativeRecord(
            id = "row-5",
            values = mapOf("containerId" to "response-parent", "title" to "Example"),
            bindingContext = mapOf("containerId" to "request-parent"),
        )

        assertEquals(
            null,
            nativeRecordActions(
                schema(resource, listOf(create)),
                resource,
                record,
                navigationContext = mapOf("containerId" to "different-parent"),
            ).create,
        )

        val overlapping = create.copy(
            binding = create.binding.copy(
                bodyFieldNames = listOf("containerId", "title"),
            ),
        )
        assertNull(
            nativeRecordActions(
                schema(resource, listOf(overlapping)),
                resource,
                navigationContext = mapOf("containerId" to "request-parent"),
            ).create,
        )

        val aliasConflict = record.copy(
            bindingContext = mapOf("container_id" to "request-parent"),
        )
        assertNull(
            nativeRecordActions(
                schema(resource, listOf(create)),
                resource,
                aliasConflict,
                navigationContext = mapOf("containerId" to "different-parent"),
            ).create,
        )

        val invalidProvenance = nativeRecordActions(
            schema(resource, listOf(create)),
            resource,
            record.copy(actionBindingProvenanceValid = false),
        )
        assertNull(invalidProvenance.create)
        assertNull(invalidProvenance.edit)
        assertNull(invalidProvenance.delete)
        assertNull(invalidProvenance.completion)
    }

    @Test
    fun `irregular plural resource identity binds canonical record id`() {
        val resource = ResourceSpec(
            id = "entries",
            name = "Entries",
            confidence = Confidence.verified,
            fields = listOf(field("title", "Title", FieldKind.string)),
        )
        val edit = ActionSpec(
            id = "edit-entry",
            label = "Edit entry",
            resourceId = resource.id,
            binding = ApiBinding(
                method = HttpMethod.PATCH,
                path = "/records/{entryId}",
                operationId = "edit-entry",
                pathParameterNames = listOf("entryId"),
                requiredPathParameterNames = listOf("entryId"),
                bodyFieldNames = listOf("title"),
                bodyContentType = "application/json",
            ),
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            confidence = Confidence.verified,
        )

        val plan = requireNotNull(
            nativeRecordActions(
                schema(resource, listOf(edit)),
                resource,
                NativeRecord("entry-7", mapOf("title" to "Before")),
            ).edit,
        )

        assertEquals(
            mapOf("entryId" to "entry-7", "title" to "After"),
            plan.request(mapOf("title" to "After")).values,
        )
    }

    @Test
    fun `unsafe canonical record ids withhold every path mutation`() {
        val resource = resource(
            fields = listOf(
                field("title", "Title", FieldKind.string),
                field("done", "Done", FieldKind.boolean),
            ),
        )
        val actions = listOf(
            action(
                id = "edit",
                intent = ActionIntent.update,
                method = HttpMethod.PATCH,
                pathNames = listOf("recordId"),
                bodyNames = listOf("title"),
            ).withRecordPath("edit"),
            action(
                id = "delete",
                intent = ActionIntent.delete,
                risk = ActionRisk.destructive,
                method = HttpMethod.DELETE,
                pathNames = listOf("recordId"),
                confirmation = true,
            ).withRecordPath("delete"),
            action(
                id = "complete",
                intent = ActionIntent.update,
                method = HttpMethod.PATCH,
                pathNames = listOf("recordId"),
                bodyNames = listOf("done"),
                requiredBodyNames = listOf("done"),
            ).withRecordPath("complete"),
            action(
                id = "archive",
                intent = ActionIntent.execute,
                effect = ActionEffect.archive,
                method = HttpMethod.POST,
                pathNames = listOf("recordId"),
            ).withRecordPath("archive"),
        )
        val schema = schema(resource, actions)

        listOf(
            "",
            "item/9",
            "item\\9",
            "item\n9",
            "x".repeat(257),
        ).forEach { unsafeId ->
            val plans = nativeRecordActions(
                schema = schema,
                resource = resource,
                record = NativeRecord(
                    id = unsafeId,
                    values = mapOf("title" to "Example", "done" to "false"),
                ),
            )

            assertNull(plans.edit, "Edit must reject unsafe canonical ID '$unsafeId'.")
            assertNull(plans.delete, "Delete must reject unsafe canonical ID '$unsafeId'.")
            assertNull(plans.completion, "Completion must reject unsafe canonical ID '$unsafeId'.")
            assertTrue(plans.commands.isEmpty(), "Commands must reject unsafe canonical ID '$unsafeId'.")
        }
    }

    @Test
    fun `record identity wins only for the selected child resource`() {
        val resource = resource(
            fields = listOf(
                field("id", "Protocol ID", FieldKind.string, readOnly = true),
                field("title", "Title", FieldKind.string),
            ),
        )
        val edit = action(
            id = "edit",
            intent = ActionIntent.update,
            method = HttpMethod.PATCH,
            pathNames = listOf("recordId"),
            bodyNames = listOf("title"),
        )
        val record = NativeRecord(
            id = "child-9",
            values = mapOf("id" to "display-id", "title" to "Before"),
            bindingContext = mapOf("id" to "parent-4"),
        )

        val plan = requireNotNull(
            nativeRecordActions(
                schema(resource, listOf(edit)),
                resource,
                record,
                navigationContext = mapOf("id" to "parent-4"),
            ).edit,
        )

        assertEquals(
            mapOf("recordId" to "child-9", "title" to "After"),
            plan.request(mapOf("title" to "After")).values,
        )
    }

    @Test
    fun `declared parent relationship is bound from navigation and never shown as user input`() {
        val resource = ResourceSpec(
            id = "entries",
            name = "Entries",
            confidence = Confidence.verified,
            fields = listOf(
                field("workspaceId", "Workspace", FieldKind.string),
                field("title", "Title", FieldKind.string),
            ),
        )
        val create = ActionSpec(
            id = "create-entry",
            label = "Create entry",
            resourceId = resource.id,
            binding = ApiBinding(
                method = HttpMethod.POST,
                path = "/entries",
                operationId = "create-entry",
                bodyFieldNames = listOf("workspaceId", "title"),
                requiredBodyFieldNames = listOf("workspaceId", "title"),
                bodyContentType = "application/json",
            ),
            intent = ActionIntent.create,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            confidence = Confidence.verified,
        )
        val schema = NativeAppSchema(
            schemaVersion = "1",
            app = AppIdentity("synthetic", "Synthetic", "1"),
            confidence = Confidence.verified,
            resources = listOf(resource),
            actions = listOf(create),
            relationships = listOf(
                ResourceRelationshipSpec(
                    parentResourceId = "workspaces",
                    childResourceId = "entries",
                    parentFieldId = "id",
                    childFieldId = "workspaceId",
                    confidence = Confidence.verified,
                ),
            ),
        )

        val plan = requireNotNull(
            nativeRecordActions(
                schema = schema,
                resource = resource,
                navigationContext = mapOf("workspaceId" to "workspace-4"),
            ).create,
        )

        assertEquals(listOf("title"), plan.fields.map(FieldSpec::id))
        assertEquals(
            mapOf("workspaceId" to "workspace-4", "title" to "First entry"),
            plan.request(mapOf("title" to "First entry")).values,
        )
        assertFailsWith<IllegalArgumentException> {
            plan.request(mapOf("workspaceId" to "other", "title" to "First entry"))
        }
    }

    @Test
    fun `high confidence inferred relationship stays editable and cannot silently bind a write`() {
        val resource = ResourceSpec(
            id = "entries",
            name = "Entries",
            confidence = Confidence.verified,
            fields = listOf(
                field("workspaceId", "Workspace", FieldKind.string),
                field("title", "Title", FieldKind.string),
            ),
        )
        val create = ActionSpec(
            id = "create-entry",
            label = "Create entry",
            resourceId = resource.id,
            binding = ApiBinding(
                method = HttpMethod.POST,
                path = "/entries",
                operationId = "create-entry",
                bodyFieldNames = listOf("workspaceId", "title"),
                requiredBodyFieldNames = listOf("workspaceId", "title"),
                bodyContentType = "application/json",
            ),
            intent = ActionIntent.create,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            confidence = Confidence.verified,
        )
        val schema = NativeAppSchema(
            schemaVersion = "1",
            app = AppIdentity("synthetic", "Synthetic", "1"),
            confidence = Confidence.verified,
            resources = listOf(resource),
            actions = listOf(create),
            relationships = listOf(
                ResourceRelationshipSpec(
                    parentResourceId = "workspaces",
                    childResourceId = "entries",
                    parentFieldId = "id",
                    childFieldId = "workspaceId",
                    confidence = Confidence.high,
                ),
            ),
        )

        val plan = requireNotNull(
            nativeRecordActions(
                schema = schema,
                resource = resource,
                navigationContext = mapOf("workspaceId" to "workspace-4"),
            ).create,
        )

        assertEquals(listOf("workspaceId", "title"), plan.fields.map(FieldSpec::id))
        assertEquals(
            mapOf("workspaceId" to "workspace-9", "title" to "First entry"),
            plan.request(mapOf("workspaceId" to "workspace-9", "title" to "First entry")).values,
        )
        assertFailsWith<IllegalArgumentException> {
            plan.request(mapOf("title" to "First entry"))
        }
    }

    @Test
    fun `editable relationship remains visible so a record can move to another parent`() {
        val resource = ResourceSpec(
            id = "entries",
            name = "Entries",
            confidence = Confidence.verified,
            fields = listOf(
                field("workspaceId", "Workspace", FieldKind.string),
                field("title", "Title", FieldKind.string),
            ),
        )
        val edit = ActionSpec(
            id = "edit-entry",
            label = "Edit entry",
            resourceId = resource.id,
            binding = ApiBinding(
                method = HttpMethod.PATCH,
                path = "/entries/{entryId}",
                operationId = "edit-entry",
                pathParameterNames = listOf("entryId"),
                requiredPathParameterNames = listOf("entryId"),
                bodyFieldNames = listOf("workspaceId", "title"),
                requiredBodyFieldNames = listOf("workspaceId", "title"),
                bodyContentType = "application/json",
            ),
            intent = ActionIntent.update,
            risk = ActionRisk.mutating,
            requiresConfirmation = false,
            confidence = Confidence.verified,
        )
        val schema = NativeAppSchema(
            schemaVersion = "1",
            app = AppIdentity("synthetic", "Synthetic", "1"),
            confidence = Confidence.verified,
            resources = listOf(resource),
            actions = listOf(edit),
            relationships = listOf(
                ResourceRelationshipSpec(
                    parentResourceId = "workspaces",
                    childResourceId = "entries",
                    parentFieldId = "id",
                    childFieldId = "workspaceId",
                    confidence = Confidence.verified,
                ),
            ),
        )
        val record = NativeRecord(
            id = "entry-8",
            values = mapOf("entryId" to "entry-8", "workspaceId" to "workspace-4", "title" to "First entry"),
            bindingContext = mapOf("entryId" to "entry-8"),
        )

        val plan = requireNotNull(
            nativeRecordActions(
                schema = schema,
                resource = resource,
                record = record,
                navigationContext = mapOf("workspaceId" to "workspace-4"),
            ).edit,
        )

        assertEquals(listOf("workspaceId", "title"), plan.fields.map(FieldSpec::id))
        assertEquals(
            mapOf("entryId" to "entry-8", "workspaceId" to "workspace-9", "title" to "Moved entry"),
            plan.request(mapOf("workspaceId" to "workspace-9", "title" to "Moved entry")).values,
        )
    }

    private fun schema(resource: ResourceSpec, actions: List<ActionSpec>) = NativeAppSchema(
        schemaVersion = "1",
        app = AppIdentity("synthetic", "Synthetic", "1"),
        confidence = Confidence.verified,
        resources = listOf(resource),
        actions = actions,
    )

    private fun resource(
        fields: List<FieldSpec>,
        id: String = "records",
    ) = ResourceSpec(
        id = id,
        name = id.replaceFirstChar(Char::uppercase),
        confidence = Confidence.verified,
        fields = fields,
    )

    private fun field(
        id: String,
        label: String,
        kind: FieldKind,
        readOnly: Boolean = false,
        enumValues: List<String>? = null,
    ) = FieldSpec(
        id = id,
        label = label,
        kind = kind,
        required = false,
        readOnly = readOnly,
        enumValues = enumValues,
    )

    private fun action(
        id: String,
        intent: ActionIntent,
        effect: ActionEffect = ActionEffect.unspecified,
        risk: ActionRisk = ActionRisk.mutating,
        method: HttpMethod,
        pathNames: List<String> = emptyList(),
        queryNames: List<String> = emptyList(),
        bodyNames: List<String> = emptyList(),
        requiredBodyNames: List<String> = emptyList(),
        confirmation: Boolean = false,
    ) = ActionSpec(
        id = id,
        label = id,
        resourceId = "records",
        binding = ApiBinding(
            method = method,
            path = "/api/records",
            operationId = id,
            pathParameterNames = pathNames,
            requiredPathParameterNames = pathNames,
            queryParameterNames = queryNames,
            bodyFieldNames = bodyNames,
            requiredBodyFieldNames = requiredBodyNames,
            bodyContentType = if (bodyNames.isEmpty()) null else "application/json",
        ),
        intent = intent,
        risk = risk,
        requiresConfirmation = confirmation,
        confidence = Confidence.verified,
        effect = effect,
    )

    private fun ActionSpec.withRecordPath(operation: String): ActionSpec = copy(
        binding = binding.copy(path = "/api/records/{recordId}/$operation"),
    )
}
