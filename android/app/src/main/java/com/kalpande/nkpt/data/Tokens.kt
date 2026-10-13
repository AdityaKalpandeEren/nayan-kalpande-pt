package com.kalpande.nkpt.data

import java.util.Base64

/** Reads the expiry (`exp`, epoch seconds) of an Upstox access token (a JWT) - nothing is sent anywhere. */
object Tokens {
    fun expiry(token: String): Long? = runCatching {
        val part = token.trim().split('.').getOrNull(1) ?: return null
        val json = String(Base64.getUrlDecoder().decode(part.padEnd((part.length + 3) / 4 * 4, '=')))
        Regex("\"exp\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLong()
    }.getOrNull()

    fun isExtended(token: String): Boolean = runCatching {
        val part = token.trim().split('.')[1]
        String(Base64.getUrlDecoder().decode(part.padEnd((part.length + 3) / 4 * 4, '='))).contains("\"isExtended\":true")
    }.getOrDefault(false)
}
