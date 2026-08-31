package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudPresentation
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

@Composable
internal fun MarketingFileShareScenario(
    scenario: MarketingCaptureScenario,
    fixture: MarketingFileShareFixture = nextcloudNativeMarketingFileShareFixture,
) {
    val capture = marketingFileShareCaptureState(scenario, fixture)
    val desktop = scenario.presentation == NextcloudPresentation.Desktop
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(if (desktop) NextcloudSpacing.XLarge else NextcloudSpacing.Medium),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = if (desktop) 760.dp else 560.dp)
                .fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(NextcloudSpacing.XLarge),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
            ) {
                Text(
                    text = "Share ${capture.dialog.file.name}",
                    style = MaterialTheme.typography.headlineSmall,
                )
                FileShareDialogContent(
                    state = capture.dialog,
                    onTargetChanged = { _ -> },
                    onAllowEditingChanged = { _ -> },
                    onDetailsChanged = { _ -> },
                    recipientPicker = { target ->
                        FileShareRecipientPickerContent(
                            target = target,
                            state = capture.recipientPicker,
                            enabled = !capture.dialog.running,
                            onQueryChanged = { _ -> },
                            onSelected = { _ -> },
                            onRetry = {},
                        )
                    },
                    existingShare = { share ->
                        ExistingFileShareSummary(
                            share = share,
                            running = false,
                            canCopy = false,
                            showManagementActions = true,
                            onCopy = {},
                            onPermissions = {},
                            onRevoke = {},
                        )
                    },
                    maximumHeight = when {
                        desktop -> 620.dp
                        scenario == MarketingCaptureScenario.FileShareLoadingMobile -> 420.dp
                        scenario == MarketingCaptureScenario.FileShareErrorMobile -> 470.dp
                        else -> 480.dp
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        NextcloudSpacing.Small,
                        Alignment.End,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FileShareDialogDismissAction(
                        state = capture.dialog,
                        onDismiss = {},
                    )
                    FileShareDialogConfirmAction(
                        state = capture.dialog,
                        onCreate = { _ -> },
                    )
                }
            }
        }
    }
}

internal data class MarketingFileShareCaptureState(
    val dialog: FileShareDialogUiState,
    val recipientPicker: FileShareRecipientPickerUiState,
)

internal fun marketingFileShareCaptureState(
    scenario: MarketingCaptureScenario,
    fixture: MarketingFileShareFixture = nextcloudNativeMarketingFileShareFixture,
): MarketingFileShareCaptureState {
    val target: FileShareTarget
    val existingShares: List<NextcloudFileShare>
    val picker: FileShareRecipientPickerUiState
    val capabilities: NextcloudFileSharingCapabilities
    when (scenario) {
        MarketingCaptureScenario.FileShareUserMobile -> {
            target = FileShareTarget.User
            existingShares = listOf(fixture.existingGroupShare)
            capabilities = fixture.capabilities
            picker = FileShareRecipientPickerUiState(
                query = "de",
                results = fixture.userResults,
            )
        }
        MarketingCaptureScenario.FileShareGroupDesktop -> {
            target = FileShareTarget.Group
            existingShares = listOf(fixture.existingUserShare)
            capabilities = fixture.capabilities
            picker = FileShareRecipientPickerUiState(
                query = "de",
                results = fixture.groupResults,
            )
        }
        MarketingCaptureScenario.FileShareLoadingMobile -> {
            target = FileShareTarget.User
            existingShares = listOf(fixture.existingGroupShare)
            capabilities = fixture.capabilities.copy(userExpirationSupported = false)
            picker = FileShareRecipientPickerUiState(
                query = "de",
                loading = true,
            )
        }
        MarketingCaptureScenario.FileShareErrorMobile -> {
            target = FileShareTarget.User
            existingShares = listOf(fixture.existingGroupShare)
            capabilities = fixture.capabilities.copy(userExpirationSupported = false)
            picker = FileShareRecipientPickerUiState(
                query = "de",
                error = "Could not search recipients. Check your connection and try again.",
            )
        }
        else -> error("${scenario.id} is not a file-share capture.")
    }
    return MarketingFileShareCaptureState(
        dialog = FileShareDialogUiState(
            file = fixture.file,
            capabilities = capabilities,
            existingShares = existingShares,
            target = target,
        ),
        recipientPicker = picker,
    )
}
