package dev.obiente.nextcloudnative.app

private const val RECOGNIZE_DAV_PREFIX = "/remote.php/dav/recognize/"
private const val MEMORIES_COVER_PATH = "/index.php/apps/memories/api/clusters/{backend}/set-cover"
private const val RECOGNIZE_PERSON_PATH_TEMPLATE = "/remote.php/dav/recognize/{uid}/faces/{person}"
private const val RECOGNIZE_FACE_PATH_TEMPLATE =
    "/remote.php/dav/recognize/{uid}/faces/{person}/{faceNode}"
private const val RECOGNIZE_HEADER_NAME = "X-Recognize-Api-Key"
private const val MAX_PATH_SEGMENT_BYTES = 1_024
private const val DEFAULT_TOKEN_SAFETY_WINDOW_SECONDS = 30L

/**
 * Account scope for a mutation and its short-lived credential.
 *
 * Only the origin is retained. Every executable path below is relative, which keeps the eventual
 * transport from redirecting a Nextcloud session or Recognize token to a different origin.
 */
@ConsistentCopyVisibility
data class PeopleMutationAccountScope private constructor(
    val serverOrigin: String,
    val loginName: String,
) {
    init {
        require(serverOrigin.isCanonicalPeopleOrigin())
        require(loginName.isNotBlank() && loginName.none(Char::isISOControl))
    }

    companion object {
        fun from(session: NextcloudSession): PeopleMutationAccountScope {
            val origin = canonicalPeopleOrigin(session.serverUrl)
                ?: error("The Nextcloud server URL has no safe HTTP origin.")
            return PeopleMutationAccountScope(origin, session.loginName)
        }
    }
}

private fun canonicalPeopleOrigin(serverUrl: String): String? {
    val value = serverUrl.trim()
    val schemeEnd = value.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = value.substring(0, schemeEnd).lowercase()
    if (scheme !in setOf("http", "https")) return null
    val remainder = value.substring(schemeEnd + 3)
    val authority = remainder.substringBefore('/').substringBefore('?').substringBefore('#')
    if (
        authority.isBlank() || '@' in authority || '\\' in authority ||
        authority.any { it.isWhitespace() || it.isISOControl() }
    ) {
        return null
    }
    return "$scheme://${authority.lowercase()}"
}

private fun String.isCanonicalPeopleOrigin(): Boolean = canonicalPeopleOrigin(this) == this

enum class RecognizeTokenRefreshReason {
    Missing,
    AccountChanged,
    HeaderMismatch,
    ClockMovedBackwards,
    Expired,
    SafetyWindowReached,
}

sealed interface RecognizeTokenLifecycle {
    data class Usable(val remainingSeconds: Long) : RecognizeTokenLifecycle
    data class RefreshRequired(val reason: RecognizeTokenRefreshReason) : RecognizeTokenLifecycle
}

/**
 * In-memory lease for a bridge token. It is intentionally not serializable.
 *
 * The token object's own string representation is redacted. A lease is bound to one Nextcloud
 * account and is never usable during its final safety window.
 */
