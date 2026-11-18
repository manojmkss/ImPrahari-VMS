package com.imprahari.app.push

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the NVR puts in a push (backend services/alerts.py `_maybe_send_push`):
 * title/body, a deep link, a signed snapshot path, and - for this app - the
 * camera and time, so a tap can play that moment.
 */
@Serializable
data class AlertPayload(
    val title: String = "ImPrahari",
    val body: String = "",
    val url: String? = null,
    val tag: String? = null,
    @SerialName("event_id") val eventId: Int? = null,
    val urgent: Boolean = false,
    val image: String? = null,
    @SerialName("camera_id") val cameraId: Int? = null,
    val at: Double? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Null for anything that isn't an ImPrahari alert. */
        fun parse(bytes: ByteArray): AlertPayload? = try {
            json.decodeFromString<AlertPayload>(bytes.toString(Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
