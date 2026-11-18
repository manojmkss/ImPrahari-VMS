package com.imprahari.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

object Jwt {
    /** The token's `exp` (unix seconds), read without verifying it - only used
     *  to refresh shortly before the server would reject it. Null if unreadable. */
    fun expiresAt(token: String): Long? = try {
        val payload = token.split('.').getOrNull(1)
        if (payload == null) {
            null
        } else {
            val text = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
            Json.parseToJsonElement(text).jsonObject["exp"]?.jsonPrimitive?.longOrNull
        }
    } catch (e: IllegalArgumentException) {
        null
    }
}