class RecognizeBridgeTokenLease private constructor(
    val token: RecognizeBridgeToken,
    val accountScope: PeopleMutationAccountScope,
    val acquiredAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
) {
    override fun toString(): String =
        "RecognizeBridgeTokenLease(token=$token, accountScope=$accountScope, " +
            "acquiredAtEpochSeconds=$acquiredAtEpochSeconds, expiresAtEpochSeconds=$expiresAtEpochSeconds)"

    fun lifecycle(
        expectedAccountScope: PeopleMutationAccountScope,
        nowEpochSeconds: Long,
        requestedSafetyWindowSeconds: Long = DEFAULT_TOKEN_SAFETY_WINDOW_SECONDS,
    ): RecognizeTokenLifecycle {
        require(requestedSafetyWindowSeconds >= 0L)
        if (accountScope != expectedAccountScope) {
            return RecognizeTokenLifecycle.RefreshRequired(RecognizeTokenRefreshReason.AccountChanged)
        }
        if (token.headerName != RECOGNIZE_HEADER_NAME) {
            return RecognizeTokenLifecycle.RefreshRequired(RecognizeTokenRefreshReason.HeaderMismatch)
        }
        if (nowEpochSeconds < acquiredAtEpochSeconds) {
            return RecognizeTokenLifecycle.RefreshRequired(RecognizeTokenRefreshReason.ClockMovedBackwards)
        }
        val remaining = expiresAtEpochSeconds - nowEpochSeconds
        if (remaining <= 0L) {
            return RecognizeTokenLifecycle.RefreshRequired(RecognizeTokenRefreshReason.Expired)
        }
        val adaptiveWindow = minOf(
            requestedSafetyWindowSeconds,
            maxOf(1L, token.expiresInSeconds / 10L),
        )
        return if (remaining <= adaptiveWindow) {
            RecognizeTokenLifecycle.RefreshRequired(RecognizeTokenRefreshReason.SafetyWindowReached)
        } else {
            RecognizeTokenLifecycle.Usable(remaining)
        }
    }

    companion object {
        fun create(
            token: RecognizeBridgeToken,
            accountScope: PeopleMutationAccountScope,
            acquiredAtEpochSeconds: Long,
        ): RecognizeBridgeTokenLease {
            require(acquiredAtEpochSeconds >= 0L)
            require(token.expiresInSeconds > 0L)
            require(acquiredAtEpochSeconds <= Long.MAX_VALUE - token.expiresInSeconds)
            return RecognizeBridgeTokenLease(
                token = token,
                accountScope = accountScope,
                acquiredAtEpochSeconds = acquiredAtEpochSeconds,
                expiresAtEpochSeconds = acquiredAtEpochSeconds + token.expiresInSeconds,
            )
        }
    }
}

sealed interface PeopleTransportAuthorization {
    data object NextcloudSession : PeopleTransportAuthorization

    class RecognizeBridgeToken internal constructor(
        internal val lease: RecognizeBridgeTokenLease,
    ) : PeopleTransportAuthorization {
        val headerName: String get() = lease.token.headerName
        val bridgeToken: dev.obiente.nextcloudnative.app.RecognizeBridgeToken get() = lease.token

        override fun toString(): String =
            "RecognizeBridgeToken(headerName=$headerName, value=[redacted])"
    }
}

/**
 * Transport-neutral, same-origin request. It cannot carry an absolute URL.
 *
 * Form fields remain structured so Android and desktop transports can serialize them identically.
 */
data class PeopleTransportRequest(
    val surface: PeopleMutationSurface,
    val method: PeopleMutationMethod,
    val relativePath: String,
    val authorization: PeopleTransportAuthorization,
    val destinationRelativePath: String? = null,
    val overwrite: Boolean? = null,
    val formFields: Map<String, String> = emptyMap(),
) {
    init {
        require(relativePath.isSafePeopleRelativePath())
        require((destinationRelativePath == null) == (method != PeopleMutationMethod.MOVE))
        require(destinationRelativePath == null || destinationRelativePath.isSafePeopleRelativePath())
        require(method == PeopleMutationMethod.MOVE || overwrite == null)
        require(method == PeopleMutationMethod.POST || formFields.isEmpty())
        when (surface) {
            PeopleMutationSurface.RecognizeDav -> {
                require(relativePath.startsWith(RECOGNIZE_DAV_PREFIX))
                require(destinationRelativePath == null || destinationRelativePath.startsWith(RECOGNIZE_DAV_PREFIX))
                require(authorization is PeopleTransportAuthorization.RecognizeBridgeToken)
                require(formFields.isEmpty())
            }

            PeopleMutationSurface.MemoriesApi -> {
                require(relativePath.startsWith("/index.php/apps/memories/api/"))
                require(destinationRelativePath == null)
                require(authorization == PeopleTransportAuthorization.NextcloudSession)
            }
        }
    }
}

fun buildPeopleMutationUrl(session: NextcloudSession, relativePath: String): String {
    PeopleMutationAccountScope.from(session)
    require(relativePath.isSafePeopleRelativePath())
    return session.serverUrl.trimEnd('/') + relativePath
}

fun PeopleTransportRequest.encodedFormBody(): ByteArray? = formFields
    .takeIf(Map<String, String>::isNotEmpty)
    ?.toSortedMap()
    ?.entries
    ?.joinToString("&") { (name, value) ->
        require(name.isNotBlank() && name.all { it.isLetterOrDigit() || it == '_' || it == '-' })
        "${encodePeoplePathSegment(name)}=${encodePeoplePathSegment(value)}"
    }
    ?.encodeToByteArray()

