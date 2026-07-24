package dev.obiente.nextcloudnative.app

import kotlin.time.Clock

sealed interface PeopleMutationServiceResult {
    data class Planning(val result: PeopleExecutionPlanningResult) : PeopleMutationServiceResult

    data class TokenUnavailable(
        val reason: RecognizeBridgeTokenFailure?,
        val message: String,
    ) : PeopleMutationServiceResult

    data class Outcome(
        val outcome: PeopleMutationExecutionOutcome,
    ) : PeopleMutationServiceResult
}

/**
 * Capability-gated people mutation coordinator.
 *
 * The bridge token exists only in memory, is minted only after [confirmed] is true, and is passed
 * only to the dedicated same-origin people transport. No generic dynamic request can obtain it.
 */
class PeopleMutationService internal constructor(
    private val mintRequest: suspend (NextcloudSession, NextcloudApiRequest) -> NextcloudApiResponse,
    private val mutationRequest: suspend (NextcloudSession, PeopleTransportRequest) -> NextcloudApiResponse,
    private val nowEpochSeconds: () -> Long,
) {
    constructor(services: NextcloudPlatformServices) : this(
        mintRequest = services::executeNextcloudApi,
        mutationRequest = services::executePeopleMutation,
        nowEpochSeconds = { Clock.System.now().epochSeconds },
    )

    private var tokenLease: RecognizeBridgeTokenLease? = null

    suspend fun execute(
        session: NextcloudSession,
        bridgeDiscovery: RecognizeBridgeDiscovery,
        plan: PeopleActionPlan,
        confirmed: Boolean,
        mergeWorkflow: PersonMergeWorkflow? = null,
        mergeReconciliation: PeopleMergeReconciliation? = null,
    ): PeopleMutationServiceResult {
        val accountScope = runCatching { PeopleMutationAccountScope.from(session) }.getOrElse { failure ->
            return PeopleMutationServiceResult.Planning(
                PeopleExecutionPlanningResult.Invalid(
                    failure.message ?: "The connected account has no safe server origin.",
                ),
            )
        }
        val now = nowEpochSeconds()
        if (!confirmed) {
            return PeopleMutationServiceResult.Planning(
                planPeopleMutationExecution(
                    plan = plan,
                    accountScope = accountScope,
                    nowEpochSeconds = now,
                    confirmed = false,
                    mergeWorkflow = mergeWorkflow,
                    mergeReconciliation = mergeReconciliation,
                ),
            )
        }

        val needsRecognizeToken =
            PeopleActionAuthRequirement.ShortLivedRecognizeApiKey in plan.authRequirements
        val lease = if (needsRecognizeToken) {
            val capability = (bridgeDiscovery as? RecognizeBridgeDiscovery.Available)?.capability
                ?: return PeopleMutationServiceResult.TokenUnavailable(
                    reason = null,
                    message = "The Obiente bridge does not advertise Recognize write access.",
                )
            tokenLease
                ?.takeIf {
                    it.lifecycle(accountScope, now) is RecognizeTokenLifecycle.Usable
                }
                ?: when (val minted = mintToken(session, accountScope, capability, now)) {
                    is TokenMintResult.Ready -> minted.lease
                    is TokenMintResult.Unavailable -> return minted.failure
                }
        } else {
            null
        }

        val execution = planPeopleMutationExecution(
            plan = plan,
            accountScope = accountScope,
            nowEpochSeconds = now,
            confirmed = true,
            tokenLease = lease,
            mergeWorkflow = mergeWorkflow,
            mergeReconciliation = mergeReconciliation,
        )
        if (execution !is PeopleExecutionPlanningResult.Ready) {
            return PeopleMutationServiceResult.Planning(execution)
        }

        val observation = runCatching {
            mutationRequest(session, execution.request)
        }.fold(
            onSuccess = { response ->
                if (
                    response.status == 403 &&
                    execution.request.surface == PeopleMutationSurface.RecognizeDav
                ) {
                    tokenLease = null
                }
                PeopleTransportObservation.Response(response.status)
            },
            onFailure = { failure ->
                PeopleTransportObservation.NoResponse(
                    failure.message?.takeIf(String::isNotBlank)
                        ?: "The server response was not received.",
                )
            },
        )
        return PeopleMutationServiceResult.Outcome(
            reducePeopleMutationObservation(execution, observation),
        )
    }

    fun clearSensitiveState() {
        tokenLease = null
    }

    private suspend fun mintToken(
        session: NextcloudSession,
        accountScope: PeopleMutationAccountScope,
        capability: RecognizeBridgeCapability,
        nowEpochSeconds: Long,
    ): TokenMintResult {
        val request = when (
            val planned = planRecognizeBridgeTokenRequest(
                RecognizeBridgeDiscovery.Available(capability),
            )
        ) {
            is RecognizeBridgeTokenRequestPlan.Ready -> planned.request
            is RecognizeBridgeTokenRequestPlan.Unavailable ->
                return TokenMintResult.Unavailable(
                    PeopleMutationServiceResult.TokenUnavailable(
                        reason = null,
                        message = "The Recognize bridge token contract is unavailable.",
                    ),
                )
        }
        val response = runCatching { mintRequest(session, request) }.getOrElse {
            return TokenMintResult.Unavailable(
                PeopleMutationServiceResult.TokenUnavailable(
                    reason = null,
                    message = "The short-lived Recognize key could not be requested.",
                ),
            )
        }
        return when (val parsed = parseRecognizeBridgeTokenResponse(response, capability)) {
            is RecognizeBridgeTokenParseResult.Success -> {
                val lease = RecognizeBridgeTokenLease.create(
                    token = parsed.token,
                    accountScope = accountScope,
                    acquiredAtEpochSeconds = nowEpochSeconds,
                )
                tokenLease = lease
                TokenMintResult.Ready(lease)
            }

            is RecognizeBridgeTokenParseResult.Failure -> TokenMintResult.Unavailable(
                PeopleMutationServiceResult.TokenUnavailable(
                    reason = parsed.reason,
                    message = when (parsed.reason) {
                        RecognizeBridgeTokenFailure.RequestRejected ->
                            "The server rejected the short-lived Recognize key request."
                        RecognizeBridgeTokenFailure.InvalidResponse,
                        RecognizeBridgeTokenFailure.InvalidToken,
                        RecognizeBridgeTokenFailure.ContractMismatch,
                        -> "The server returned an unusable Recognize key response."
                    },
                ),
            )
        }
    }
}

private sealed interface TokenMintResult {
    data class Ready(val lease: RecognizeBridgeTokenLease) : TokenMintResult
    data class Unavailable(val failure: PeopleMutationServiceResult.TokenUnavailable) : TokenMintResult
}
