package dev.obiente.nextcloudnative.app

internal fun JvmSupportIntake.submittedRecordId(): String =
    (states().value as SupportDiagnosticsSubmissionState.Submitted).reports.single().recordId
