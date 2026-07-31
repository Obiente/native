package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionEffect
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_INTEGER_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_REPEATABLE_OBJECT_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputFieldSpec
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputRow
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputScalarKind
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputSpec
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ResourceRelationshipSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
            ).withRecordPath(""),
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
                "canEdit" to "true",
                "canDelete" to "true",
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
            ).withRecordPath(""),
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
    fun `presentation-only observed fields cannot become mutation authority`() {
        val canonicalResource = resource(
            fields = listOf(
                field("id", "ID", FieldKind.string, readOnly = true),
                field("title", "Title", FieldKind.string),
            ),
        )
        val presentationResource = canonicalResource.copy(
            fields = canonicalResource.fields +
                field("canEdit", "Can edit", FieldKind.boolean, readOnly = true),
        )
        val edit = action(
            id = "change",
            intent = ActionIntent.update,
            method = HttpMethod.PATCH,
            pathNames = listOf("recordId"),
            bodyNames = listOf("title"),
        )
        val record = NativeRecord(
            id = "item-9",
            values = mapOf("id" to "item-9", "title" to "Prepare room"),
        )

        val observedOnly = nativeRecordActions(
            schema = schema(canonicalResource, listOf(edit)),
            resource = presentationResource,
            record = record,
        )

        assertNull(
            observedOnly.edit,
            "Neither endpoint existence nor a renderer-local field may authorize mutation.",
        )

        val declaredResource = canonicalResource.copy(fields = presentationResource.fields)
        val declaredMissing = nativeRecordActions(
            schema = schema(declaredResource, listOf(edit)),
            resource = presentationResource,
            record = record,
        )
        val declaredAllowed = nativeRecordActions(
            schema = schema(declaredResource, listOf(edit)),
            resource = presentationResource,
            record = record.copy(values = record.values + ("canEdit" to "true")),
        )

        assertNull(
            declaredMissing.edit,
            "A contract-declared capability with missing evidence must continue to fail closed.",
        )
        assertTrue(declaredAllowed.edit != null)
    }

    @Test
    fun `partial record capability surfaces authorize only their declared mutation category`() {
        val fields = listOf(
            field("id", "ID", FieldKind.string, readOnly = true),
            field("title", "Title", FieldKind.string),
            field("done", "Done", FieldKind.boolean),
        )
        val edit = action(
            id = "change",
            intent = ActionIntent.update,
            method = HttpMethod.PATCH,
            pathNames = listOf("recordId"),
            bodyNames = listOf("title"),
        )
        val delete = action(
            id = "remove",
            intent = ActionIntent.delete,
            risk = ActionRisk.destructive,
            method = HttpMethod.DELETE,
            pathNames = listOf("recordId"),
            confirmation = true,
        ).withRecordPath("")
        val complete = action(
            id = "set-state",
            intent = ActionIntent.update,
            method = HttpMethod.PATCH,
            pathNames = listOf("recordId"),
            bodyNames = listOf("done"),
            requiredBodyNames = listOf("done"),
        )
        val archive = action(
            id = "archive",
            intent = ActionIntent.execute,
            effect = ActionEffect.archive,
            method = HttpMethod.POST,
            pathNames = listOf("recordId"),
        ).withRecordPath("archive")
        val actions = listOf(edit, delete, complete, archive)
        val baseRecord = NativeRecord(
            id = "item-9",
            values = mapOf("id" to "item-9", "title" to "Prepare room", "done" to "false"),
        )
        val deleteOnlyResource = resource(
            fields = fields + field("canDelete", "Can delete", FieldKind.boolean, readOnly = true),
        )
        val deleteOnly = nativeRecordActions(
            schema(deleteOnlyResource, actions),
            deleteOnlyResource,
            baseRecord.copy(values = baseRecord.values + ("canDelete" to "true")),
        )

        assertNull(deleteOnly.edit)
        assertNull(deleteOnly.completion)
        assertTrue(deleteOnly.commands.isEmpty())
        assertTrue(deleteOnly.delete != null)

        val editOnlyResource = resource(
            fields = fields + field("canEdit", "Can edit", FieldKind.boolean, readOnly = true),
        )
        val editOnly = nativeRecordActions(
            schema(editOnlyResource, actions),
            editOnlyResource,
            baseRecord.copy(values = baseRecord.values + ("canEdit" to "true")),
        )

        assertTrue(editOnly.edit != null)
        assertTrue(editOnly.completion != null)
        assertEquals(listOf(ActionEffect.archive), editOnly.commands.map { command -> command.effect })
        assertNull(editOnly.delete)
    }

    @Test
    fun `parent authority uniquely authorizes list note and photo deletion`() {
        listOf("lists", "notes", "photos").forEach { resourceId ->
            val identityName = resourceId.dropLast(1) + "Id"
            val child = resource(
                id = resourceId,
                fields = listOf(
                    field("id", "ID", FieldKind.integer, readOnly = true),
                    field("title", "Title", FieldKind.string),
                    field("canEdit", "Can edit", FieldKind.boolean, readOnly = true),
                ),
            )
            val parent = resource(
                id = "houses",
                fields = listOf(
                    field("id", "ID", FieldKind.integer, readOnly = true),
                    field("isAdmin", "Is admin", FieldKind.boolean, readOnly = true),
                    field("permissions", "Permissions", FieldKind.objectValue, readOnly = true),
                ),
            )
            val delete = action(
                id = "$resourceId-delete",
                intent = ActionIntent.delete,
                effect = ActionEffect.delete,
                risk = ActionRisk.destructive,
                method = HttpMethod.DELETE,
                pathNames = listOf("houseId", identityName),
                confirmation = true,
            ).let { action ->
                action.copy(
                    resourceId = child.id,
                    binding = action.binding.copy(
                        path = "/api/houses/{houseId}/$resourceId/{$identityName}",
                    ),
                )
            }
            val selected = NativeRecord(
                id = "23",
                values = mapOf("id" to "23", "title" to "Selected", "canEdit" to "true"),
            )
            val permissionId = "canDelete" + resourceId.replaceFirstChar(Char::uppercase)
            val parentRecord = NativeRecord(
                id = "4",
                values = mapOf("id" to "4", "isAdmin" to "false"),
                structuredValues = mapOf(
                    "permissions" to NativeStructuredValue.ObjectValue(
                        entries = listOf(
                            NativeStructuredEntry(
                                key = permissionId,
                                label = permissionId,
                                value = NativeStructuredValue.Scalar(
                                    value = "true",
                                    kind = NativeStructuredScalarKind.boolean,
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val nativeSchema = schema(child, listOf(delete)).copy(
                resources = listOf(child, parent),
            )
            val authority = NativeRecordAuthorityContext(parent, parentRecord)

            val plan = nativeRecordActions(
                schema = nativeSchema,
                resource = child,
                record = selected,
                navigationContext = mapOf("id" to "4"),
                authorityContext = authority,
            ).delete

            assertEquals(
                mapOf("houseId" to "4", identityName to "23"),
                requireNotNull(plan) { resourceId }.request(confirmed = true).values,
            )
            assertNull(
                nativeRecordActions(
                    schema = nativeSchema,
                    resource = child,
                    record = selected,
                    navigationContext = mapOf("id" to "4"),
                ).delete,
                "$resourceId canEdit must not authorize deletion without parent evidence.",
            )
        }
    }

    @Test
    fun `parent authority fails closed for false absent malformed and ambiguous permissions`() {
        val child = resource(
            id = "notes",
            fields = listOf(
                field("id", "ID", FieldKind.integer, readOnly = true),
                field("canEdit", "Can edit", FieldKind.boolean, readOnly = true),
            ),
        )
        val parent = resource(
            id = "houses",
            fields = listOf(
                field("id", "ID", FieldKind.integer, readOnly = true),
                field("isAdmin", "Is admin", FieldKind.boolean, readOnly = true),
                field("permissions", "Permissions", FieldKind.objectValue, readOnly = true),
            ),
        )
        val delete = action(
            id = "notes-delete",
            intent = ActionIntent.delete,
            effect = ActionEffect.delete,
            risk = ActionRisk.destructive,
            method = HttpMethod.DELETE,
            pathNames = listOf("houseId", "noteId"),
            confirmation = true,
        ).let { action ->
            action.copy(
                resourceId = child.id,
                binding = action.binding.copy(
                    path = "/api/houses/{houseId}/notes/{noteId}",
                ),
            )
        }
        val selected = NativeRecord(
            id = "23",
            values = mapOf("id" to "23", "canEdit" to "true"),
        )
        val nativeSchema = schema(child, listOf(delete)).copy(resources = listOf(child, parent))

        fun deleteWith(
            entries: List<NativeStructuredEntry>?,
            isAdmin: String = "false",
            omittedEntries: Int = 0,
        ) =
            nativeRecordActions(
                schema = nativeSchema,
                resource = child,
                record = selected,
                navigationContext = mapOf("id" to "4"),
                authorityContext = NativeRecordAuthorityContext(
                    parentResource = parent,
                    parentRecord = NativeRecord(
                        id = "4",
                        values = mapOf("id" to "4", "isAdmin" to isAdmin),
                        structuredValues = entries?.let { values ->
                            mapOf(
                                "permissions" to NativeStructuredValue.ObjectValue(
                                    entries = values,
                                    omittedEntries = omittedEntries,
                                ),
                            )
                        }.orEmpty(),
                    ),
                ),
            ).delete

        fun permission(
            id: String,
            value: String?,
            kind: NativeStructuredScalarKind = NativeStructuredScalarKind.boolean,
        ) = NativeStructuredEntry(
            key = id,
            label = id,
            value = NativeStructuredValue.Scalar(value, kind),
        )

        assertNull(deleteWith(listOf(permission("canDeleteNotes", "false"))))
        assertNull(deleteWith(listOf(permission("canEditNotes", "true"))))
        assertNull(
            deleteWith(
                listOf(
                    permission("canDeleteNotes", "true"),
                    permission("canRemoveNotes", "true"),
                ),
            ),
        )
        assertNull(
            deleteWith(
                listOf(permission("canDeleteNotes", "true", NativeStructuredScalarKind.string)),
            ),
        )
        assertNull(
            deleteWith(
                entries = listOf(permission("canDeleteNotes", "true")),
                omittedEntries = 1,
            ),
        )
        assertNull(deleteWith(entries = null))
        assertTrue(deleteWith(entries = emptyList(), isAdmin = "true") != null)
    }

    @Test
    fun `selected note command binds a proven parent alias from generic navigation id`() {
        val notes = resource(
            id = "notes",
            fields = listOf(
                field("id", "ID", FieldKind.integer, readOnly = true),
                field("deletedAt", "Deleted at", FieldKind.dateTime, readOnly = true),
            ),
        )
        val restore = action(
            id = "note-restore",
            intent = ActionIntent.execute,
            effect = ActionEffect.restore,
            method = HttpMethod.POST,
            pathNames = listOf("houseId", "noteId"),
        ).let { action ->
            action.copy(
                resourceId = notes.id,
                binding = action.binding.copy(
                    path = "/api/houses/{houseId}/notes/{noteId}/restore",
                ),
            )
        }
        val note = NativeRecord(
            id = "23",
            values = mapOf("id" to "23", "deletedAt" to "2026-07-30T12:00:00Z"),
        )

        val plan = nativeRecordActions(
            schema = schema(notes, listOf(restore)),
            resource = notes,
            record = note,
            navigationContext = mapOf("id" to "4"),
            authorityContext = affirmativeParentAuthority(),
        ).commands.single()

        assertEquals(
            mapOf("houseId" to "4", "noteId" to "23"),
            plan.request().values,
        )
    }

    @Test
    fun `sparse records cannot authorize replacement put edit forms`() {
        val resource = resource(
            fields = listOf(
                field("id", "ID", FieldKind.string, readOnly = true),
                field("title", "Title", FieldKind.string),
                field("description", "Description", FieldKind.longText),
                field("canEdit", "Can edit", FieldKind.boolean, readOnly = true),
            ),
        )
        val replace = action(
            id = "replace",
            intent = ActionIntent.update,
            method = HttpMethod.PUT,
            pathNames = listOf("recordId"),
            bodyNames = listOf("title", "description"),
            requiredBodyNames = listOf("title"),
        )
        val sparseRecord = NativeRecord(
            id = "item-9",
            values = mapOf(
                "id" to "item-9",
                "title" to "Visible title",
                "canEdit" to "true",
            ),
        )

        assertNull(
            nativeRecordActions(schema(resource, listOf(replace)), resource, sparseRecord).edit,
        )

        val patch = replace.copy(
            id = "patch",
            binding = replace.binding.copy(method = HttpMethod.PATCH, operationId = "patch"),
        )
        assertTrue(
            nativeRecordActions(schema(resource, listOf(patch)), resource, sparseRecord).edit != null,
        )

        val nullableRecord = sparseRecord.copy(
            values = sparseRecord.values + ("description" to null),
        )
        val completeRecord = sparseRecord.copy(
            values = sparseRecord.values + ("description" to "Authoritative description"),
        )
        assertNull(nativeRecordActions(schema(resource, listOf(replace)), resource, nullableRecord).edit)
        assertNull(nativeRecordActions(schema(resource, listOf(replace)), resource, completeRecord).edit)

        val unsafePlan = NativeRecordFormActionPlan(
            kind = NativeRecordFormActionKind.Edit,
            action = replace,
            fields = resource.fields.filter { field -> field.id in replace.binding.bodyFieldNames },
            initialValues = mapOf(
                "title" to "Visible title",
                "description" to "Authoritative description",
            ),
            bindingValues = mapOf("recordId" to "item-9"),
        )
        assertFailsWith<IllegalArgumentException> {
            unsafePlan.request(
                mapOf(
                    "title" to "Updated title",
                    "description" to "Updated description",
                ),
            )
        }
    }

    @Test
    fun `selected record mutations require affirmative capability or parent authority`() {
        val resource = resource(
            fields = listOf(
                field("id", "ID", FieldKind.string, readOnly = true),
                field("title", "Title", FieldKind.string),
            ),
        )
        val parent = resource(
            id = "spaces",
            fields = listOf(
                field("id", "ID", FieldKind.string, readOnly = true),
                field("isAdmin", "Is admin", FieldKind.boolean, readOnly = true),
            ),
        )
        val edit = action(
            id = "change",
            intent = ActionIntent.update,
            method = HttpMethod.PATCH,
            pathNames = listOf("recordId"),
            bodyNames = listOf("title"),
        )
        val archive = action(
            id = "archive",
            intent = ActionIntent.execute,
            effect = ActionEffect.archive,
            method = HttpMethod.POST,
            pathNames = listOf("recordId"),
        ).withRecordPath("archive")
        val delete = action(
            id = "remove",
            intent = ActionIntent.delete,
            risk = ActionRisk.destructive,
            method = HttpMethod.DELETE,
            pathNames = listOf("recordId"),
            confirmation = true,
        ).withRecordPath("")
        val actions = listOf(edit, archive, delete)
        val record = NativeRecord(
            id = "item-9",
            values = mapOf("id" to "item-9", "title" to "Selected"),
        )
        val nativeSchema = schema(resource, actions).copy(resources = listOf(resource, parent))

        val absent = nativeRecordActions(nativeSchema, resource, record)
        val unscoped = nativeRecordActions(
            schema = nativeSchema,
            resource = resource,
            record = record,
            authorityContext = NativeRecordAuthorityContext(
                parentResource = parent.copy(fields = parent.fields.take(1)),
                parentRecord = NativeRecord("space-4", mapOf("id" to "space-4")),
            ),
        )
        listOf(absent, unscoped).forEach { capabilities ->
            assertNull(capabilities.edit)
            assertNull(capabilities.delete)
            assertTrue(capabilities.commands.isEmpty())
        }

        val parentAllowed = nativeRecordActions(
            schema = nativeSchema,
            resource = resource,
            record = record,
            authorityContext = NativeRecordAuthorityContext(
                parentResource = parent,
                parentRecord = NativeRecord(
                    id = "space-4",
                    values = mapOf("id" to "space-4", "isAdmin" to "true"),
                ),
            ),
        )
        assertTrue(parentAllowed.edit != null)
        assertTrue(parentAllowed.delete != null)
        assertEquals(listOf(ActionEffect.archive), parentAllowed.commands.map { plan -> plan.effect })

        val capableResource = resource.copy(
            fields = resource.fields +
                field("canEdit", "Can edit", FieldKind.boolean, readOnly = true) +
                field("canDelete", "Can delete", FieldKind.boolean, readOnly = true),
        )
        val canonicalAllowed = nativeRecordActions(
            schema = schema(capableResource, actions),
            resource = capableResource,
            record = record.copy(
                values = record.values + mapOf("canEdit" to "true", "canDelete" to "true"),
            ),
        )
        assertTrue(canonicalAllowed.edit != null)
        assertTrue(canonicalAllowed.delete != null)
        assertEquals(listOf(ActionEffect.archive), canonicalAllowed.commands.map { plan -> plan.effect })

        val scopedEditResource = resource.copy(
            fields = resource.fields +
                field("writable", "Writable", FieldKind.boolean, readOnly = true) +
                field("canEdit", "Can edit", FieldKind.boolean, readOnly = true),
        )
        val scopedEditOnly = nativeRecordActions(
            schema = schema(scopedEditResource, actions),
            resource = scopedEditResource,
            record = record.copy(
                values = record.values + mapOf("writable" to "true", "canEdit" to "true"),
            ),
        )
        assertTrue(scopedEditOnly.edit != null)
        assertEquals(listOf(ActionEffect.archive), scopedEditOnly.commands.map { plan -> plan.effect })
        assertNull(
            scopedEditOnly.delete,
            "General writable evidence cannot fill an absent canDelete capability category.",
        )
    }

    @Test
    fun `record delete must consume the selected item identity`() {
        val resource = resource(
            id = "tasks",
            fields = listOf(field("id", "ID", FieldKind.string, readOnly = true)),
        )
        val collectionDelete = action(
            id = "delete-task-collection",
            intent = ActionIntent.delete,
            risk = ActionRisk.destructive,
            method = HttpMethod.DELETE,
            pathNames = listOf("projectId"),
            confirmation = true,
        ).let { action ->
            action.copy(
                resourceId = resource.id,
                binding = action.binding.copy(path = "/api/projects/{projectId}/tasks"),
            )
        }
        val itemDelete = collectionDelete.copy(
            id = "delete-task",
            binding = collectionDelete.binding.copy(
                path = "/api/projects/{projectId}/tasks/{taskId}",
                operationId = "delete-task",
                pathParameterNames = listOf("projectId", "taskId"),
                requiredPathParameterNames = listOf("projectId", "taskId"),
            ),
        )
        val record = NativeRecord(
            id = "task-9",
            values = mapOf("id" to "task-9"),
            bindingContext = mapOf("projectId" to "project-4"),
        )

        assertNull(
            nativeRecordActions(
                schema(resource, listOf(collectionDelete)),
                resource,
                record,
            ).delete,
        )
        assertEquals(
            mapOf("projectId" to "project-4", "taskId" to "task-9"),
            requireNotNull(
                nativeRecordActions(
                    schema = schema(resource, listOf(itemDelete)),
                    resource = resource,
                    record = record,
                    authorityContext = affirmativeParentAuthority(),
                ).delete,
            ).request(confirmed = true).values,
        )
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
            nativeRecordActions(
                schema = schema(resource, listOf(toggle)),
                resource = resource,
                record = incomplete,
                authorityContext = affirmativeParentAuthority(),
            ).completion,
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
            nativeRecordActions(
                schema = schema(resource, listOf(toggle)),
                resource = resource,
                record = completed,
                authorityContext = affirmativeParentAuthority(),
            ).completion,
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
                    authorityContext = affirmativeParentAuthority(),
                ).completion,
            ).kind,
        )
    }

    @Test
    fun `non-task boolean state aliases expose no completion mutation`() {
        listOf(
            "enabled" to "Status",
            "published" to "State",
            "available" to "Status",
        ).forEach { (stateFieldId, stateFieldLabel) ->
            val resource = resource(
                fields = listOf(
                    field("id", "ID", FieldKind.string, readOnly = true),
                    field("title", "Title", FieldKind.string),
                    field(stateFieldId, stateFieldLabel, FieldKind.boolean),
                ),
            )
            val setState = action(
                id = "set-$stateFieldId",
                intent = ActionIntent.update,
                method = HttpMethod.PATCH,
                pathNames = listOf("recordId"),
                bodyNames = listOf(stateFieldId),
                requiredBodyNames = listOf(stateFieldId),
            )
            val toggle = action(
                id = "toggle-$stateFieldId",
                intent = ActionIntent.execute,
                effect = ActionEffect.toggle,
                method = HttpMethod.POST,
                pathNames = listOf("recordId"),
            ).withRecordPath("toggle-$stateFieldId")
            val record = NativeRecord(
                id = "record-14",
                values = mapOf(
                    "id" to "record-14",
                    "title" to "Ordinary record",
                    stateFieldId to "true",
                ),
            )

            assertNull(
                nativeRecordActions(
                    schema(resource, listOf(setState, toggle)),
                    resource,
                    record,
                ).completion,
                "$stateFieldId must not acquire task completion semantics from a boolean state alias.",
            )
        }
    }

    @Test
    fun `record transition commands bind exact identities and declared values`() {
        val resource = resource(
            fields = listOf(
                field("id", "ID", FieldKind.string, readOnly = true),
                field("destinationId", "Destination", FieldKind.string),
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
            .withScalarBodySchema("destinationId", "string")
        val record = NativeRecord(
            id = "record-20",
            values = mapOf(
                "id" to "record-20",
                "destinationId" to "destination-4",
            ),
        )

        val capabilities = nativeRecordActions(
            schema = schema(resource, listOf(copy, archive)),
            resource = resource,
            record = record,
            authorityContext = affirmativeParentAuthority(),
        )

        assertEquals(
            listOf(ActionEffect.archive),
            capabilities.commands.map(NativeRecordCommandActionPlan::effect),
        )
        assertEquals(
            mapOf("recordId" to "record-20"),
            capabilities.commands.single().request().values,
        )
        assertEquals(
            listOf(ActionEffect.copy),
            capabilities.commandForms.map(NativeRecordCommandFormActionPlan::effect),
        )
        assertFalse(capabilities.commands.any(NativeRecordCommandActionPlan::requiresConfirmation))
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
                schema = schema(resource, listOf(permanentDelete)),
                resource = resource,
                record = record,
                authorityContext = affirmativeParentAuthority(),
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
                schema = schema,
                resource = resource,
                record = NativeRecord("record-active", mapOf("id" to "record-active")),
                authorityContext = affirmativeParentAuthority(),
            ).commands.map(NativeRecordCommandActionPlan::effect),
        )
        assertEquals(
            listOf(ActionEffect.unarchive),
            nativeRecordActions(
                schema = schema,
                resource = resource,
                record = NativeRecord(
                    "record-archived",
                    mapOf("id" to "record-archived", "archivedAt" to "2026-07-30T12:00:00Z"),
                ),
                authorityContext = affirmativeParentAuthority(),
            ).commands.map(NativeRecordCommandActionPlan::effect),
        )
        assertEquals(
            listOf(ActionEffect.restore, ActionEffect.permanentDelete),
            nativeRecordActions(
                schema = schema,
                resource = resource,
                record = NativeRecord(
                    "record-deleted",
                    mapOf("id" to "record-deleted", "deletedAt" to "2026-07-30T12:00:00Z"),
                ),
                authorityContext = affirmativeParentAuthority(),
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
                ).withRecordPath(""),
            ),
        )
        val record = NativeRecord(
            id = "item-12",
            values = mapOf("id" to "item-12", "title" to "Reachable item"),
        )
        val capabilities = nativeRecordActions(
            schema = schema,
            resource = resource,
            record = record,
            authorityContext = affirmativeParentAuthority(),
        )
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
    fun `body bearing record commands remain distinct from edit and bind exact context`() {
        val resource = resource(
            fields = listOf(
                field("id", "ID", FieldKind.string, readOnly = true),
                field("targetListId", "Destination", FieldKind.integer),
                field(
                    "roleIds",
                    "Roles",
                    FieldKind.integer,
                    format = DYNAMIC_INTEGER_ARRAY_FORMAT,
                ),
                field(
                    "visibility",
                    "Visibility",
                    FieldKind.enumeration,
                    enumValues = listOf("private", "shared"),
                ),
            ),
        )
        val copy = action(
            id = "copy-record",
            intent = ActionIntent.execute,
            effect = ActionEffect.copy,
            method = HttpMethod.POST,
            pathNames = listOf("containerId", "recordId"),
            bodyNames = listOf("targetListId"),
            requiredBodyNames = listOf("targetListId"),
        ).let { action ->
            action.copy(
                binding = action.binding.copy(
                    path = "/api/containers/{containerId}/records/{recordId}/copy",
                ),
            )
        }.withScalarBodySchema("targetListId", "integer")
        val assign = action(
            id = "set-record-roles",
            intent = ActionIntent.update,
            effect = ActionEffect.assign,
            method = HttpMethod.PUT,
            pathNames = listOf("containerId", "recordId"),
            bodyNames = listOf("roleIds"),
            requiredBodyNames = listOf("roleIds"),
        ).let { action ->
            action.copy(
                binding = action.binding.copy(
                    path = "/api/containers/{containerId}/records/{recordId}/roles",
                    bodySchema = Json.parseToJsonElement(
                        """
                        {
                          "type": "object",
                          "properties": {
                            "roleIds": {
                              "type": "array",
                              "format": "$DYNAMIC_INTEGER_ARRAY_FORMAT",
                              "items": { "type": "integer" }
                            }
                          },
                          "required": ["roleIds"]
                        }
                        """.trimIndent(),
                    ),
                ),
            )
        }
        val execute = action(
            id = "set-record-visibility",
            intent = ActionIntent.execute,
            effect = ActionEffect.execute,
            method = HttpMethod.POST,
            pathNames = listOf("containerId", "recordId"),
            bodyNames = listOf("visibility"),
            requiredBodyNames = listOf("visibility"),
        ).let { action ->
            action.copy(
                binding = action.binding.copy(
                    path = "/api/containers/{containerId}/records/{recordId}/visibility",
                ),
            )
        }.withScalarBodySchema(
            fieldId = "visibility",
            type = "string",
            enumValues = listOf("private", "shared"),
        )
        val record = NativeRecord(
            id = "record-9",
            values = mapOf(
                "id" to "record-9",
                "roleIds" to "[2,7]",
            ),
            bindingContext = mapOf("containerId" to "container-4"),
        )

        val plans = nativeRecordActions(
            schema = schema(resource, listOf(copy, assign, execute)),
            resource = resource,
            record = record,
            navigationContext = mapOf("containerId" to "container-4"),
            authorityContext = affirmativeParentAuthority(),
        )

        assertNull(plans.edit)
        assertEquals(
            listOf("copy-record", "set-record-roles", "set-record-visibility"),
            plans.commandForms.map { it.action.id },
        )
        val copyPlan = plans.commandForms.single { it.action.id == "copy-record" }
        assertEquals(listOf("targetListId"), copyPlan.fields.map(FieldSpec::id))
        assertTrue(copyPlan.initialValues.isEmpty())
        assertEquals(
            mapOf(
                "containerId" to "container-4",
                "recordId" to "record-9",
                "targetListId" to "12",
            ),
            copyPlan.request(mapOf("targetListId" to "12")).values,
        )
        val assignPlan = plans.commandForms.single { it.action.id == "set-record-roles" }
        assertEquals(mapOf("roleIds" to "[2,7]"), assignPlan.initialValues)
        assertEquals(
            mapOf(
                "containerId" to "container-4",
                "recordId" to "record-9",
                "roleIds" to "[3,8]",
            ),
            assignPlan.request(mapOf("roleIds" to "[3,8]")).values,
        )
        listOf("""["3"]""", "[3.5]", """{"roleIds":[3]}""").forEach { invalid ->
            assertFailsWith<IllegalArgumentException>(invalid) {
                assignPlan.request(mapOf("roleIds" to invalid))
            }
        }
        val executePlan = plans.commandForms.single { it.action.id == "set-record-visibility" }
        assertEquals(
            "shared",
            executePlan.request(mapOf("visibility" to "shared")).values["visibility"],
        )
        assertFailsWith<IllegalArgumentException> {
            executePlan.request(mapOf("visibility" to "public"))
        }
    }

    @Test
    fun `record command form encodes exact typed repeatable object rows`() {
        val structured = RepeatableObjectInputSpec(
            minimumItems = 1,
            maximumItems = 4,
            fields = listOf(
                RepeatableObjectInputFieldSpec(
                    id = "uid",
                    label = "Recipient",
                    kind = RepeatableObjectInputScalarKind.String,
                    required = true,
                    minimumLength = 1,
                ),
                RepeatableObjectInputFieldSpec(
                    id = "permission",
                    label = "Permission",
                    kind = RepeatableObjectInputScalarKind.Enumeration,
                    required = true,
                    enumValues = listOf("view", "edit"),
                ),
            ),
        )
        val resource = resource(
            fields = listOf(
                field("id", "ID", FieldKind.integer, readOnly = true),
                field(
                    id = "shares",
                    label = "Shares",
                    kind = FieldKind.objectValue,
                    format = DYNAMIC_REPEATABLE_OBJECT_ARRAY_FORMAT,
                    repeatableObjectInput = structured,
                ),
            ),
        )
        val assign = action(
            id = "replace-shares",
            intent = ActionIntent.update,
            effect = ActionEffect.update,
            method = HttpMethod.PUT,
            pathNames = listOf("recordId"),
            bodyNames = listOf("shares"),
            requiredBodyNames = listOf("shares"),
        ).withRecordPath("shares").let { action ->
            action.copy(
                resultRecoveryActionId = "read-shares",
                binding = action.binding.copy(
                    bodySchema = Json.parseToJsonElement(
                        """
                        {
                          "type":"object",
                          "required":["shares"],
                          "properties":{
                            "shares":{
                              "type":"array",
                              "format":"$DYNAMIC_REPEATABLE_OBJECT_ARRAY_FORMAT",
                              "minItems":1,
                              "maxItems":4,
                              "items":{
                                "type":"object",
                                "additionalProperties":false,
                                "required":["uid","permission"],
                                "properties":{
                                  "uid":{"type":"string","title":"Recipient","minLength":1},
                                  "permission":{
                                    "type":"string",
                                    "title":"Permission",
                                    "enum":["view","edit"]
                                  }
                                }
                              }
                            }
                          }
                        }
                        """.trimIndent(),
                    ),
                ),
            )
        }
        val record = NativeRecord(
            id = "7",
            values = mapOf("id" to "7"),
        )

        val plan = nativeRecordActions(
            schema = schema(resource, listOf(assign)),
            resource = resource,
            record = record,
            authorityContext = affirmativeParentAuthority(),
        ).commandForms.single()
        val request = plan.requestWithStructuredInput(
            scalarInputValues = emptyMap(),
            repeatableObjectValues = mapOf(
                "shares" to listOf(
                    RepeatableObjectInputRow(
                        mapOf("uid" to "alice", "permission" to "edit"),
                    ),
                ),
            ),
        )

        assertEquals(
            """[{"uid":"alice","permission":"edit"}]""",
            request.values["shares"],
        )
        assertFailsWith<IllegalArgumentException> {
            plan.requestWithStructuredInput(
                scalarInputValues = mapOf("shares" to "[]"),
                repeatableObjectValues = emptyMap(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            plan.requestWithStructuredInput(
                scalarInputValues = emptyMap(),
                repeatableObjectValues = mapOf(
                    "shares" to listOf(
                        RepeatableObjectInputRow(
                            mapOf("uid" to "alice", "permission" to "owner"),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `record command forms fail closed for unsafe ambiguous or unconfirmed actions`() {
        val resource = resource(
            fields = listOf(
                field("targetId", "Destination", FieldKind.string),
                field("payload", "Payload", FieldKind.objectValue),
            ),
        )
        val safe = action(
            id = "copy-record",
            intent = ActionIntent.execute,
            effect = ActionEffect.copy,
            method = HttpMethod.POST,
            pathNames = listOf("recordId"),
            bodyNames = listOf("targetId"),
            requiredBodyNames = listOf("targetId"),
        ).withRecordPath("copy")
            .withScalarBodySchema("targetId", "string")
        val record = NativeRecord("record-4", emptyMap())

        fun commandForms(action: ActionSpec, target: ResourceSpec = resource) =
            nativeRecordActions(
                schema = schema(target, listOf(action)),
                resource = target,
                record = record,
                authorityContext = affirmativeParentAuthority(),
            ).commandForms

        assertEquals(listOf("copy-record"), commandForms(safe).map { it.action.id })
        assertTrue(
            commandForms(
                safe.withScalarBodySchema("targetId", "integer"),
            ).isEmpty(),
        )
        val bounded = safe.withScalarBodySchema(
            fieldId = "targetId",
            type = "string",
            minimumLength = 3,
            maximumLength = 12,
        )
        val boundedPlan = commandForms(bounded).single()
        assertFailsWith<IllegalArgumentException> {
            boundedPlan.request(mapOf("targetId" to "x"))
        }
        assertEquals(
            "record-8",
            boundedPlan.request(mapOf("targetId" to "record-8")).values["targetId"],
        )
        assertTrue(
            nativeRecordActions(
                schema(resource, listOf(safe, safe.copy(id = "copy-record-again"))),
                resource,
                record,
            ).commandForms.isEmpty(),
        )
        assertTrue(
            commandForms(
                safe.copy(
                    binding = safe.binding.copy(
                        path = "/api/records/copy",
                    ),
                ),
            ).isEmpty(),
        )
        assertTrue(
            commandForms(
                safe.copy(
                    binding = safe.binding.copy(
                        path = "/api/records/{recordId}/{undeclaredScope}/copy",
                    ),
                ),
            ).isEmpty(),
        )
        assertTrue(
            commandForms(
                safe.copy(
                    binding = safe.binding.copy(
                        bodyFieldNames = listOf("recordId", "targetId"),
                    ),
                ),
            ).isEmpty(),
        )
        assertTrue(commandForms(safe.copy(confidence = Confidence.medium)).isEmpty())
        assertTrue(
            commandForms(
                safe.copy(
                    binding = safe.binding.copy(allowsObservedBodyFields = true),
                ),
            ).isEmpty(),
        )
        val unsupported = safe.copy(
            binding = safe.binding.copy(
                bodyFieldNames = listOf("payload"),
                requiredBodyFieldNames = listOf("payload"),
            ),
        )
        assertTrue(commandForms(unsupported).isEmpty())

        val unconfirmedDestructive = safe.copy(
            risk = ActionRisk.destructive,
            requiresConfirmation = false,
        )
        assertTrue(commandForms(unconfirmedDestructive).isEmpty())
        val confirmedDestructive = unconfirmedDestructive.copy(requiresConfirmation = true)
        val destructivePlan = commandForms(confirmedDestructive).single()
        assertFailsWith<IllegalArgumentException> {
            destructivePlan.request(mapOf("targetId" to "record-8"))
        }
        assertTrue(
            destructivePlan.request(
                inputValues = mapOf("targetId" to "record-8"),
                confirmed = true,
            ).confirmed,
        )
    }

    @Test
    fun `related action resource can form a command only from exact selected record identity`() {
        val members = resource(
            id = "members",
            fields = listOf(field("id", "ID", FieldKind.integer, readOnly = true)),
        )
        val roles = resource(
            id = "roles",
            fields = listOf(
                field(
                    "roleIds",
                    "Roles",
                    FieldKind.integer,
                    format = DYNAMIC_INTEGER_ARRAY_FORMAT,
                ),
            ),
        )
        val setRoles = action(
            id = "set-member-roles",
            intent = ActionIntent.update,
            effect = ActionEffect.assign,
            method = HttpMethod.PUT,
            pathNames = listOf("houseId", "memberId"),
            bodyNames = listOf("roleIds"),
            requiredBodyNames = listOf("roleIds"),
        ).let { action ->
            action.copy(
                resourceId = roles.id,
                binding = action.binding.copy(
                    path = "/api/houses/{houseId}/members/{memberId}/roles",
                    bodySchema = Json.parseToJsonElement(
                        """
                        {
                          "type": "object",
                          "properties": {
                            "roleIds": {
                              "type": "array",
                              "format": "$DYNAMIC_INTEGER_ARRAY_FORMAT",
                              "items": { "type": "integer" }
                            }
                          },
                          "required": ["roleIds"]
                        }
                        """.trimIndent(),
                    ),
                ),
            )
        }
        val schema = NativeAppSchema(
            schemaVersion = "1",
            app = AppIdentity("synthetic", "Synthetic", "1"),
            confidence = Confidence.verified,
            resources = listOf(members, roles),
            actions = listOf(setRoles),
        )
        val member = NativeRecord(
            id = "23",
            values = mapOf("id" to "23"),
        )

        val plan = requireNotNull(
            nativeRecordActions(
                schema = schema,
                resource = members,
                record = member,
                navigationContext = mapOf("id" to "4"),
                authorityContext = affirmativeParentAuthority(),
            ).commandForms.singleOrNull(),
        )

        assertEquals(setRoles.id, plan.action.id)
        assertEquals(listOf("roleIds"), plan.fields.map(FieldSpec::id))
        assertEquals(
            mapOf("houseId" to "4", "memberId" to "23", "roleIds" to "[2,5]"),
            plan.request(mapOf("roleIds" to "[2,5]")).values,
        )
        assertTrue(
            nativeRecordActions(
                schema = schema.copy(
                    actions = listOf(
                        setRoles.copy(
                            binding = setRoles.binding.copy(
                                path = "/api/houses/{houseId}/members/current/roles",
                            ),
                        ),
                    ),
                ),
                resource = members,
                record = member,
                navigationContext = mapOf("id" to "4"),
                authorityContext = affirmativeParentAuthority(),
            ).commandForms.isEmpty(),
        )
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

        val plan = requireNotNull(
            nativeRecordActions(
                schema = schema(resource, listOf(update)),
                resource = resource,
                record = record,
                authorityContext = affirmativeParentAuthority(),
            ).completion,
        )

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
                schema = schema(oneWayResource, listOf(update)),
                resource = oneWayResource,
                record = record,
                authorityContext = affirmativeParentAuthority(),
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
                schema = schema(resource, listOf(putUpdate)),
                resource = resource,
                record = record,
                authorityContext = affirmativeParentAuthority(),
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
                    schema = schema(resource, listOf(patchUpdate)),
                    resource = resource,
                    record = record,
                    authorityContext = affirmativeParentAuthority(),
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
        ).withRecordPath("")
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
        ).withRecordPath("")
        val plan = requireNotNull(
            nativeRecordActions(
                schema = schema(resource, listOf(delete)),
                resource = resource,
                record = NativeRecord("row-3", emptyMap()),
                authorityContext = affirmativeParentAuthority(),
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
                schema = schema(resource, listOf(edit)),
                resource = resource,
                record = NativeRecord("entry-7", mapOf("title" to "Before")),
                authorityContext = affirmativeParentAuthority(),
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
                authorityContext = affirmativeParentAuthority(),
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
                authorityContext = affirmativeParentAuthority(),
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

    private fun affirmativeParentAuthority() = NativeRecordAuthorityContext(
        parentResource = resource(
            id = "parent-records",
            fields = listOf(
                field("isAdmin", "Is admin", FieldKind.boolean, readOnly = true),
            ),
        ),
        parentRecord = NativeRecord(
            id = "parent-1",
            values = mapOf("isAdmin" to "true"),
        ),
    )

    private fun field(
        id: String,
        label: String,
        kind: FieldKind,
        readOnly: Boolean = false,
        enumValues: List<String>? = null,
        format: String? = null,
        repeatableObjectInput: RepeatableObjectInputSpec? = null,
    ) = FieldSpec(
        id = id,
        label = label,
        kind = kind,
        required = false,
        readOnly = readOnly,
        format = format,
        enumValues = enumValues,
        repeatableObjectInput = repeatableObjectInput,
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
            path = buildString {
                append("/api/records")
                pathNames.forEach { name ->
                    append("/{")
                    append(name)
                    append('}')
                }
            },
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
        binding = binding.copy(
            path = "/api/records/{recordId}" +
                operation.takeIf(String::isNotBlank)?.let { suffix -> "/$suffix" }.orEmpty(),
        ),
    )

    private fun ActionSpec.withScalarBodySchema(
        fieldId: String,
        type: String,
        enumValues: List<String>? = null,
        minimumLength: Int? = null,
        maximumLength: Int? = null,
    ): ActionSpec {
        val property = JsonObject(
            buildMap {
                put("type", JsonPrimitive(type))
                enumValues?.let { values ->
                    put("enum", JsonArray(values.map(::JsonPrimitive)))
                }
                minimumLength?.let { value -> put("minLength", JsonPrimitive(value)) }
                maximumLength?.let { value -> put("maxLength", JsonPrimitive(value)) }
            },
        )
        return copy(
            binding = binding.copy(
                bodySchema = JsonObject(
                    buildMap {
                        put("type", JsonPrimitive("object"))
                        put(
                            "properties",
                            JsonObject(mapOf(fieldId to property)),
                        )
                        if (fieldId in binding.requiredBodyFieldNames) {
                            put("required", JsonArray(listOf(JsonPrimitive(fieldId))))
                        }
                    },
                ),
            ),
        )
    }
}
