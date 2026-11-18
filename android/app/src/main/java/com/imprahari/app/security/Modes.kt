package com.imprahari.app.security

/** The NVR's security modes (what alerts, and how loudly). */
object Modes {
    val ALL = listOf("home", "away", "night", "disarmed")

    fun label(mode: String): String = when (mode) {
        "home" -> "Home"
        "away" -> "Away"
        "night" -> "Night"
        "disarmed" -> "Off"
        else -> mode.replaceFirstChar { it.uppercase() }
    }

    /** What a mode means. What actually alerts in it is up to the NVR's alert
     *  rules (web settings → Security modes), so this stays general. */
    fun about(mode: String): String = when (mode) {
        "home" -> "Someone's home."
        "away" -> "Nobody's home."
        "night" -> "Everyone's asleep."
        "disarmed" -> "Not watching - recording carries on."
        else -> ""
    } + " Alerts follow the rules set for this mode."

    /** Armed = away or night (what the Quick Settings tile shows as "on"). */
    fun isArmed(mode: String): Boolean = mode == "away" || mode == "night"

    /** The tile's tap: armed -> Home; otherwise -> Away. */
    fun toggled(mode: String): String = if (isArmed(mode)) "home" else "away"
}
