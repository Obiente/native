package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec

/** Connects a workspace header to the active renderer's durable create form. Not restorable. */
class NativeCollectionCreateControl {
    var action: ActionSpec? by mutableStateOf(null)
        private set
    private var openForm: (() -> Unit)? = null

    fun open(actionId: String) {
        if (action?.id == actionId) openForm?.invoke()
    }

    internal fun update(action: ActionSpec?, openForm: (() -> Unit)?) {
        this.openForm = openForm
        this.action = action.takeIf { openForm != null }
    }
}

@Composable
internal fun BindNativeCollectionCreateControl(
    control: NativeCollectionCreateControl?,
    action: ActionSpec?,
    openForm: (() -> Unit)?,
) {
    SideEffect { control?.update(action, openForm) }
    DisposableEffect(control) { onDispose { control?.update(null, null) } }
}

internal data class NativeCollectionCreatePlans(
    val form: NativeRecordFormActionPlan,
    val recovery: NativeCreateMutationRecoveryPlan,
)

internal fun nativeCollectionCreatePlans(
    schema: NativeAppSchema,
    readActionId: String,
    resource: ResourceSpec?,
    records: List<NativeRecord>,
    context: NativeDatasetContext,
    collectionComplete: Boolean,
    enabled: Boolean,
): NativeCollectionCreatePlans? {
    if (!enabled || resource == null) return null
    val create = nativeRecordActions(schema, resource, navigationContext = context.bindingValues).create ?: return null
    val read = schema.action(readActionId) ?: return null
    val recovery = nativeCreateMutationRecoveryPlan(
        schema, read, resource, create, records, context.bindingValues, collectionComplete,
    ) ?: nativeChoresInviteMutationRecoveryPlan(
        schema, read, resource, create, records, context.bindingValues, collectionComplete,
    ) ?: return null
    return NativeCollectionCreatePlans(create, recovery)
}
