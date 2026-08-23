package dev.obiente.nextcloudnative.app

data class SupportDiagnosticsReportsRefreshResult(
    val refreshedRecordIds: Set<String>,
    val result: SupportDiagnosticsConversationResult,
) {
    init {
        require(refreshedRecordIds.none(String::isBlank))
    }
}
