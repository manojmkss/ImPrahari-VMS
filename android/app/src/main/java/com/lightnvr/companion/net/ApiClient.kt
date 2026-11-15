package com.lightnvr.companion.net

import com.lightnvr.companion.data.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
private data class LoginRequest(val username: String, val password: String)

@Serializable
private data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class Camera(
    val id: Int,
    val name: String,
    val codec: String = "",
    val status: String = "unknown",
    val enabled: Boolean = true,
    @SerialName("has_audio") val hasAudio: Boolean = false,
    /** Camera group (cameras watching the same place); null = none. */
    val area: String? = null,
) {
    val group: String? get() = area?.trim()?.takeIf { it.isNotEmpty() }
}

@Serializable
data class Me(val id: Int, val username: String, val role: String) {
    val isAdmin: Boolean get() = role == "admin"
}

@Serializable
data class SecurityMode(
    val mode: String,
    val source: String = "",
    @SerialName("manual_mode") val manualMode: String = "",
    val hold: Boolean = false,
    @SerialName("set_by") val setBy: String? = null,
    @SerialName("set_at") val setAt: String? = null,
    @SerialName("next_change_at") val nextChangeAt: String? = null,
)

@Serializable
private data class ModeRequest(val mode: String, val hold: Boolean, val source: String = "manual")

/** One person in a visit, from the after-visit review. */
@Serializable
data class VisitPerson(
    val kind: String, // known | probable | stranger | unseen
    val name: String? = null,
    @SerialName("camera_id") val cameraId: Int? = null,
    val photo: Boolean = false,
)

@Serializable
data class Visit(
    val id: Int,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String? = null,
    val status: String = "",
    @SerialName("camera_ids") val cameraIds: List<Int>? = null,
    @SerialName("areas_path") val areasPath: List<String>? = null,
    val counts: Map<String, Double>? = null,
    val identities: List<String>? = null,
    val summary: String? = null,
    @SerialName("snapshot_path") val snapshotPath: String? = null,
    val mode: String? = null,
    val people: List<VisitPerson>? = null,
    val strangers: Int = 0,
    val description: String? = null,
    @SerialName("review_status") val reviewStatus: String? = null,
    @SerialName("reviewed_at") val reviewedAt: String? = null,
)

/** Visits filters, as the API takes them (all optional, combined). */
data class VisitFilter(
    /** A name, or [STRANGERS]. */
    val person: String? = null,
    /** real | people | vehicles | animals | packages | other | motion; null = everything. */
    val what: String? = "real",
    val cameraId: Int? = null,
    val group: String? = null,
) {
    companion object {
        const val STRANGERS = "__strangers__"
    }
}

@Serializable
data class Timeline(
    @SerialName("server_now") val serverNow: Double,
    val spans: List<List<Double>> = emptyList(),
)

@Serializable
private data class FaceName(val name: String = "")

@Serializable
private data class VapidKey(@SerialName("public_key") val publicKey: String? = null)

@Serializable
private data class PushKeys(val p256dh: String, val auth: String)

@Serializable
private data class SubscribeRequest(val endpoint: String, val keys: PushKeys)

@Serializable
private data class UnsubscribeRequest(val endpoint: String)

/** What a camera's PTZ can do (admins only; null for a viewer). */
@Serializable
data class PtzInfo(
    val supported: Boolean,
    val reason: String? = null,
    @SerialName("pan_tilt") val panTilt: Boolean = false,
    val zoom: Boolean = false,
    val presets: Boolean = false,
    val home: Boolean = false,
    @SerialName("presets_list") val presetsList: List<PtzPreset> = emptyList(),
)

@Serializable
data class PtzPreset(val token: String, val name: String)

@Serializable
private data class PtzMoveRequest(val pan: Double, val tilt: Double, val zoom: Double)

@Serializable
private data class PtzPresetRequest(val name: String)

@Serializable
data class PushTestResult(val success: Boolean, val message: String = "")

sealed interface ProbeResult {
    data object Ok : ProbeResult
    data class UntrustedCertificate(val sha256: String, val subject: String) : ProbeResult
    data class Failed(val message: String) : ProbeResult
}

/** The refresh token is gone or rejected: the user must sign in again. */
class AuthExpiredException : IOException("Your session has expired - sign in again")

class ApiException(val code: Int, message: String) : IOException(message)

/** Talks to one LightNVR backend (the same /api the web app uses). */
class ApiClient(private val store: SessionStore) {
    val trust = PinnedTrust { store.certPinSha256 }