private fun String.isSafePeopleRelativePath(): Boolean =
    startsWith('/') && !startsWith("//") && "://" !in this && '?' !in this && '#' !in this &&
        '\\' !in this && none { it.isWhitespace() || it.isISOControl() } &&
        split('/').none { it == "." || it == ".." }

sealed interface PeopleExecutionPlanningResult {
    data class Disabled(val reason: String) : PeopleExecutionPlanningResult
    data class ConfirmationRequired(val confirmation: PeopleActionConfirmation) : PeopleExecutionPlanningResult
    data object FaceInventoryRequired : PeopleExecutionPlanningResult

    data class BridgeTokenRequired(
        val reason: RecognizeTokenRefreshReason,
    ) : PeopleExecutionPlanningResult

    data class ReconciliationRequired(
        val workflow: PersonMergeWorkflow,
        val reason: String,
    ) : PeopleExecutionPlanningResult

    data class Ready(
        val action: PeopleAction,
        val request: PeopleTransportRequest,
        /** In-flight snapshot to use when reducing the transport observation. */
        val mergeWorkflow: PersonMergeWorkflow? = null,
    ) : PeopleExecutionPlanningResult

    data class Completed(val workflow: PersonMergeWorkflow) : PeopleExecutionPlanningResult
    data class Invalid(val reason: String) : PeopleExecutionPlanningResult
}

/**
 * Evidence that both people were refreshed and the merge workflow was reconciled.
 *
 * The constructor is private so a caller cannot resume a rejected move by merely toggling a flag.
 */
class PeopleMergeReconciliation private constructor(
    val workflow: PersonMergeWorkflow,
) {
    companion object {
        internal fun create(workflow: PersonMergeWorkflow): PeopleMergeReconciliation =
            PeopleMergeReconciliation(workflow)
    }
}

fun reconcilePeopleMergeAfterRefresh(
    workflow: PersonMergeWorkflow,
    sourceDetectionIds: Set<Long>,
    targetDetectionIds: Set<Long>,
): PeopleMergeReconciliation = PeopleMergeReconciliation.create(
    workflow.reconcileAfterRefresh(sourceDetectionIds, targetDetectionIds),
)

/**
 * Produces at most one request. It never mints a token, executes HTTP, or retries a face move.
 */
fun planPeopleMutationExecution(
    plan: PeopleActionPlan,
    accountScope: PeopleMutationAccountScope,
    nowEpochSeconds: Long,
    confirmed: Boolean,
    tokenLease: RecognizeBridgeTokenLease? = null,
    mergeWorkflow: PersonMergeWorkflow? = null,
    mergeReconciliation: PeopleMergeReconciliation? = null,
): PeopleExecutionPlanningResult {
    if (!plan.enabled) return PeopleExecutionPlanningResult.Disabled(requireNotNull(plan.disabledReason))
    if (!confirmed) return PeopleExecutionPlanningResult.ConfirmationRequired(plan.confirmation)
    return runCatching {
        when (val binding = plan.binding) {
            is PeopleActionBinding.Single -> planSinglePeopleMutation(
                action = plan.action,
                mutation = binding.request,
                accountScope = accountScope,
                nowEpochSeconds = nowEpochSeconds,
                tokenLease = tokenLease,
            )

            is PeopleActionBinding.PerFaceMoveWorkflow -> {
                val workflow = mergeWorkflow ?: return PeopleExecutionPlanningResult.FaceInventoryRequired
                require(plan.action == PeopleAction.MergePerson)
                val inFlight = when (workflow.phase) {
                    PersonMergePhase.Completed -> return PeopleExecutionPlanningResult.Completed(workflow)
                    PersonMergePhase.NeedsManualReconciliation ->
                        return PeopleExecutionPlanningResult.ReconciliationRequired(
                            workflow,
                            "Refresh both people and reconcile every uncertain face before resuming.",
                        )

                    PersonMergePhase.Running -> {
                        if (workflow.activeMove != null) {
                            return PeopleExecutionPlanningResult.ReconciliationRequired(
                                workflow,
                                "A face was already in flight. Refresh before deciding whether to resume.",
                            )
                        }
                        workflow.beginNext()
                    }

                    PersonMergePhase.Ready -> workflow.start().beginNext()

                    PersonMergePhase.PausedForRefresh -> {
                        if (mergeReconciliation?.workflow != workflow) {
                            return PeopleExecutionPlanningResult.ReconciliationRequired(
                                workflow,
                                "Refresh both people before retrying a face the server rejected.",
                            )
                        }
                        workflow.start().beginNext()
                    }
                }
                val request = requireNotNull(inFlight.activeMove)
                when (
                    val single = planSinglePeopleMutation(
                        action = plan.action,
                        mutation = request,
                        accountScope = accountScope,
                        nowEpochSeconds = nowEpochSeconds,
                        tokenLease = tokenLease,
                    )
                ) {
                    is PeopleExecutionPlanningResult.Ready -> single.copy(mergeWorkflow = inFlight)
                    else -> single
                }
            }
        }
    }.getOrElse { failure ->
        PeopleExecutionPlanningResult.Invalid(failure.message ?: "The people mutation plan is invalid.")
    }
}

