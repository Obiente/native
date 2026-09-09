package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CancellationException

@Composable
internal fun DeckCardDraftRecoveryPreparationEffect(
    services: DeckCardDraftPlatformServices,
    session: NextcloudSession,
) {
    LaunchedEffect(services, session) {
        try {
            services.prepareDeckCardDraftRecovery(session)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // Migration is best effort and retries after the next authenticated app load.
        }
    }
}