    val http: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(trust.sslSocketFactory, trust.trustManager)
        .hostnameVerifier(trust.hostnameVerifier)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // Keeps the media websocket from being silently dropped by NAT /
        // mobile networks while a camera has nothing new to send.
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()
    private val refreshMutex = Mutex()

    private fun base(): String = store.serverUrl ?: throw AuthExpiredException()

    /** Reach the NVR without credentials, and learn whether its certificate
     *  needs the user's go-ahead first. */
    suspend fun probe(baseUrl: String): ProbeResult = withContext(Dispatchers.IO) { probeWith(http, trust, baseUrl) }

    /**
     * Find the NVR among [candidates] (see [ServerUrl.candidates]): the first
     * address that answers - or whose certificate needs trusting - wins.
     * Returns that address with its result, or the most telling failure.
     */
    suspend fun findServer(candidates: List<String>): Pair<String, ProbeResult> =
        withContext(Dispatchers.IO) { findWith(http, trust, candidates) }

    suspend fun login(baseUrl: String, username: String, password: String) = withContext(Dispatchers.IO) {
        val body = json.encodeToString(LoginRequest(username, password)).toRequestBody(jsonType)
        val request = Request.Builder().url("$baseUrl/api/auth/login").post(body).build()
        http.newCall(request).execute().use { r ->
            when {
                r.code == 401 -> throw ApiException(401, "Wrong username or password")
                r.code == 429 -> throw ApiException(429, "Too many attempts - wait a minute and try again")
                !r.isSuccessful -> throw ApiException(r.code, "Sign-in failed (HTTP ${r.code})")
            }
            val tokens = json.decodeFromString<TokenResponse>(r.body?.string().orEmpty())
            store.serverUrl = baseUrl
            store.username = username
            store.saveTokens(tokens.accessToken, tokens.refreshToken)
        }
    }

    /**
     * A usable access token. Refreshed when it's within a minute of expiry (or
     * when [forceRefresh]), storing the rotated refresh token the server hands
     * back each time - reusing the old one would fail on the next refresh.
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String = refreshMutex.withLock {
        val current = store.accessToken
        val exp = current?.let { Jwt.expiresAt(it) }
        val now = System.currentTimeMillis() / 1000
        if (!forceRefresh && current != null && exp != null && exp - now > 60) return@withLock current

        val refresh = store.refreshToken ?: throw AuthExpiredException()
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(RefreshRequest(refresh)).toRequestBody(jsonType)
            val request = Request.Builder().url("${base()}/api/auth/refresh").post(body).build()
            http.newCall(request).execute().use { r ->
                if (r.code == 401 || r.code == 403) {
                    store.signOut()
                    throw AuthExpiredException()
                }
                if (!r.isSuccessful) throw ApiException(r.code, "Couldn't renew the session (HTTP ${r.code})")
                val tokens = json.decodeFromString<TokenResponse>(r.body?.string().orEmpty())
                store.saveTokens(tokens.accessToken, tokens.refreshToken)
                tokens.accessToken
            }
        }
    }

    /** A call with the access token; one refresh-and-retry on 401. [body]
     *  is JSON (ignored for GET). */
    private suspend fun authed(path: String, method: String = "GET", body: String? = null): Response {
        suspend fun call(token: String): Response = withContext(Dispatchers.IO) {
            val builder = Request.Builder().url("${base()}$path").header("Authorization", "Bearer $token")
            when {
                method == "DELETE" && body == null -> builder.delete()
                method != "GET" -> builder.method(method, (body ?: "{}").toRequestBody(jsonType))
            }
            http.newCall(builder.build()).execute()
        }
        val first = call(accessToken())
        if (first.code != 401) return first
        first.close()
        val second = call(accessToken(forceRefresh = true))
        if (second.code == 401) {
            second.close()
            store.signOut()
            throw AuthExpiredException()
        }
        return second
    }

    private suspend fun authedGet(path: String): Response = authed(path)

    /** A call whose JSON answer is decoded; a readable error otherwise. */
    private suspend fun <T> call(
        what: String,
        path: String,
        method: String = "GET",
        body: String? = null,
        decode: (String) -> T,
    ): T {
        val response = authed(path, method, body)
        return withContext(Dispatchers.IO) {
            response.use { r ->
                if (!r.isSuccessful) throw ApiException(r.code, errorMessage(what, r.code))
                decode(r.body?.string().orEmpty())
            }
        }
    }

    private suspend inline fun <reified T> getJson(what: String, path: String): T =
        call(what, path) { json.decodeFromString<T>(it) }

