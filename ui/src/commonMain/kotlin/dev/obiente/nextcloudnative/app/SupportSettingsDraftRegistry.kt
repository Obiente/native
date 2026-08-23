package dev.obiente.nextcloudnative.app

internal object SupportSettingsDraftRegistry {
    private val states = linkedMapOf<String, SupportSettingsDraftState>()

    fun stateFor(session: NextcloudSession): SupportSettingsDraftState = stateFor(previewCacheDigest(session))

    internal fun stateFor(accountScopeDigest: String): SupportSettingsDraftState {
        require(accountScopeDigest.length == 64 && accountScopeDigest.all { it in '0'..'9' || it in 'a'..'f' })
        states.remove(accountScopeDigest)?.let { retained ->
            states[accountScopeDigest] = retained
            return retained
        }
        return SupportSettingsDraftState().also { created ->
            states[accountScopeDigest] = created
            if (states.size > MAX_RETAINED_SUPPORT_DRAFT_ACCOUNTS) states.remove(states.keys.first())
        }
    }
}

private const val MAX_RETAINED_SUPPORT_DRAFT_ACCOUNTS = 4