private fun planSinglePeopleMutation(
    action: PeopleAction,
    mutation: PeopleMutationRequest,
    accountScope: PeopleMutationAccountScope,
    nowEpochSeconds: Long,
    tokenLease: RecognizeBridgeTokenLease?,
): PeopleExecutionPlanningResult {
    val authorization = when (mutation.surface) {
        PeopleMutationSurface.MemoriesApi -> PeopleTransportAuthorization.NextcloudSession
        PeopleMutationSurface.RecognizeDav -> {
            val lease = tokenLease ?: return PeopleExecutionPlanningResult.BridgeTokenRequired(
                RecognizeTokenRefreshReason.Missing,
            )
            when (val lifecycle = lease.lifecycle(accountScope, nowEpochSeconds)) {
                is RecognizeTokenLifecycle.RefreshRequired ->
                    return PeopleExecutionPlanningResult.BridgeTokenRequired(lifecycle.reason)

                is RecognizeTokenLifecycle.Usable -> PeopleTransportAuthorization.RecognizeBridgeToken(lease)
            }
        }
    }
    return PeopleExecutionPlanningResult.Ready(
        action = action,
        request = mutation.toTransportRequest(authorization),
    )
}

private fun PeopleMutationRequest.toTransportRequest(
    authorization: PeopleTransportAuthorization,
): PeopleTransportRequest {
    require(pathTemplate in APPROVED_PEOPLE_MUTATION_TEMPLATES)
    require(destinationPathTemplate == null || destinationPathTemplate in APPROVED_PEOPLE_MUTATION_TEMPLATES)
    if (surface == PeopleMutationSurface.MemoriesApi) require(pathTemplate == MEMORIES_COVER_PATH)
    if (surface == PeopleMutationSurface.RecognizeDav) require(pathTemplate != MEMORIES_COVER_PATH)
    return PeopleTransportRequest(
        surface = surface,
        method = method,
        relativePath = expandPeoplePathTemplate(pathTemplate, pathValues),
        authorization = authorization,
        destinationRelativePath = destinationPathTemplate?.let { template ->
            expandPeoplePathTemplate(template, destinationPathValues)
        },
        overwrite = overwrite,
        formFields = bodyFields,
    )
}

internal fun expandPeoplePathTemplate(template: String, values: Map<String, String>): String {
    require(template in APPROVED_PEOPLE_MUTATION_TEMPLATES)
    val used = linkedSetOf<String>()
    val output = StringBuilder(template.length)
    var cursor = 0
    while (cursor < template.length) {
        val opening = template.indexOf('{', cursor)
        if (opening < 0) {
            output.append(template, cursor, template.length)
            break
        }
        output.append(template, cursor, opening)
        val closing = template.indexOf('}', opening + 1)
        require(closing > opening + 1) { "The people path template is malformed." }
        val name = template.substring(opening + 1, closing)
        require(name.all { it.isLetterOrDigit() || it == '_' })
        require(used.add(name)) { "The people path template repeats $name." }
        output.append(encodePeoplePathSegment(requireNotNull(values[name]) { "$name is missing." }))
        cursor = closing + 1
    }
    require(values.keys == used) { "The people path contains undeclared values." }
    return output.toString()
}