    private fun errorMessage(what: String, code: Int): String = when (code) {
        403 -> "$what: this account isn't allowed to do that"
        else -> "$what (HTTP $code)"
    }

    suspend fun cameras(): List<Camera> = getJson("Couldn't load cameras", "/api/cameras")

    suspend fun me(): Me = getJson("Couldn't load your account", "/api/auth/me")

    suspend fun mode(): SecurityMode = getJson("Couldn't load the security mode", "/api/security/mode")

    /** Admins only (the server answers 403 for a viewer account). */
    suspend fun setMode(mode: String, hold: Boolean): SecurityMode =
        call("Couldn't change the mode", "/api/security/mode", "PUT", json.encodeToString(ModeRequest(mode, hold))) {
            json.decodeFromString<SecurityMode>(it)
        }

    /** Visits that began in [startSec, endSec), newest first, filtered by the server. */
    suspend fun visits(startSec: Long, endSec: Long, filter: VisitFilter, limit: Int = 200): List<Visit> =
        getJson("Couldn't load visits", visitsPath(startSec, endSec, filter, limit))

    /** Names on the People page (admin only; empty for a viewer). */
    suspend fun peopleNames(): List<String> = try {
        getJson<List<FaceName>>("Couldn't load people", "/api/faces/people").map { it.name }.filter { it.isNotBlank() }
    } catch (e: ApiException) {
        emptyList()
    }

    /** Which parts of [start, end] (unix s) have footage, and the NVR's clock. */
    suspend fun timeline(cameraId: Int, start: Double, end: Double): Timeline =
        getJson("Couldn't load the timeline", "/api/cameras/$cameraId/timeline?start=$start&end=$end")

    suspend fun vapidPublicKey(): String? =
        getJson<VapidKey>("Couldn't get the alerts key", "/api/push/vapid-public-key").publicKey

    suspend fun subscribePush(endpoint: String, p256dh: String, auth: String) {
        val body = json.encodeToString(SubscribeRequest(endpoint, PushKeys(p256dh, auth)))
        call("Couldn't register for alerts", "/api/push/subscribe", "POST", body) { }
    }

    suspend fun unsubscribePush(endpoint: String) {
        val body = json.encodeToString(UnsubscribeRequest(endpoint))
        call("Couldn't unregister alerts", "/api/push/unsubscribe", "POST", body) { }
    }

    suspend fun testPush(): PushTestResult =
        call("Couldn't send a test alert", "/api/push/test", "POST", null) { json.decodeFromString<PushTestResult>(it) }

    /**
     * Bytes of an NVR image path ("/api/..."), sent with the session - or
     * without it for a signed public link (alert pictures). Null when it has
     * none (yet) or can't be reached.
     */
    suspend fun imageBytes(path: String, signed: Boolean = false): ByteArray? {
        val response = try {
            if (signed) {
                withContext(Dispatchers.IO) {
                    http.newCall(Request.Builder().url("${base()}$path").build()).execute()
                }
            } else {
                authedGet(path)
            }
        } catch (e: AuthExpiredException) {
            throw e
        } catch (e: IOException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null // not a valid URL
        }
        return withContext(Dispatchers.IO) {
            response.use { r -> if (r.isSuccessful) r.body?.bytes() else null }
        }
    }

    /** The camera's PTZ, or null when this account may not turn cameras (viewer). */
    suspend fun ptz(cameraId: Int): PtzInfo? = try {
        getJson("Couldn't load PTZ", "/api/cameras/$cameraId/ptz")
    } catch (e: ApiException) {
        if (e.code == 403) null else throw e
    }

    /** Hold to move: lasts under a second - send again while held. Speeds -1..1. */
    suspend fun ptzMove(cameraId: Int, pan: Double, tilt: Double, zoom: Double) {
        val body = json.encodeToString(PtzMoveRequest(pan, tilt, zoom))
        call("Couldn't move the camera", "/api/cameras/$cameraId/ptz/move", "POST", body) { }
    }

    suspend fun ptzStop(cameraId: Int) {
        call("Couldn't stop the camera", "/api/cameras/$cameraId/ptz/stop", "POST", null) { }
    }

    suspend fun ptzGotoPreset(cameraId: Int, token: String) {
        call("Couldn't go to that view", "/api/cameras/$cameraId/ptz/presets/${enc(token)}/goto", "POST", null) { }
    }

    suspend fun ptzSavePreset(cameraId: Int, name: String): PtzPreset {
        val body = json.encodeToString(PtzPresetRequest(name))
        return call("Couldn't save the view", "/api/cameras/$cameraId/ptz/presets", "POST", body) {
            json.decodeFromString<PtzPreset>(it)
        }
    }

