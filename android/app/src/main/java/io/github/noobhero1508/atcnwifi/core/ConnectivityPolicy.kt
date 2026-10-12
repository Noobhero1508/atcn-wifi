package io.github.noobhero1508.atcnwifi.core

import java.net.URI
import java.util.Locale

/** Pure decisions kept separate from Android state so stale VALIDATED flags cannot imply success. */
internal object ConnectivityPolicy {
    fun stateForProbe(secureProbePassed: Boolean, gateway: GatewayEvidence): ConnectivityState = when {
        gateway.loginRequired -> ConnectivityState.LOGIN_REQUIRED
        secureProbePassed -> ConnectivityState.ONLINE
        gateway.loggedIn -> ConnectivityState.UNAVAILABLE
        else -> ConnectivityState.UNKNOWN
    }

    fun isValidInternetResponse(url: String, response: PortalHttpResponse): Boolean {
        val endpoint = runCatching { URI(url) }.getOrNull() ?: return false
        if (!endpoint.scheme.equals("https", true)) return false
        return when (endpoint.host?.lowercase(Locale.ROOT)) {
            "connectivitycheck.gstatic.com", "www.google.com" ->
                endpoint.path == "/generate_204" && response.status == 204 && response.body.isEmpty()
            "www.msftconnecttest.com" -> endpoint.path == "/connecttest.txt" &&
                response.status == 200 && response.text().trim() == "Microsoft Connect Test"
            else -> false
        }
    }

    fun gatewayEvidence(response: PortalHttpResponse, options: PortalOptions): GatewayEvidence {
        if (response.status !in 200..399) return GatewayEvidence()
        val html = response.text()
        val info = PortalParsers.extractWifiInfo(html)
        val awing = html.contains("awing-captive", true) ||
            html.contains(URI(options.awingBaseUrl).host.orEmpty(), true)
        val mikrotik = html.contains("mikrotik", true) &&
            (html.contains("/logout", true) || html.contains("/login", true) || html.contains("/status", true))
        val redirect = response.headers.entries.firstOrNull { it.key.equals("Location", true) }
            ?.value?.firstOrNull()
        val redirectedToPortal = redirect?.let { isKnownPortalLocation(it, response.url, options) } == true
        val redirectedToAuthenticatedPage = redirect?.let {
            val target = runCatching { URI(response.url).resolve(it) }.getOrNull()
            target?.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
                target?.host?.lowercase(Locale.ROOT) in setOf(
                    URI(options.gatewayLoginUrl).host.lowercase(Locale.ROOT),
                    URI(options.awingBaseUrl).host.lowercase(Locale.ROOT),
                ) && isAuthenticatedPath(target?.path)
        } == true
        val authenticatedPage = isAuthenticatedPath(runCatching { URI(response.url).path }.getOrNull())
        val recognizedInfo = info != null && PortalParsers.infoValue(info, "mac", "client_mac") != null &&
            PortalParsers.infoValue(info, "ip", "client_ip") != null
        val recognized = recognizedInfo || awing || mikrotik || redirectedToPortal || redirectedToAuthenticatedPage
        val loginForm = PortalParsers.chooseLoginForm(html, response.url)?.let {
            PortalParsers.isAllowedFormAction(it.action, options.allowedFormActionHosts)
        } == true
        val loggedIn = recognized && (redirectedToAuthenticatedPage || info?.let(PortalParsers::isLoggedIn) == true ||
            (mikrotik && html.contains("/logout", true) && !loginForm))
        val explicitLoggedOut = info != null && PortalParsers.infoValue(info, "logged_in", "logged-in")
            ?.toString()?.lowercase(Locale.ROOT) in setOf("no", "false", "0")
        val hasChallenge = info != null && PortalParsers.infoValue(info, "chap_id", "chap-id") != null &&
            PortalParsers.infoValue(info, "chap_challenge", "chap-challenge") != null
        return GatewayEvidence(
            recognized = recognized,
            loggedIn = loggedIn,
            loginRequired = recognized && !loggedIn && !authenticatedPage && !redirectedToAuthenticatedPage &&
                (explicitLoggedOut || hasChallenge || loginForm ||
                    (awing && response.status in 200..299) || redirectedToPortal),
            wifiInfoPresent = recognizedInfo,
        )
    }

    fun isKnownPortalLocation(location: String, fromUrl: String, options: PortalOptions): Boolean = runCatching {
        val target = URI(fromUrl).resolve(location)
        val path = target.path.orEmpty().trimEnd('/').lowercase(Locale.ROOT)
        val host = target.host?.lowercase(Locale.ROOT)
        val awingHost = URI(options.awingBaseUrl).host.lowercase(Locale.ROOT)
        val gatewayHost = URI(options.gatewayLoginUrl).host.lowercase(Locale.ROOT)
        target.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !isAuthenticatedPath(path) && when (host) {
            gatewayHost -> path == URI(options.gatewayLoginUrl).path.orEmpty().trimEnd('/').lowercase(Locale.ROOT)
            awingHost, "ex.login.net.vn" -> path in setOf("", "/login", "/connecting", "/home/login", "/home/connecting")
            else -> false
        }
    }.getOrDefault(false)

    private fun isAuthenticatedPath(path: String?): Boolean = path.orEmpty().trimEnd('/')
        .lowercase(Locale.ROOT) in setOf("/status", "/logout", "/success", "/home/success")
}

internal data class GatewayEvidence(
    val recognized: Boolean = false,
    val loggedIn: Boolean = false,
    val loginRequired: Boolean = false,
    val wifiInfoPresent: Boolean = false,
)
