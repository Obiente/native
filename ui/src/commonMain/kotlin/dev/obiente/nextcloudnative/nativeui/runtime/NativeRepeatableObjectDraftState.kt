package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputRow
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputSpec

/** Accepts only drafts that can be restored in full within the saved-state budget. */
internal class NativeRepeatableObjectDraftState(
    initial: Map<String, List<RepeatableObjectInputRow>>?,
    private val specs: Map<String, RepeatableObjectInputSpec>,
) {
    var editable by mutableStateOf(initial != null && encodeNativeRepeatableObjectDraft(initial, specs) != null)
        private set
    var values by mutableStateOf(if (editable) requireNotNull(initial) else emptyMap())
        private set
    var error by mutableStateOf<String?>(if (editable) null else
        "The existing structured value is invalid or too large to edit here. No changes have been made.")
        private set

    fun update(fieldId: String, rows: List<RepeatableObjectInputRow>) {
        if (editable) replace(values + (fieldId to rows))
    }

    fun replace(candidate: Map<String, List<RepeatableObjectInputRow>>) {
        if (encodeNativeRepeatableObjectDraft(candidate, specs) == null) {
            error = "This change exceeds the form's saved-draft limit. Your previous input is unchanged."
            return
        }
        values = candidate
        editable = true
        error = null
    }
}

internal fun nativeRepeatableObjectDraftStateSaver(specs: Map<String, RepeatableObjectInputSpec>) =
    Saver<NativeRepeatableObjectDraftState, List<String>>(
        save = { state -> if (state.editable) encodeNativeRepeatableObjectDraft(state.values, specs) else null },
        restore = { saved -> decodeNativeRepeatableObjectDraft(saved, specs)?.let {
            NativeRepeatableObjectDraftState(it, specs)
        } },
    )
