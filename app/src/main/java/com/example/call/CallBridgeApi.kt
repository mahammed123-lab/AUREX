package com.example.call

import android.content.Context
import android.util.Log
import com.example.agentring.AgentringBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * AUREX's talking interface to JARVIS — now over the agentring relay.
 *
 * Before this it called two raw servers on a hardcoded LAN address: a call
 * server on :7860 (Pipecat) and a messaging server on :8080. Both lived on the
 * desk the phone could only reach at home. Now the phone talks to the single
 * agentring relay over Tailscale, which rings it and handles the LiveKit room.
 *
 * The rendered event stream (an incoming call, a message from the agent)
 * is unchanged, so [JarvisCallService] and [JarvisCallActivity] keep working.
 */
object CallBridgeApi {

    @Serializable
    data class JarvisEvent(
        val id: Long,
        val kind: String,       // "call" | "reply" | "info" | "ask"
        val title: String,
        val body: String,
        val call: Boolean,
        val ref: String,
        val ts: Double,
    )

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    private fun bridge(ctx: Context): AgentringBridge = AgentringBridge.get(ctx)

    // ── connection settings ──────────────────────────────────────────────────

    /** The relay URL the phone is paired with (Tailscale address of the PC). */
    fun relayUrl(ctx: Context): String = bridge(ctx).relayUrl

    fun setRelayUrl(ctx: Context, url: String) {
        bridge(ctx).relayUrl = url
    }

    fun isPaired(ctx: Context): Boolean = bridge(ctx).isPaired

    // ── names the call UI already uses (kept as aliases) ─────────────────────

    fun callUrl(ctx: Context): String = bridge(ctx).relayUrl

    fun setBaseUrl(ctx: Context, url: String) {
        bridge(ctx).relayUrl = url
    }

    // ── events (the agent wants something) ───────────────────────────────────