internal fun encodePeoplePathSegment(value: String): String {
    require(value.isNotBlank() && value.none(Char::isISOControl))
    require(value !in setOf(".", ".."))
    val bytes = value.encodeToByteArray()
    require(bytes.size <= MAX_PATH_SEGMENT_BYTES)
    return buildString(bytes.size) {
        bytes.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            val character = unsigned.toChar()
            if (
                character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
                character == '-' || character == '_' || character == '.' || character == '~'
            ) {
                append(character)
            } else {
                append('%')
                append(HEX_DIGITS[unsigned ushr 4])
                append(HEX_DIGITS[unsigned and 0x0f])
            }
        }
    }
}

private val APPROVED_PEOPLE_MUTATION_TEMPLATES = setOf(
    RECOGNIZE_PERSON_PATH_TEMPLATE,
    RECOGNIZE_FACE_PATH_TEMPLATE,
    MEMORIES_COVER_PATH,
)
private const val HEX_DIGITS = "0123456789ABCDEF"

sealed interface PeopleTransportObservation {
    data class Response(val status: Int) : PeopleTransportObservation {
        init {
            require(status in 100..599)
        }
    }

    data class NoResponse(val reason: String) : PeopleTransportObservation {
        init {
            require(reason.isNotBlank())
        }
    }
}

sealed interface PeopleMutationExecutionOutcome {
    val action: PeopleAction

    data class SingleSucceeded(
        override val action: PeopleAction,
        val refreshRequired: Boolean = true,
    ) : PeopleMutationExecutionOutcome

    data class SingleRejected(
        override val action: PeopleAction,
        val status: Int,
        val refreshRequired: Boolean = true,
    ) : PeopleMutationExecutionOutcome

    data class SingleOutcomeUnknown(
        override val action: PeopleAction,
        val reason: String,
        val refreshRequired: Boolean = true,
    ) : PeopleMutationExecutionOutcome

    data class MergeAdvanced(
        override val action: PeopleAction,
        val workflow: PersonMergeWorkflow,
    ) : PeopleMutationExecutionOutcome

    data class MergePaused(
        override val action: PeopleAction,
        val workflow: PersonMergeWorkflow,
        val outcomeUnknown: Boolean,
        val refreshRequired: Boolean = true,
    ) : PeopleMutationExecutionOutcome

    data class MergeCompleted(
        override val action: PeopleAction,
        val workflow: PersonMergeWorkflow,
        val refreshRequired: Boolean = true,
    ) : PeopleMutationExecutionOutcome
}

/**
 * Reduces one transport observation. A 4xx is a known rejection; redirects, 5xx responses, and a
 * missing response are conservative unknown outcomes that require refresh before any retry.
 */
fun reducePeopleMutationObservation(
    execution: PeopleExecutionPlanningResult.Ready,
    observation: PeopleTransportObservation,
): PeopleMutationExecutionOutcome {
    val successful = observation is PeopleTransportObservation.Response && observation.status in 200..299
    val rejectedStatus = (observation as? PeopleTransportObservation.Response)
        ?.status
        ?.takeIf { it in 400..499 }
    val unknownReason = when (observation) {
        is PeopleTransportObservation.NoResponse -> observation.reason
        is PeopleTransportObservation.Response -> "The server returned HTTP ${observation.status}."
    }
    val workflow = execution.mergeWorkflow
    if (workflow == null) {
        return when {
            successful -> PeopleMutationExecutionOutcome.SingleSucceeded(execution.action)
            rejectedStatus != null ->
                PeopleMutationExecutionOutcome.SingleRejected(execution.action, rejectedStatus)

            else -> PeopleMutationExecutionOutcome.SingleOutcomeUnknown(execution.action, unknownReason)
        }
    }
    return when {
        successful -> workflow.markActiveSucceeded().let { updated ->
            if (updated.phase == PersonMergePhase.Completed) {
                PeopleMutationExecutionOutcome.MergeCompleted(execution.action, updated)
            } else {
                PeopleMutationExecutionOutcome.MergeAdvanced(execution.action, updated)
            }
        }

        rejectedStatus != null -> PeopleMutationExecutionOutcome.MergePaused(
            action = execution.action,
            workflow = workflow.markActiveFailed("The server rejected this face move (HTTP $rejectedStatus).", false),
            outcomeUnknown = false,
        )

        else -> PeopleMutationExecutionOutcome.MergePaused(
            action = execution.action,
            workflow = workflow.markActiveFailed(unknownReason, true),
            outcomeUnknown = true,
        )
    }
}
