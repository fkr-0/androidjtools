package dev.androidjtools.remote.samplelib

import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Canonical form of a server address as entered by a person: trimmed, http(s) only, no credentials,
 * query or fragment, no trailing slash. A path prefix (reverse proxy) is preserved.
 */
fun normalizeBaseUrl(input: String): String {
    val trimmed = input.trim()
    require(trimmed.isNotEmpty()) { "Enter the server address." }
    val url = try {
        trimmed.toHttpUrl()
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Enter a full address such as https://samplelab.example:8017.")
    }
    require(url.username.isEmpty() && url.password.isEmpty()) {
        "Do not put a username or password in the server address."
    }
    return url.newBuilder().query(null).fragment(null).build().toString().trimEnd('/')
}
