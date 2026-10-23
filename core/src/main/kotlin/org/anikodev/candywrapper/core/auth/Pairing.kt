package org.anikodev.candywrapper.core.auth

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Where to redeem a pairing code: the environment's origin and the one-time code. */
data class PairingTarget(val httpBase: String, val wsBase: String, val code: String, val label: String?) {
    override fun toString() = "PairingTarget($httpBase)"
}

class PairingInputError(message: String) : IllegalArgumentException(message)

/**
 * Reads what `npx t3 pair` prints or the user pastes (protocol.md 4.3):
 * `http://host:port/pair#token=CODE`, the legacy `?token=CODE`, the hosted
 * `https://app.t3.codes/pair?host=<origin>&label=Mac#token=CODE`, or a bare host with the code in
 * [manualCode]. A host without a scheme gets `https://`.
 */
fun parsePairingInput(input: String, manualCode: String? = null): PairingTarget {
    val raw = input.trim()
    if (raw.isEmpty()) throw PairingInputError("Paste the pairing link from `npx t3 pair`.")
    val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(raw)) raw else "https://$raw"
    // OkHttp parses only http and https; read ws and wss as those.
    val normalized = withScheme.replaceFirst(Regex("^ws(s?)://", RegexOption.IGNORE_CASE), "http$1://")
    val url = normalized.toHttpUrlOrNull() ?: throw PairingInputError("That is not a link or host name.")
    val fragmentToken = url.fragment?.split('&')?.firstNotNullOfOrNull { part ->
        part.split('=', limit = 2).takeIf { it.size == 2 && it[0] == "token" }?.get(1)
    }
    val code = (fragmentToken ?: url.queryParameter("token") ?: manualCode)?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw PairingInputError("The link has no pairing code. Run `npx t3 pair` again and paste the whole link.")
    val hosted = url.queryParameter("host")
    val origin = if (hosted != null) {
        val h = if (hosted.contains("://")) hosted else "https://$hosted"
        h.toHttpUrlOrNull() ?: throw PairingInputError("The link names a host that is not a valid address.")
    } else {
        url
    }
    val httpBase = originOf(origin)
    val wsBase = (if (origin.isHttps) "wss" else "ws") + httpBase.removePrefix(if (origin.isHttps) "https" else "http")
    return PairingTarget(httpBase, wsBase, code, url.queryParameter("label"))
}

/** `scheme://host[:port]/` with the default port dropped. */
fun originOf(url: HttpUrl): String {
    val host = if (url.host.contains(':')) "[${url.host}]" else url.host
    val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
    return "${url.scheme}://$host$port/"
}
