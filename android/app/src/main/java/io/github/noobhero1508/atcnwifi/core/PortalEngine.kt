package io.github.noobhero1508.atcnwifi.core

import android.net.Network
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Browser-free MikroTik/AWING client bound to one Android [Network].
 *
 * Create a new engine whenever ConnectivityManager reports a new Wi-Fi Network. The engine never
 * binds the whole process, so mobile-data traffic from the rest of the app is left untouched.
 */
class PortalEngine(
    private val network: Network,
    private val options: PortalOptions = PortalOptions(),
    private val logger: PortalLogger = PortalLogger.NONE,
) {
    suspend fun checkInternet(): InternetCheckResult = withContext(Dispatchers.IO) {
        checkInternetInternal(options.connectivityTimeoutMs)
    }

    suspend fun isTargetNetwork(): TargetNetworkResult = withContext(Dispatchers.IO) {
        inspectTargetNetwork()
    }

    suspend fun connect(): ConnectResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        withTimeoutOrNull(options.connectionBudgetMs.coerceAtLeast(1)) {
            connectWithinBudget(started)
        } ?: ConnectResult.Failed(
            listOf(failure(PortalStage.CONNECTIVITY, PortalFailureCode.ATTEMPT_TIMED_OUT,
                "Connection attempt reached its time limit")),
            elapsedMs(started),
        )
    }

    private suspend fun connectWithinBudget(started: Long): ConnectResult {
        val internet = checkInternetInternal(options.connectivityTimeoutMs)
        if (internet.online) return ConnectResult.AlreadyOnline(elapsedMs(started))
        if (internet.state != ConnectivityState.LOGIN_REQUIRED) {
            return ConnectResult.Failed(
                listOf(failure(PortalStage.CONNECTIVITY, PortalFailureCode.NETWORK_ERROR,
                    "Internet unavailable without confirmed captive-portal evidence; session preserved")),
                elapsedMs(started),
            )
        }

        log(PortalLogLevel.INFO, PortalStage.CONNECTIVITY, "Captive network detected; starting login")
        val recovery = RecoveryState(captiveConfirmed = true)
        val failures = mutableListOf<PortalFailure>()

        when (val direct = routerApiLogin(recovery, useAnalytics = false)) {
            is AttemptResult.Success -> return connected(direct.method, started)
            is AttemptResult.AlreadyOnline -> return ConnectResult.AlreadyOnline(elapsedMs(started))
            is AttemptResult.Failure -> failures += direct.failure
        }

        if (options.analyticsFallback) {
            delay(500)
            when (val compatible = routerApiLogin(recovery, useAnalytics = true)) {
                is AttemptResult.Success -> return connected(compatible.method, started)
                is AttemptResult.AlreadyOnline -> return ConnectResult.AlreadyOnline(elapsedMs(started))
                is AttemptResult.Failure -> failures += compatible.failure
            }
        }

        when (val fallback = genericHttpLogin()) {
            is AttemptResult.Success -> return connected(fallback.method, started)
            is AttemptResult.AlreadyOnline -> return ConnectResult.AlreadyOnline(elapsedMs(started))
            is AttemptResult.Failure -> failures += fallback.failure
        }

        log(PortalLogLevel.ERROR, PortalStage.CONNECTIVITY, "Login failed after all safe methods")
        return ConnectResult.Failed(failures.distinct(), elapsedMs(started))
    }

    suspend fun diagnose(): DiagnosticReport = withContext(Dispatchers.IO) {
        val errors = mutableListOf<PortalFailure>()
        val internet = checkInternetInternal(options.connectivityTimeoutMs)
        val target = inspectTargetNetwork()
        var fields = emptySet<String>()
        var maskedSerial: String? = null

        try {
            val session = NetworkHttpSession(network, options)
            val page = readRouterInfo(session)
            fields = SAFE_DIAGNOSTIC_FIELDS.filterTo(sortedSetOf()) {
                PortalParsers.infoValue(page.info.orEmpty(), it) != null
            }
            maskedSerial = PortalParsers.maskSerial(page.serial)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val failure = failure(
                PortalStage.DIAGNOSTIC,
                PortalFailureCode.ROUTER_UNREACHABLE,
                safeReason(error),
            )
            errors += failure
            log(PortalLogLevel.WARNING, failure.stage, failure.message)
        }

        DiagnosticReport(
            internet = internet,
            targetNetwork = target,
            gatewayEndpoint = safeEndpoint(options.gatewayLoginUrl),
            wifiInfoFields = fields,
            maskedSerial = maskedSerial,
            errors = errors,
        )
    }

    private suspend fun checkInternetInternal(timeoutMs: Int, classifyFailure: Boolean = true): InternetCheckResult {
        val started = System.nanoTime()
        var reason = "No connectivity probe succeeded"
        var state = ConnectivityState.UNKNOWN
        var result: InternetCheckResult? = null
        withTimeoutOrNull(options.connectivityBudgetMs.coerceAtLeast(1)) {
        // Reserve time for the local portal evidence when both HTTPS probes fail.
        val probeTimeout = minOf(timeoutMs, 1_500).coerceAtLeast(1)
        for (probe in options.connectivityCheckUrls.take(2)) {
            try {
                val response = NetworkHttpSession(network, options).execute(
                    PortalHttpRequest(
                        url = probe,
                        followRedirects = false,
                        connectTimeoutMs = probeTimeout,
                        readTimeoutMs = probeTimeout,
                        totalTimeoutMs = probeTimeout.toLong(),
                        maxBodyBytes = 128,
                    ),
                )
                if (ConnectivityPolicy.isValidInternetResponse(probe, response)) {
                    result = InternetCheckResult(true, safeEndpoint(probe), response.status)
                    // A walled garden may allow an OS HTTPS endpoint even after the session expires.
                    // A current, explicit gateway login requirement takes precedence over that probe.
                    try {
                        val gateway = NetworkHttpSession(network, options).execute(PortalHttpRequest(
                            options.gatewayLoginUrl, followRedirects = false,
                            totalTimeoutMs = 1_000, maxBodyBytes = 96_000,
                        ))
                        val evidence = ConnectivityPolicy.gatewayEvidence(gateway, options)
                        val confirmedState = ConnectivityPolicy.stateForProbe(true, evidence)
                        if (confirmedState == ConnectivityState.LOGIN_REQUIRED) {
                            result = result?.copy(online = false, state = confirmedState,
                                reason = "HTTPS endpoint is reachable but the gateway confirms required portal login")
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // An unknown gateway response does not override a validated HTTPS response.
                    }
                    return@withTimeoutOrNull
                }
                reason = if (response.status in 300..399) {
                    "Connectivity probe was redirected by a captive portal"
                } else {
                    "Connectivity probe returned HTTP ${response.status}"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reason = safeReason(error)
            }
        }
        if (!classifyFailure) return@withTimeoutOrNull
        try {
            val session = NetworkHttpSession(network, options)
            val gateway = session.execute(PortalHttpRequest(
                options.gatewayLoginUrl, followRedirects = false,
                totalTimeoutMs = 1_000, maxBodyBytes = 96_000,
            ))
            val evidence = ConnectivityPolicy.gatewayEvidence(gateway, options)
            when {
                evidence.loginRequired -> {
                    state = ConnectivityState.LOGIN_REQUIRED
                    reason = "Gateway confirms that this Wi-Fi requires portal login"
                }
                evidence.loggedIn -> {
                    state = ConnectivityState.UNAVAILABLE
                    reason = "Gateway session is active but HTTPS Internet checks failed"
                }
            }
            if (state == ConnectivityState.LOGIN_REQUIRED) return@withTimeoutOrNull
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // An unreachable gateway does not prevent checking for an explicit captive redirect.
        }
        try {
            // HTTP is evidence of a redirect only, never proof of Internet access.
            val captive = NetworkHttpSession(network, options).execute(PortalHttpRequest(
                "http://connectivitycheck.gstatic.com/generate_204", followRedirects = false,
                totalTimeoutMs = 800, maxBodyBytes = 16_000,
            ))
            if (ConnectivityPolicy.gatewayEvidence(captive, options).loginRequired) {
                state = ConnectivityState.LOGIN_REQUIRED
                reason = "HTTP request was intercepted by the recognized captive portal"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // DNS/timeouts or an unrelated HTTP response never imply a captive login requirement.
        }
        }
        return (result ?: InternetCheckResult(false, reason = redact(reason), state = state))
            .copy(elapsedMs = elapsedMs(started))
    }

    private suspend fun inspectTargetNetwork(): TargetNetworkResult {
        return try {
            val session = NetworkHttpSession(network, options)
            val response = session.execute(
                PortalHttpRequest(
                    url = options.gatewayLoginUrl,
                    followRedirects = false,
                    connectTimeoutMs = options.connectivityTimeoutMs,
                    readTimeoutMs = options.connectivityTimeoutMs,
                    totalTimeoutMs = options.connectivityTimeoutMs.toLong(),
                ),
            )
            val evidence = ConnectivityPolicy.gatewayEvidence(response, options)
            val reachable = response.status in 200..399
            TargetNetworkResult(
                matches = evidence.recognized,
                gatewayReachable = reachable,
                wifiInfoPresent = evidence.wifiInfoPresent,
                reason = if (!evidence.recognized) "Gateway response has no recognized AWING/MikroTik identity" else null,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            TargetNetworkResult(false, false, false, safeReason(error))
        }
    }

    private suspend fun routerApiLogin(recovery: RecoveryState, useAnalytics: Boolean): AttemptResult {
        val acquired = acquireRouterContext(recovery)
        when (acquired) {
            is RouterAcquisition.AlreadyOnline -> return AttemptResult.AlreadyOnline
            is RouterAcquisition.Failed -> return AttemptResult.Failure(acquired.failure)
            is RouterAcquisition.Ready -> Unit
        }
        val awingUrl = try {
            PortalProtocol.buildAwingLoginUrl(acquired.info, acquired.serial, options)
        } catch (error: IllegalArgumentException) {
            return AttemptResult.Failure(
                failure(
                    PortalStage.GATEWAY,
                    PortalFailureCode.ROUTER_CONTEXT_INCOMPLETE,
                    error.message ?: "wifiInfo is incomplete",
                ),
            )
        }

        val verified = verifyAwing(awingUrl)
        if (verified is AwingVerification.Failed) return AttemptResult.Failure(verified.failure)
        verified as AwingVerification.Ready

        if (!PortalParsers.isAllowedFormAction(verified.form.action, normalizedAllowedHosts())) {
            return AttemptResult.Failure(
                failure(
                    PortalStage.AWING,
                    PortalFailureCode.UNSAFE_FORM_ACTION,
                    "AWING returned a form action outside the allowlist",
                ),
            )
        }

        if (useAnalytics) {
            try {
                sendAnalyticsFallback(verified.session, awingUrl, verified.payload)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Analytics is compatibility-only. A valid fresh form remains more valuable.
                log(PortalLogLevel.WARNING, PortalStage.AWING, "Compatibility analytics failed; submitting fresh form")
            }
        }

        log(
            PortalLogLevel.INFO,
            PortalStage.AWING,
            if (useAnalytics) "Fresh AWING challenge acquired in compatibility mode" else "Fresh AWING challenge acquired",
        )
        return when (val submitted = submitGatewayForm(acquired.session, verified.form, awingUrl)) {
            is SubmitResult.Success -> AttemptResult.Success(
                if (useAnalytics) ConnectionMethod.COMPATIBILITY_ANALYTICS else ConnectionMethod.DIRECT_API,
            )
            is SubmitResult.Failed -> AttemptResult.Failure(submitted.failure)
        }
    }

    private suspend fun acquireRouterContext(recovery: RecoveryState): RouterAcquisition {
        var lastFailure = failure(
            PortalStage.GATEWAY,
            PortalFailureCode.ROUTER_CONTEXT_MISSING,
            "Gateway did not return wifiInfo",
        )
        var session = NetworkHttpSession(network, options)

        for (phase in 0..1) {
            for (attempt in 0 until options.routerReadAttempts.coerceAtLeast(1)) {
                try {
                    val page = readRouterInfo(session)
                    if (page.info != null) {
                        if (!PortalParsers.isLoggedIn(page.info)) {
                            return RouterAcquisition.Ready(session, page.info, page.serial)
                        }
                        lastFailure = failure(
                            PortalStage.GATEWAY,
                            PortalFailureCode.STUCK_SESSION,
                            "Gateway reports logged in but Internet is unavailable",
                        )
                        break
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    lastFailure = failure(
                        PortalStage.GATEWAY,
                        PortalFailureCode.ROUTER_UNREACHABLE,
                        safeReason(error),
                    )
                }
                if (attempt + 1 < options.routerReadAttempts) delay(350)
            }

            if (phase == 1 || recovery.logoutUsed) break
            val freshInternet = checkInternetInternal(1_500)
            if (freshInternet.online) return RouterAcquisition.AlreadyOnline
            if (!recovery.captiveConfirmed || freshInternet.state != ConnectivityState.LOGIN_REQUIRED) {
                lastFailure = failure(PortalStage.GATEWAY, PortalFailureCode.NETWORK_ERROR,
                    "Session refresh skipped because the current outage has no captive evidence")
                break
            }
            if (!gatewayStatusRecognized(session)) {
                lastFailure = failure(
                    PortalStage.GATEWAY,
                    PortalFailureCode.ROUTER_UNREACHABLE,
                    "Gateway status has no recognized MikroTik identity",
                )
                break
            }

            recovery.logoutUsed = true
            if (!logoutGateway(session)) {
                lastFailure = failure(
                    PortalStage.GATEWAY,
                    PortalFailureCode.STUCK_SESSION,
                    "Gateway rejected the guarded session refresh",
                )
                break
            }
            log(PortalLogLevel.WARNING, PortalStage.GATEWAY, "Stuck MikroTik session refreshed; waiting for a new challenge")
            delay(options.logoutWaitMs.coerceAtLeast(0))
            session = NetworkHttpSession(network, options)
        }
        return RouterAcquisition.Failed(lastFailure)
    }

    private suspend fun readRouterInfo(session: NetworkHttpSession): RouterPage {
        val response = session.execute(PortalHttpRequest(options.gatewayLoginUrl))
        if (response.status >= 500) throw IOException("Gateway returned HTTP ${response.status}")
        val html = response.text()
        val info = PortalParsers.extractWifiInfo(html)
        return RouterPage(
            info = info,
            serial = PortalParsers.discoverSerial(html, info, options.fallbackSerial),
        )
    }

    private suspend fun gatewayStatusRecognized(session: NetworkHttpSession): Boolean = try {
        val response = session.execute(
            PortalHttpRequest(
                options.gatewayStatusUrl,
                headers = mapOf("Referer" to options.gatewayLoginUrl),
                followRedirects = false,
            ),
        )
        ConnectivityPolicy.gatewayEvidence(response, options).recognized
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun logoutGateway(session: NetworkHttpSession): Boolean = try {
        val response = session.execute(
            PortalHttpRequest(
                options.gatewayLogoutUrl,
                headers = mapOf("Referer" to options.gatewayStatusUrl),
                followRedirects = false,
            ),
        )
        response.status in 200..399
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun verifyAwing(awingUrl: String): AwingVerification {
        val session = NetworkHttpSession(network, options)
        var lastFailure = failure(
            PortalStage.AWING,
            PortalFailureCode.AWING_VERIFY_FAILED,
            "AWING did not return contentAuthenForm",
        )
        val origin = PortalProtocol.origin(options.awingBaseUrl)

        repeat(options.awingRefreshAttempts.coerceAtLeast(1)) { attempt ->
            try {
                // A unique URL emulates F5 while the session preserves AWING cookies.
                session.execute(
                    PortalHttpRequest(
                        url = PortalProtocol.cacheBust(awingUrl),
                        headers = mapOf("Referer" to awingUrl),
                    ),
                )
                val response = session.execute(
                    PortalHttpRequest(
                        url = options.awingVerifyUrl,
                        method = "POST",
                        headers = mapOf(
                            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                            "X-Requested-With" to "XMLHttpRequest",
                            "Origin" to origin,
                            "Referer" to awingUrl,
                        ),
                        body = ByteArray(0),
                    ),
                )
                if (response.status !in 200..299) {
                    lastFailure = failure(
                        PortalStage.AWING,
                        PortalFailureCode.AWING_VERIFY_FAILED,
                        "AWING VerifyUrl returned HTTP ${response.status}",
                    )
                } else {
                    val payload = SimpleJson.obj(SimpleJson.parse(response.text()))
                        ?: throw IllegalArgumentException("VerifyUrl JSON root is not an object")
                    val captive = SimpleJson.obj(payload["captiveContext"])
                    val authHtml = SimpleJson.string(captive?.get("contentAuthenForm")).orEmpty()
                    val form = PortalParsers.chooseLoginForm(authHtml, awingUrl)
                    if (form != null) return AwingVerification.Ready(form, payload, session)
                    lastFailure = failure(
                        PortalStage.AWING,
                        PortalFailureCode.LOGIN_FORM_MISSING,
                        "AWING contentAuthenForm has no MikroTik login form",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastFailure = failure(
                    PortalStage.AWING,
                    PortalFailureCode.AWING_VERIFY_FAILED,
                    safeReason(error),
                )
            }
            if (attempt + 1 < options.awingRefreshAttempts) delay(250)
        }
        return AwingVerification.Failed(lastFailure)
    }

    private suspend fun sendAnalyticsFallback(
        session: NetworkHttpSession,
        awingUrl: String,
        verifyPayload: JsonValue.ObjectValue,
    ) {
        var dto = SimpleJson.obj(SimpleJson.parse(SimpleJson.stringify(verifyPayload)))
            ?: throw IllegalArgumentException("Invalid AWING DTO")
        SimpleJson.obj(dto["captiveContext"])?.set(
            "userContext",
            JsonValue.ObjectValue(linkedMapOf()),
        )
        val origin = PortalProtocol.origin(options.awingBaseUrl)

        for ((type, index) in listOf("View" to 1, "Click" to 0)) {
            val body = JsonValue.ObjectValue(
                linkedMapOf(
                    "captiveContextDTO" to dto,
                    "analyticType" to JsonValue.StringValue(type),
                    "viewIndex" to JsonValue.NumberValue(index.toString()),
                ),
            )
            val response = session.execute(
                PortalHttpRequest(
                    url = options.awingAnalyticsUrl,
                    method = "POST",
                    headers = mapOf(
                        "Content-Type" to "application/json; charset=UTF-8",
                        "Origin" to origin,
                        "Referer" to awingUrl,
                    ),
                    body = SimpleJson.stringify(body).toByteArray(StandardCharsets.UTF_8),
                ),
            )
            if (response.status !in 200..299) throw IOException("Analytics returned HTTP ${response.status}")
            if (type == "View") {
                val updated = runCatching { SimpleJson.obj(SimpleJson.parse(response.text())) }.getOrNull()
                if (SimpleJson.string(updated?.get("token")) != null) dto = updated!!
            }
        }
    }

    private suspend fun submitGatewayForm(
        routerSession: NetworkHttpSession,
        form: PortalForm,
        referer: String,
    ): SubmitResult {
        val response = try {
            routerSession.execute(
                PortalHttpRequest(
                    url = form.action,
                    method = "POST",
                    headers = mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "Referer" to referer,
                    ),
                    body = PortalEncoding.formEncode(form.fields),
                    followRedirects = false,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return SubmitResult.Failed(
                failure(PortalStage.SUBMIT, PortalFailureCode.NETWORK_ERROR, safeReason(error)),
            )
        }
        if (response.status !in 200..399) {
            return SubmitResult.Failed(
                failure(
                    PortalStage.SUBMIT,
                    PortalFailureCode.GATEWAY_REJECTED,
                    "Gateway returned HTTP ${response.status}",
                ),
            )
        }

        repeat(options.submitVerificationAttempts.coerceAtLeast(1)) { attempt ->
            if (checkInternetInternal(1_250, classifyFailure = false).online) return SubmitResult.Success
            if (attempt + 1 < options.submitVerificationAttempts) {
                delay(options.submitVerificationDelayMs.coerceAtLeast(0))
            }
        }
        return SubmitResult.Failed(
            failure(
                PortalStage.SUBMIT,
                PortalFailureCode.INTERNET_NOT_GRANTED,
                "Gateway accepted the form but Internet is still unavailable",
            ),
        )
    }

    private suspend fun genericHttpLogin(): AttemptResult {
        val session = NetworkHttpSession(network, options)
        var lastFailure = failure(
            PortalStage.FALLBACK,
            PortalFailureCode.LOGIN_FORM_MISSING,
            "No browser-free fallback form was found",
        )

        for (probe in options.genericProbeUrls) {
            try {
                val response = session.execute(
                    PortalHttpRequest(
                        url = if (URL(probe).host.equals("awifi.vn", true)) PortalProtocol.cacheBust(probe) else probe,
                    ),
                )
                val html = response.text()
                var form = PortalParsers.chooseLoginForm(html, response.url)
                val responseUri = URI(response.url)
                val awingHost = URI(options.awingBaseUrl).host
                val looksLikeAwing = html.contains("awing-captive.min.js", ignoreCase = true) ||
                    responseUri.host.equals(awingHost, ignoreCase = true)
                val awingPath = responseUri.path.orEmpty().lowercase(Locale.ROOT) in setOf("", "/", "/login", "/home/login")
                if (form == null && looksLikeAwing && awingPath) {
                    form = refreshGenericAwingForm(session, response.url, html)
                }
                if (form == null) continue
                if (!PortalParsers.isAllowedFormAction(form.action, normalizedAllowedHosts())) {
                    lastFailure = failure(
                        PortalStage.FALLBACK,
                        PortalFailureCode.UNSAFE_FORM_ACTION,
                        "Fallback form action is outside the allowlist",
                    )
                    continue
                }
                log(PortalLogLevel.WARNING, PortalStage.FALLBACK, "Using browser-free HTTP fallback")
                return when (val submitted = submitGatewayForm(session, form, response.url)) {
                    is SubmitResult.Success -> AttemptResult.Success(ConnectionMethod.GENERIC_HTTP)
                    is SubmitResult.Failed -> AttemptResult.Failure(submitted.failure)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastFailure = failure(PortalStage.FALLBACK, PortalFailureCode.NETWORK_ERROR, safeReason(error))
            }
        }
        return AttemptResult.Failure(lastFailure)
    }

    private suspend fun refreshGenericAwingForm(
        session: NetworkHttpSession,
        initialUrl: String,
        initialHtml: String,
    ): PortalForm? {
        var currentUrl = initialUrl
        var html = initialHtml
        repeat(options.awingRefreshAttempts.coerceAtLeast(1)) { attempt ->
            PortalParsers.chooseLoginForm(html, currentUrl)?.let { return it }
            loadDynamicLoginForm(session, currentUrl)?.let { return it }
            if (attempt + 1 < options.awingRefreshAttempts) {
                try {
                    val refreshed = session.execute(
                        PortalHttpRequest(PortalProtocol.cacheBust(currentUrl), headers = mapOf("Referer" to currentUrl)),
                    )
                    currentUrl = refreshed.url
                    html = refreshed.text()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A refresh failure leaves the previous shell available for the next attempt.
                }
                delay(200)
            }
        }
        return null
    }

    private suspend fun loadDynamicLoginForm(session: NetworkHttpSession, pageUrl: String): PortalForm? {
        val page = URI(pageUrl)
        val awingHost = URI(options.awingBaseUrl).host
        if (page.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") ||
            !page.host.equals(awingHost, ignoreCase = true)
        ) return null
        val verifyUrl = URI(page.scheme, page.userInfo, page.host, page.port, "/Home/VerifyUrl", null, null).toString()
        val response = session.execute(
            PortalHttpRequest(
                verifyUrl,
                method = "POST",
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to PortalProtocol.origin(pageUrl),
                    "Referer" to pageUrl,
                ),
                body = ByteArray(0),
            ),
        )
        val payload = SimpleJson.obj(SimpleJson.parse(response.text())) ?: return null
        val authHtml = SimpleJson.string(SimpleJson.obj(payload["captiveContext"])?.get("contentAuthenForm")) ?: return null
        return PortalParsers.chooseLoginForm(authHtml, pageUrl)
    }

    private fun connected(method: ConnectionMethod, started: Long): ConnectResult.Connected {
        val elapsed = elapsedMs(started)
        log(PortalLogLevel.INFO, PortalStage.CONNECTIVITY, "Internet access restored in ${elapsed} ms")
        return ConnectResult.Connected(method, elapsed)
    }

    private fun normalizedAllowedHosts(): Set<String> = options.allowedFormActionHosts
        .mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }

    private fun log(level: PortalLogLevel, stage: PortalStage, message: String) {
        runCatching { logger.log(PortalLogEvent(level, stage, redact(message))) }
    }

    private fun failure(stage: PortalStage, code: PortalFailureCode, message: String) =
        PortalFailure(stage, code, redact(message))

    private fun safeReason(error: Throwable): String = when (error) {
        is SocketTimeoutException -> "Network request timed out"
        is UnknownHostException -> "Host could not be resolved"
        is SecurityException -> "Android blocked the network request"
        is IllegalArgumentException -> error.message?.take(180) ?: "Unexpected response format"
        is IOException -> "Network I/O failed"
        else -> "Unexpected portal error"
    }

    private fun redact(message: String): String = message
        .replace(Regex("""https?://[^\s?#]+(?:\?[^\s]*)?""", RegexOption.IGNORE_CASE)) { match ->
            safeEndpoint(match.value)
        }
        .replace(Regex("""\b(?:[0-9A-F]{2}:){5}[0-9A-F]{2}\b""", RegexOption.IGNORE_CASE), "**:**:**:**:**:**")
        .replace(Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b"""), "x.x.x.x")
        .replace(Regex("""(?i)(chap_(?:id|challenge)|token|cookie|password)=([^\s&]+)"""), "$1=<redacted>")

    private fun safeEndpoint(url: String): String = runCatching {
        val uri = URI(url)
        URI(uri.scheme, null, uri.host, uri.port, uri.path.ifBlank { "/" }, null, null).toString()
    }.getOrDefault("<invalid endpoint>")

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    private data class RecoveryState(var logoutUsed: Boolean = false, val captiveConfirmed: Boolean = false)
    private data class RouterPage(val info: Map<String, Any?>?, val serial: String)

    private sealed class RouterAcquisition {
        data class Ready(
            val session: NetworkHttpSession,
            val info: Map<String, Any?>,
            val serial: String,
        ) : RouterAcquisition()
        data class Failed(val failure: PortalFailure) : RouterAcquisition()
        data object AlreadyOnline : RouterAcquisition()
    }

    private sealed class AwingVerification {
        data class Ready(
            val form: PortalForm,
            val payload: JsonValue.ObjectValue,
            val session: NetworkHttpSession,
        ) : AwingVerification()
        data class Failed(val failure: PortalFailure) : AwingVerification()
    }

    private sealed class SubmitResult {
        data object Success : SubmitResult()
        data class Failed(val failure: PortalFailure) : SubmitResult()
    }

    private sealed class AttemptResult {
        data class Success(val method: ConnectionMethod) : AttemptResult()
        data class Failure(val failure: PortalFailure) : AttemptResult()
        data object AlreadyOnline : AttemptResult()
    }

    private companion object {
        val SAFE_DIAGNOSTIC_FIELDS = setOf(
            "logged_in",
            "mac",
            "ip",
            "chap_id",
            "chap_challenge",
            "serial",
        )
    }
}

internal object PortalProtocol {
    fun buildAwingLoginUrl(
        info: Map<String, Any?>,
        serial: String,
        options: PortalOptions,
    ): String {
        val mac = PortalParsers.infoValue(info, "mac", "client_mac", "client-mac")?.toString().orEmpty()
        val ip = PortalParsers.infoValue(info, "ip", "client_ip", "client-ip")?.toString().orEmpty()
        val chapId = PortalParsers.infoValue(info, "chap_id", "chap-id")
        val challenge = PortalParsers.infoValue(info, "chap_challenge", "chap-challenge")
        val missing = listOf(
            "mac" to mac,
            "ip" to ip,
            "chap_id" to chapId,
            "chap_challenge" to challenge,
        ).filter { it.second == null || it.second.toString().isEmpty() }.map { it.first }
        require(missing.isEmpty()) { "wifiInfo is missing: ${missing.joinToString(", ")}" }

        val query = PortalEncoding.queryEncode(
            linkedMapOf(
                "serial" to (serial.ifBlank { options.fallbackSerial }),
                "client_mac" to mac,
                "client_ip" to ip,
                "userurl" to options.awingSuccessUrl,
                "login_url" to options.gatewayLoginUrl,
                "chap_id" to PortalParsers.toUrlOctal(chapId),
                "chap_challenge" to PortalParsers.toUrlOctal(challenge),
            ),
        )
        return "${options.awingLoginUrl}?$query"
    }

    fun cacheBust(url: String): String {
        val separator = if ('?' in url) '&' else '?'
        return "$url${separator}_wifi_retry=${System.nanoTime()}"
    }

    fun origin(url: String): String {
        val uri = URI(url)
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return "${uri.scheme}://${uri.host}$port"
    }
}
