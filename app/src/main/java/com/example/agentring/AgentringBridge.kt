package com.example.agentring

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * AUREX's link to the agentring relay.
 *
 * The relay (on the user's own PC, reachable over Tailscale) is what rings the
 * phone and what hands out the LiveKit room to join. This object holds the URL,
 * the device credential earned by pairing, and the few calls AUREX needs:
 * check whether the agent is up, start a call from the phone, and fetch a LiveKit token.
 *
 * Everything here is blocking on purpose (OkHttp), so callers put it on
 * [Dispatchers.IO]. Pairing info lives in EncryptedSharedPreferences — a phone
 * that loses it can re-pair from the Pair screen.
 */
class AgentringBridge private constructor(private val app: Context) {

    @Serializable
    private data class StartCallReq(val reason: String?)

    @Serializable
    private data class PairingReq(
        val pairing_token: String,
        val device_name: String,
        val platform: String = "android",
    )

    @Serializable
    private data class PairingResp(
        val device_id: String,
        val device_jwt: String,
        val relay_id: String,
        val push_secret: String,
        val agent_name: String,
    )

    companion object {
        private const val TAG = "AgentringBridge"
        private val JSON = "application/json".toMediaType()

        /**
         * The body every body-less relay POST carries. Fastify refuses an empty
         * body when the content type is JSON (HTTP 400), so the minimum legal
         * body is an empty JSON object.
         */
        private const val EMPTY_JSON = "{}"

        @Volatile
        private var instance: AgentringBridge? = null

        fun get(app: Context): AgentringBridge =
            instance ?: synchronized(this) {
                instance ?: AgentringBridge(app).also { instance = it }
            }

        /** Normalises a pasted base URL: no trailing slash, http:// added if bare. */
        fun normalizeUrl(raw: String): String {
            val t = raw.trim().trimEnd('/')
            if (t.isEmpty()) return t
            return if (t.startsWith("http://") || t.startsWith("https://")) t else "http://$t"
        }
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val prefs by lazy {
        val key = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            "agentring_pairing",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // ── pairing state ────────────────────────────────────────────────────────

    var relayUrl: String
        get() = prefs.getString("relay_url", null) ?: ""
        set(v) = prefs.edit().putString("relay_url", normalizeUrl(v)).apply()

    /** The device JWT the relay handed us when we paired. */
    var deviceJwt: String?
        get() = prefs.getString("device_jwt", null)
        private set(v) = prefs.edit().putString("device_jwt", v).apply()

    var deviceId: String?
        get() = prefs.getString("device_id", null)
        private set(v) = prefs.edit().putString("device_id", v).apply()

    var agentName: String
        get() = prefs.getString("agent_name", null) ?: "JARVIS"
        private set(v) = prefs.edit().putString("agent_name", v).apply()

    val isPaired: Boolean get() = !deviceJwt.isNullOrBlank()

    fun forget() {
        prefs.edit().clear().apply()
    }

    /**
     * Exchanges the pairing token from the QR / PASTE screen for a device
     * credential. Returns true on success.
     */
    suspend fun pair(token: String, deviceName: String): Boolean = withContext(Dispatchers.IO) {
        val base = relayUrl
        if (base.isBlank()) return@withContext false
        val body = json.encodeToString(PairingReq.serializer(), PairingReq(token, deviceName))
            .toRequestBody(JSON)
        val req = Request.Builder()
            .url("$base/v1/pair")
            .post(body)
            .header("Content-Type", "application/json")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "pair failed: HTTP ${resp.code} ${resp.message}")
                return@withContext false
            }
            val parsed = json.decodeFromString(PairingResp.serializer(), resp.body!!.string())
            deviceJwt = parsed.device_jwt
            deviceId = parsed.device_id
            agentName = parsed.agent_name
            agentName = parsed.agent_name
            true
        }
    }

    // ── REST helpers ─────────────────────────────────────────────────────────

    private fun authHeader(): String = "Bearer ${deviceJwt ?: ""}"

    suspend fun status(): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$relayUrl/v1/status")
            .header("Authorization", authHeader())
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) "error: HTTP ${resp.code}"
            else resp.body?.string() ?: ""
        }
    }

    /**
     * Starts a call from this phone to the agent.
     *
     * The relay's `POST /v1/calls` already mints the LiveKit join info for the
     * CALLER, so this returns both the call and the credentials to join its
     * room. Accepting afterwards is wrong: for a `user_to_agent` call the phone
     * is the caller, and the relay refuses to let a caller answer its own call
     * ("The caller cannot answer its own call", HTTP 403).
     */
    suspend fun createCall(): CallWithJoin? = withContext(Dispatchers.IO) {
        val body = json.encodeToString(StartCallReq.serializer(), StartCallReq(null))
            .toRequestBody(JSON)
        val req = Request.Builder()
            .url("$relayUrl/v1/calls")
            .header("Authorization", authHeader())
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "createCall failed: HTTP ${resp.code}")
                return@withContext null
            }
            runCatching {
                ProtocolJson.decodeFromString<CallWithJoin>(resp.body!!.string())
            }.onFailure { Log.w(TAG, "createCall: bad body: ${it.message}") }
                .getOrNull()
        }
    }

    /**
     * Accepts the incoming call and returns the LiveKit ws url + token to join it.
     *
     * The body must be `{}`, not empty: the relay is Fastify, and Fastify
     * rejects an empty body with `Content-Type: application/json` —
     * "Body cannot be empty when content-type is set to 'application/json'" —
     * HTTP 400. That 400 made the phone think the accept failed, so it fell
     * back to the dead legacy peer path and never joined the room.
     */
    suspend fun acceptCall(callId: String): JoinInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$relayUrl/v1/calls/$callId/accept")
            .header("Authorization", authHeader())
            .post(EMPTY_JSON.toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "acceptCall failed: HTTP ${resp.code}")
                return@withContext null
            }
            val obj = json.parseToJsonElement(resp.body!!.string()).jsonObject
            val join = obj["join"]?.jsonObject ?: return@use null
            JoinInfo(
                livekitUrl = join["livekit_url"]?.jsonPrimitive?.content ?: "",
                livekitToken = join["livekit_token"]?.jsonPrimitive?.content ?: "",
                room = join["room"]?.jsonPrimitive?.content ?: "",
                identity = join["identity"]?.jsonPrimitive?.content ?: "",
                expiresAt = join["expires_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            )
        }
    }

    suspend fun endCall(callId: String) {
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$relayUrl/v1/calls/$callId/end")
                .header("Authorization", authHeader())
                .post(EMPTY_JSON.toRequestBody(JSON))
                .build()
            http.newCall(req).execute().close()
        }
    }

    /** Drops an incoming call back to the agent (phone was not answered). */
    suspend fun declineCall(callId: String) {
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$relayUrl/v1/calls/$callId/decline")
                .header("Authorization", authHeader())
                .post(EMPTY_JSON.toRequestBody(JSON))
                .build()
            http.newCall(req).execute().close()
        }
    }
}