    suspend fun ptzRemovePreset(cameraId: Int, token: String) {
        call("Couldn't delete the view", "/api/cameras/$cameraId/ptz/presets/${enc(token)}", "DELETE", null) { }
    }

    suspend fun ptzHome(cameraId: Int) {
        call("Couldn't go home", "/api/cameras/$cameraId/ptz/home/goto", "POST", null) { }
    }

    private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    /** Server origin (for the embedded web settings). */
    fun baseUrl(): String? = store.serverUrl

    /** Latest JPEG the NVR has for a camera, or null if it has none yet. */
    suspend fun snapshot(cameraId: Int): ByteArray? {
        val response = try {
            authedGet("/api/cameras/$cameraId/snapshot.jpg")
        } catch (e: AuthExpiredException) {
            throw e
        } catch (e: IOException) {
            return null
        }
        return withContext(Dispatchers.IO) {
            response.use { r -> if (r.isSuccessful) r.body?.bytes() else null }
        }
    }

    /** Request for the media-engine websocket (/api/media/ws). OkHttp upgrades
     *  an https:// request to wss:// itself. */
    fun mediaSocketRequest(token: String): Request =
        Request.Builder().url("${base()}/api/media/ws?token=$token").build()

    companion object {
        /** [probe] without the Android parts, for tests. */
        fun probeWith(http: OkHttpClient, trust: PinnedTrust, baseUrl: String): ProbeResult {
            trust.clearLastRejected()
            val request = try {
                Request.Builder().url("$baseUrl/api/auth/setup-status").build()
            } catch (e: IllegalArgumentException) {
                return ProbeResult.Failed("that doesn't look like a valid address")
            }
            return try {
                http.newCall(request).execute().use { r ->
                    if (r.isSuccessful) {
                        ProbeResult.Ok
                    } else {
                        ProbeResult.Failed("it answered HTTP ${r.code} - is this a LightNVR address?")
                    }
                }
            } catch (e: IOException) {
                val rejected = trust.lastRejected
                    ?: generateSequence<Throwable>(e) { it.cause }.filterIsInstance<UntrustedServerCertificate>().firstOrNull()
                if (rejected != null) {
                    ProbeResult.UntrustedCertificate(rejected.sha256, rejected.subject)
                } else {
                    ProbeResult.Failed(explain(e))
                }
            }
        }

        fun findWith(http: OkHttpClient, trust: PinnedTrust, candidates: List<String>): Pair<String, ProbeResult> {
            var failure: Pair<String, ProbeResult>? = null
            for (base in candidates) {
                val result = probeWith(http, trust, base)
                if (result !is ProbeResult.Failed) return base to result
                // Keep the first failure that isn't "nothing listens": it says more.
                if (failure == null || (failure.second as ProbeResult.Failed).message == NOTHING_LISTENS) {
                    failure = base to result
                }
            }
            return failure ?: ("" to ProbeResult.Failed("enter the NVR's address"))
        }

        private const val NOTHING_LISTENS = "nothing answered on that port"

        /** A network error in words a person can act on. */
        fun explain(e: IOException): String = when (e) {
            is java.net.ConnectException -> NOTHING_LISTENS
            is java.net.SocketTimeoutException ->
                "no answer - is that machine switched on, and is the phone on its network (or Tailscale on)?"
            is java.net.UnknownHostException -> "that name isn't known on this network"
            is java.net.NoRouteToHostException -> "no route to it - is the phone on the same network?"
            is javax.net.ssl.SSLException ->
                "the secure connection failed (${e.message ?: "TLS"}) - is this the NVR's HTTPS port?"
            else -> e.message ?: e.javaClass.simpleName
        }

        /** The visits query (pure, so it can be unit-tested). */
        fun visitsPath(startSec: Long, endSec: Long, filter: VisitFilter, limit: Int = 200): String {
            fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")
            fun iso(sec: Long) = java.time.Instant.ofEpochSecond(sec).toString()
            val q = StringBuilder("/api/security/incidents?limit=$limit")
                .append("&start=").append(enc(iso(startSec)))
                .append("&end=").append(enc(iso(endSec)))
            filter.person?.let { q.append("&person=").append(enc(it)) }
            filter.what?.let { q.append("&what=").append(enc(it)) }
            filter.cameraId?.let { q.append("&camera_id=").append(it) }
            filter.group?.let { q.append("&group=").append(enc(it)) }
            return q.toString()
        }
    }
}