    /**
     * Reads agentring events after the given sequence id and maps them to
     * [JarvisEvent]s, returning the new cursor. The cursor is
     * persisted by the caller (the service) so we never repeat an event.
     *
     * Event shapes we care about:
     *   call.invite    the agent is ringing this phone   → call = true
     *   msg.new        a text message from the agent     → kind = "reply"
     */
    suspend fun fetchEvents(ctx: Context, afterSeq: Long): Pair<List<JarvisEvent>, Long> =
        withContext(Dispatchers.IO) {
            val b = bridge(ctx)
            if (!b.isPaired) return@withContext emptyList<JarvisEvent>() to afterSeq
            val req = Request.Builder()
                .url("${b.relayUrl}/v1/events?after_seq=$afterSeq&limit=50")
                .header("Authorization", "Bearer ${b.deviceJwt ?: ""}")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "fetchEvents: HTTP ${resp.code}")
                    return@use emptyList<JarvisEvent>() to afterSeq
                }
                val body = resp.body?.string().orEmpty()
                val root = json.parseToJsonElement(body).jsonObject
                // next_after_seq is the last seq of the page we actually got.
                // last_seq is the newest seq in the whole log, so using it as
                // the cursor would skip events whenever the page was truncated.
                val next = root["next_after_seq"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: root["last_seq"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: afterSeq
                val events = root["events"]?.jsonArray ?: return@use emptyList<JarvisEvent>() to next
                val out = ArrayList<JarvisEvent>()
                for (el in events) {
                    val ev = el.jsonObject
                    val type = ev["type"]?.jsonPrimitive?.content ?: continue
                    val seq = ev["seq"]?.jsonPrimitive?.content?.toLongOrNull() ?: continue
                    // The relay's cursor is the event's global `seq`. Callers
                    // persist it and pass it back as after_seq, so it must be the
                    // id we hand out — a call_id hash was not a cursor at all and
                    // made every poll replay the whole log.
                    val id = seq
                    val direction = ev["direction"]?.jsonPrimitive?.content
                    val reason = ev["reason"]?.jsonPrimitive?.content
                    val text = ev["text"]?.jsonPrimitive?.content

                    // The relay's Direction type has exactly two values:
                    // "agent_to_user" (the agent/PC is calling the phone) and
                    // "user_to_agent" (the phone is calling the PC).
                    // "agent_to_device" was never a relay value — it silently
                    // matched nothing, so the phone never rang.
                    when {
                        type == "call.invite" && direction == "agent_to_user" ->
                            out += JarvisEvent(id, "call", "JARVIS", reason ?: "JARVIS wants to talk", true, ev["call_id"]?.jsonPrimitive?.content ?: "", 0.0)
                        // msg.new carries no "direction" at all: the relay emits
                        // {type,msg_id,from,text,reply_to?}. "from" is "agent"
                        // when the PC sent it and "user" when the phone did.
                        type == "msg.new" && ev["from"]?.jsonPrimitive?.content == "agent" ->
                            out += JarvisEvent(id, "reply", b.agentName, text ?: "", false, ev["msg_id"]?.jsonPrimitive?.content ?: "", 0.0)
                    }
                }
                return@use out to next
            }
        }

    // ── event helpers kept for the call UI's existing call sites ─────────────

    /**
     * Events newer than [sinceId]. The relay is sequence-based, so the old
     * "id" cursor is preserved in the returned events' `id`.
     */
    suspend fun peekEvents(ctx: Context, sinceId: Long): List<JarvisEvent> =
        fetchEvents(ctx, afterSeq = if (sinceId < 0) 0 else sinceId).first

    suspend fun pollEvents(ctx: Context, sinceId: Long = 0): List<JarvisEvent> =
        peekEvents(ctx, sinceId)

    /**
     * The relay's newest event seq. A live poller starts from here so it only
     * sees events from now on, instead of replaying the whole log.
     */
    suspend fun latestSeq(ctx: Context): Long = withContext(Dispatchers.IO) {
        val b = bridge(ctx)
        if (!b.isPaired) return@withContext 0L
        val req = Request.Builder()
            .url("${b.relayUrl}/v1/events?after_seq=0&limit=1")
            .header("Authorization", "Bearer ${b.deviceJwt ?: ""}")
            .get()
            .build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use 0L
                val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                root["last_seq"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)
    }

    // ── call lifecycle ───────────────────────────────────────────────────────

    suspend fun startCall(ctx: Context): String? = bridge(ctx).createCall()?.call?.callId

    suspend fun acceptCall(ctx: Context, callId: String): com.example.agentring.JoinInfo? =
        bridge(ctx).acceptCall(callId)

    suspend fun declineCall(ctx: Context, callId: String) = bridge(ctx).declineCall(callId)

    suspend fun endCall(ctx: Context, callId: String) = bridge(ctx).endCall(callId)

    suspend fun status(ctx: Context): String = bridge(ctx).status()

    // ── reply (what the phone said, sent back to the agent) ─────────────────

    @Serializable
    private data class SendMessageReq(
        val text: String,
        @Serializable val reply_to: String? = null,
    )

    /** Sends a message from the phone to the agent. Returns true when the relay accepted it. */
    suspend fun reply(ctx: Context, text: String, ref: String = ""): Boolean =
        withContext(Dispatchers.IO) {
            val b = bridge(ctx)
            if (!b.isPaired) return@withContext false
            val payload = json.encodeToString(
                SendMessageReq.serializer(),
                SendMessageReq(text, ref.ifBlank { null }),
            )
            val req = Request.Builder()
                .url("${b.relayUrl}/v1/messages")
                .header("Authorization", "Bearer ${b.deviceJwt ?: ""}")
                .post(payload.toRequestBody(JSON))
                .build()
            http.newCall(req).execute().use { it.isSuccessful }
        }

    /** Alias used by the call screen for what the user said back to the agent. */
    suspend fun sayToJarvis(ctx: Context, text: String, ref: String = ""): Boolean =
        reply(ctx, text, ref)

    /** The messaging URL is the same relay the phone is paired with. */
    fun messagingUrl(ctx: Context): String = bridge(ctx).relayUrl

    /** True when the relay answers /v1/status (the phone can reach its agent). */
    suspend fun isReachable(ctx: Context): Boolean {
        val b = bridge(ctx)
        if (!b.isPaired || b.relayUrl.isBlank()) return false
        val req = okhttp3.Request.Builder()
            .url("${b.relayUrl}/v1/status")
            .header("Authorization", "Bearer ${b.deviceJwt ?: ""}")
            .get()
            .build()
        return runCatching {
            http.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /**
     * Starts a LiveKit call to the agent and returns the room credentials to
     * join it.
     *
     * The relay hands the CALLER its LiveKit join info in the same response
     * that creates the call, so there is nothing to accept afterwards: the
     * phone is the caller of a `user_to_agent` call and the relay rejects a
     * caller trying to answer its own call with HTTP 403.
     */
    suspend fun livekitToken(ctx: Context, room: String, identity: String): LiveKitCreds? {
        val b = bridge(ctx)
        val created = b.createCall() ?: return null
        return LiveKitCreds(
            url = created.join.livekitUrl,
            token = created.join.livekitToken,
            callId = created.call.callId,
        )
    }

    /** Minimal credentials the call screen passes straight to room.connect(). */
    data class LiveKitCreds(val url: String, val token: String, val callId: String)

    /**
     * Phone answers an INCOMING call: tells the relay it accepted `callId` and
     * returns the LiveKit credentials for that room (no new call is created).
     */
    suspend fun joinCall(ctx: Context, callId: String, identity: String): LiveKitCreds? {
        val join = com.example.agentring.AgentringBridge.get(ctx).acceptCall(callId)
            ?: return null
        return LiveKitCreds(url = join.livekitUrl, token = join.livekitToken, callId = callId)
    }

    private const val TAG = "CallBridgeApi"
}
