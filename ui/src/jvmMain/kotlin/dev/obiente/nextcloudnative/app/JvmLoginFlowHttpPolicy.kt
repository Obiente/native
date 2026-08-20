package dev.obiente.nextcloudnative.app

import org.json.JSONException
import org.json.JSONObject

const val LOGIN_FLOW_RESPONSE_MAX_BYTES = 64 * 1024

data class LoginChallengeHttpInterpretation(
    val challenge: LoginChallenge,
    val loginOriginMatchesEntered: Boolean,
    val pollOriginMatchesEntered: Boolean,
    val pollFallbackAvailable: Boolean,
)

data class LoginPollHttpInterpretation(
    val result: LoginPollResult,
    val resultOriginMatchesEntered: Boolean? = null,
    val approvedLoginName: String? = null,
    val approvedAppPassword: String? = null,
)

fun interpretLoginChallengeHttpResponse(
    status: Int,
    body: String,
    enteredServerUrl: String,
    transportSecurity: LoginTransportSecurity,
): LoginChallengeHttpInterpretation {
    check(status in 200..299) {
        "This server did not start Nextcloud Login Flow v2 (HTTP $status)."
    }
    val fields = try {
        val json = JSONObject(body)
        val poll = json.getJSONObject("poll")
        LoginChallengeFields(
            pollEndpoint = poll.getString("endpoint"),
            token = poll.getString("token"),
            loginUrl = json.getString("login"),
        )
    } catch (failure: JSONException) {
        throw IllegalStateException("The server returned an invalid Login Flow v2 challenge.", failure)
    }
    require(fields.token.isNotBlank()) { "The server returned an empty Login Flow v2 token." }
    val relationships = validateLoginEndpointRelationships(
        enteredServerUrl,
        fields.loginUrl,
        fields.pollEndpoint,
    )
    return LoginChallengeHttpInterpretation(
        challenge = LoginChallenge(
            enteredServerUrl = enteredServerUrl,
            pollEndpoint = fields.pollEndpoint,
            pollFallbackEndpoint = relationships.pollFallbackEndpoint,
            token = fields.token,
            loginUrl = fields.loginUrl,
            transportSecurity = transportSecurity,
        ),
        loginOriginMatchesEntered = relationships.loginOriginMatchesEntered,
        pollOriginMatchesEntered = relationships.pollOriginMatchesEntered,
        pollFallbackAvailable = relationships.pollFallbackEndpoint != null,
    )
}

fun interpretLoginPollHttpResponse(
    status: Int,
    body: String,
    challenge: LoginChallenge,
): LoginPollHttpInterpretation {
    if (status == 404) {
        return LoginPollHttpInterpretation(LoginPollResult.Pending)
    }
    if (status !in 200..299) {
        return LoginPollHttpInterpretation(
            LoginPollResult.FatalFailure(
                message = "Login approval failed (HTTP $status). Please try again.",
                code = "HTTP:$status",
            ),
        )
    }
    return try {
        val json = JSONObject(body)
        val resultServerUrl = normalizeServerUrl(json.getString("server"), challenge.transportSecurity)
        val loginName = json.getString("loginName")
        val appPassword = json.getString("appPassword")
        require(loginName.isNotEmpty()) { "The login name is empty." }
        require(appPassword.isNotEmpty()) { "The app password is empty." }
        LoginPollHttpInterpretation(
            result = LoginPollResult.Approved(NextcloudSession(resultServerUrl, loginName, appPassword)),
            resultOriginMatchesEntered = loginResultOriginMatchesEntered(
                challenge.enteredServerUrl,
                resultServerUrl,
            ),
            approvedLoginName = loginName,
            approvedAppPassword = appPassword,
        )
    } catch (_: Exception) {
        LoginPollHttpInterpretation(
            ambiguousLoginPollResponse("The server approved sign-in, but its one-time response was invalid."),
        )
    }
}

fun LoginChallengeHttpInterpretation.toStartedDiagnostic(): SupportDiagnosticEventDraft =
    SupportDiagnosticEventDraft(
        severity = SupportDiagnosticSeverity.Info,
        component = SupportDiagnosticComponent.Authentication,
        operation = "login.challenge",
        outcome = "started",
        fields = listOf(
            SupportDiagnosticFieldDraft(
                "login_origin_matches_entered",
                loginOriginMatchesEntered.toString(),
            ),
            SupportDiagnosticFieldDraft(
                "poll_origin_matches_entered",
                pollOriginMatchesEntered.toString(),
            ),
            SupportDiagnosticFieldDraft(
                "poll_fallback_available",
                pollFallbackAvailable.toString(),
            ),
            SupportDiagnosticFieldDraft(
                "transport_security",
                challenge.transportSecurity.diagnosticValue,
            ),
        ),
    )

fun loginPollEndpointFallbackDiagnostic(): SupportDiagnosticEventDraft =
    SupportDiagnosticEventDraft(
        severity = SupportDiagnosticSeverity.Info,
        component = SupportDiagnosticComponent.Authentication,
        operation = "login.poll",
        outcome = "endpoint-fallback",
        fields = listOf(
            SupportDiagnosticFieldDraft("safe_to_retry", "true"),
            SupportDiagnosticFieldDraft("exchange_started", "false"),
        ),
    )

fun LoginPollHttpInterpretation.toApprovedDiagnostic(
    usedFallback: Boolean,
): SupportDiagnosticEventDraft {
    check(result is LoginPollResult.Approved && resultOriginMatchesEntered != null) {
        "Only an approved login response has an approval diagnostic."
    }
    return SupportDiagnosticEventDraft(
        severity = SupportDiagnosticSeverity.Info,
        component = SupportDiagnosticComponent.Authentication,
        operation = "login.poll",
        outcome = "approved",
        fields = listOf(
            SupportDiagnosticFieldDraft(
                "result_origin_matches_entered",
                resultOriginMatchesEntered.toString(),
            ),
            SupportDiagnosticFieldDraft(
                "poll_fallback_used",
                usedFallback.toString(),
            ),
        ),
    )
}

private data class LoginChallengeFields(
    val pollEndpoint: String,
    val token: String,
    val loginUrl: String,
)
