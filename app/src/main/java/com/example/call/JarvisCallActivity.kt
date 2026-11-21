package com.example.call

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.roundToInt

// ── Palette — black & white, accept green / decline red ──────────────────────
private val BG       = Color(0xFF000000)
private val CYAN     = Color(0xFFD6D6D6)
private val CYAN_HI  = Color(0xFFF0F0F0)
private val GREEN    = Color(0xFFE4E4E4)
private val TEXT_HI  = Color(0xFFF5F5F5)
private val TEXT_LO  = Color(0xFF9A9A9A)
private val RING     = Color(0x33FFFFFF)
private val DANGER   = Color(0xFFEF4444)
private val KEY_BG   = Color(0xFF111111)
private val ACCEPT   = Color(0xFF10B981)

/**
 * JARVIS / AUREX live voice call — visual twin of the mock:
 *   Calling …   glowing orb, "Connecting you with Aurex…", red Cancel
 *   In Call     orb + waveform, duration, Voice: Aurex (Natural), 5 actions
 *   Call Ended  ✓ ring + "Talk to you soon." + Back to Chat
 *
 * Audio path: LiveKit room (self-hosted on the PC) — no account, no per-minute
 * billing, ever.
 */
class JarvisCallActivity : ComponentActivity() {

    // Crash containment: any coroutine exception here logs instead of
    // killing the whole app.
    private val crashGuard = kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
        android.util.Log.e("JarvisCall", "call coroutine error (contained)", t)
    }
    private val scope = CoroutineScope(SupervisorJob() + crashGuard + Dispatchers.Main)

    private var rmsSampler: Job? = null
    // LiveKit room — the voice path.
    private var liveKitRoom: Room? = null
    // The relay call this screen is on ("" = none yet). Needed so hanging up
    // tells the relay the phone left instead of leaving the call ringing.
    private var relayCallId: String = ""

    // compose state
    private val phase = mutableStateOf(PHASE_IDLE)
    private val statusText = mutableStateOf("")
    private val subtitle = mutableStateOf("")
    private val durationSec = mutableLongStateOf(0L)
    private val amplitudes = mutableStateListOf<Float>()
    private val showTextReply = mutableStateOf(false)
    private val textReply = mutableStateOf("")
    private val muted = mutableStateOf(false)
    private val speaker = mutableStateOf(true)
    // live captions (whisper on the PC transcribes you in real time)
    private val captionYou = mutableStateOf("")
    private val captionJarvis = mutableStateOf("")
    private var captionCursor = -1L
    // messages deck — separate incoming/outgoing text log, distinct from the
    // in-call caption strip. Incoming: /aurex/events kind="reply"; outgoing:
    // every text you SEND from this screen.
    private val deckIncoming = mutableStateListOf<Pair<Long, String>>()
    private val deckOutgoing = mutableStateListOf<Pair<Long, String>>()
    private val showDeck = mutableStateOf(false)
    private var deckCursor = -1L
    // server link editor
    private val showUrlEditor = mutableStateOf(false)
    private val urlDraft = mutableStateOf("")

    private var ref: String = ""
    private var question: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        ref = intent.getStringExtra(EXTRA_REF) ?: ""
        question = intent.getStringExtra(EXTRA_QUESTION) ?: ""
        repeat(14) { amplitudes.add(0.1f) }

        // Request microphone permission EARLY, before any WebRTC/LiveKit init
        // This avoids SIGABRT in signaling_thread when WebRTC tries to access
        // the mic before the user grants permission.
        val hasRecordPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        setContent { CallScreen() }
        startDeckPoller()  // incoming messages flow from boot, not just in-call

        // For MODE_OUTGOING, start call only after permission is granted
        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_INCOMING -> { phase.value = PHASE_RINGING; statusText.value = "Incoming Call"; subtitle.value = question }
            MODE_OUTGOING -> { if (hasRecordPermission) startCall() else { pendingOutgoing = true } }
            MODE_REPLY -> { phase.value = PHASE_REPLY; statusText.value = "Reply to Aurex"; subtitle.value = question }
            else -> { phase.value = PHASE_RINGING; statusText.value = "Incoming Call" }
        }

        if (!hasRecordPermission) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 44)
        }
    }

    // Track if we have a pending outgoing call waiting for permission
    private var pendingOutgoing = false

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 44 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (pendingOutgoing) {
                pendingOutgoing = false
                startCall()
            }
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // COMPOSE UI
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Composable
    fun CallScreen() {
        Box(
            Modifier
                .fillMaxSize()
                .background(BG)
                .pointerInput(phase.value == PHASE_IN_CALL) {
                    if (phase.value == PHASE_IN_CALL) {
                        var totalDrag = 0f
                        detectVerticalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onDragEnd = { if (totalDrag > 700f) hangUp() }
                        ) { _, dragAmount ->
                            totalDrag += dragAmount
                        }
                    }
                }
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TopBar()
                Spacer(Modifier.height(24.dp))
                Text("Aurex", color = TEXT_HI, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text("AI Assistant", color = TEXT_LO, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                StatusLine()

                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    when (phase.value) {
                        PHASE_IN_CALL -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            OrbWithWaveform()
                            Spacer(Modifier.height(14.dp))
                            Text("Listening…", color = TEXT_HI, fontSize = 17.sp)
                            Text("Just talk, I'm here.", color = TEXT_LO, fontSize = 13.sp)
                            Spacer(Modifier.height(6.dp))
                            Text("Pull down to end call", color = TEXT_LO.copy(alpha = 0.55f), fontSize = 11.sp)
                            if (showUrlEditor.value) { Spacer(Modifier.height(8.dp)); UrlEditor() }
                        }
                        PHASE_ENDED -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            EndedGlyph()
                            Spacer(Modifier.height(18.dp))
                            Text("Call Ended", color = TEXT_HI, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                            Text("Talk to you soon.", color = TEXT_LO, fontSize = 13.sp)
                        }
                        else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Orb(centerLetter = true)
                            Spacer(Modifier.height(18.dp))
                            Text(statusText.value, color = GREEN, fontSize = 15.sp)
                            if (subtitle.value.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(subtitle.value, color = TEXT_LO, fontSize = 13.sp)
                            }
                            if (showUrlEditor.value) { Spacer(Modifier.height(10.dp)); UrlEditor() }
                        }
                    }
                }

                // ── messages deck: separate incoming/outgoing text log ──
                MessagesDeck()

                // ── live captions: what you said → what JARVIS answered ──
                if (phase.value == PHASE_IN_CALL && (captionYou.value.isNotBlank() || captionJarvis.value.isNotBlank())) {
                    Column(Modifier.fillMaxWidth().background(KEY_BG, RoundedCornerShape(18.dp)).padding(14.dp)) {
                        if (captionYou.value.isNotBlank())
                            Text("You: ${captionYou.value}", color = TEXT_LO, fontSize = 12.sp, maxLines = 2)
                        if (captionJarvis.value.isNotBlank())
                            Text("JARVIS: ${captionJarvis.value}", color = GREEN, fontSize = 12.sp, maxLines = 2)
                    }
                    Spacer(Modifier.height(10.dp))
                }

                when (phase.value) {
                    PHASE_RINGING -> RingingControls()
                    PHASE_CALLING -> CallingControls()
                    PHASE_IN_CALL -> InCallControls()
                    PHASE_ENDED -> EndedControls()
                    PHASE_REPLY -> ReplyControls()
                }
            }
        }
    }

    @Composable fun TopBar() {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("⚡", color = CYAN, fontSize = 15.sp)
            Spacer(Modifier.width(6.dp))
            Text("AUREX", color = TEXT_HI, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Text("▮▮▮", color = GREEN, fontSize = 10.sp)
            Text("  Voice Call", color = TEXT_LO, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.Settings, contentDescription = "link",
                tint = TEXT_LO, modifier = Modifier.size(20.dp).clickable {
                    urlDraft.value = CallBridgeApi.callUrl(applicationContext)
                    showUrlEditor.value = !showUrlEditor.value
                })
            Spacer(Modifier.width(10.dp))
            Text(timeLabel(), color = TEXT_LO, fontSize = 12.sp)
        }
    }

    @Composable fun UrlEditor() {
        Column(Modifier.fillMaxWidth().background(KEY_BG, RoundedCornerShape(20.dp)).padding(12.dp)) {
            Text("JARVIS link URL (LAN IP or the Cloudflare tunnel https URL)",
                color = TEXT_LO, fontSize = 10.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.TextField(
                    value = urlDraft.value, onValueChange = { urlDraft.value = it },
                    modifier = Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("https://…trycloudflare.com", color = TEXT_LO, fontSize = 12.sp) }
                )
                Spacer(Modifier.width(6.dp))
                Text("SAVE", color = CYAN_HI, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp).clickable {
                        CallBridgeApi.setBaseUrl(applicationContext, urlDraft.value.trim())
                        showUrlEditor.value = false
                        toast("Link saved")
                    })
            }
        }
    }

    @Composable fun StatusLine() {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(if (phase.value == PHASE_IN_CALL) GREEN else CYAN, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(
                when (phase.value) {
                    PHASE_IN_CALL -> "In Call…"; PHASE_RINGING -> "Incoming…"
                    PHASE_CALLING -> "Calling…"; else -> ""
                },
                color = if (phase.value == PHASE_IN_CALL) GREEN else CYAN, fontSize = 13.sp
            )
        }
    }

    // ── messages deck: separate incoming/outgoing text log ───────────────────
    // Collapsible panel; the newest line of each side is always visible even
    // collapsed, so nothing needs a tap to be noticed. Messages persist across
    // the whole call (and the ended phase) — they are NOT the live captions.

    @Composable fun MessagesDeck() {
        val hasAny = deckIncoming.isNotEmpty() || deckOutgoing.isNotEmpty()
        if (!hasAny && !showDeck.value) return
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(KEY_BG)
                .clickable { showDeck.value = !showDeck.value }
                .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Email, contentDescription = null, tint = CYAN, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text("Messages", color = TEXT_HI, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                if (deckIncoming.isNotEmpty())
                    Text("${deckIncoming.size} in", color = GREEN, fontSize = 10.sp)
                if (deckIncoming.isNotEmpty() && deckOutgoing.isNotEmpty())
                    Text(" · ", color = TEXT_LO, fontSize = 10.sp)
                if (deckOutgoing.isNotEmpty())
                    Text("${deckOutgoing.size} out", color = CYAN, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                Text(if (showDeck.value) "▾" else "▸", color = TEXT_LO, fontSize = 12.sp)
            }
            if (showDeck.value) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    deckIncoming.forEach { (_, msg) ->
                        Row(Modifier.fillMaxWidth().background(Color(0xFF161616), RoundedCornerShape(12.dp)).padding(10.dp),
                            verticalAlignment = Alignment.Top) {
                            Icon(Icons.Filled.CallReceived, contentDescription = null,
                                tint = GREEN, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(7.dp))
                            Text(msg, color = TEXT_HI, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        }
                    }
                    deckOutgoing.forEach { (_, msg) ->
                        Row(Modifier.fillMaxWidth().background(Color(0xFF101010), RoundedCornerShape(12.dp)).padding(10.dp),
                            verticalAlignment = Alignment.Top) {
                            Icon(Icons.Filled.CallMade, contentDescription = null,
                                tint = CYAN, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(7.dp))
                            Text(msg, color = TEXT_HI, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                // collapsed preview — the single newest line, either side
                val newest = (deckIncoming.lastOrNull()?.second ?: "")
                    .takeIf { deckIncoming.isNotEmpty() }
                    ?: (deckOutgoing.lastOrNull()?.second ?: "")
                if (newest.isNotBlank())
                    Text(if (deckOutgoing.isNotEmpty() && (deckIncoming.isEmpty() ||
                            (deckOutgoing.lastOrNull()?.first ?: 0L) > (deckIncoming.lastOrNull()?.first ?: 0L)))
                            "→ $newest" else "← $newest",
                        color = TEXT_LO, fontSize = 11.sp, maxLines = 1,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    private fun recordDeckIncoming(msg: String) {
        if (msg.isBlank()) return
        deckIncoming.add(System.currentTimeMillis() to msg)
        if (deckIncoming.size > 30) deckIncoming.removeAt(0)
    }

    private fun recordDeckOutgoing(msg: String) {
        if (msg.isBlank()) return
        deckOutgoing.add(System.currentTimeMillis() to msg)
        if (deckOutgoing.size > 30) deckOutgoing.removeAt(0)
    }

    private fun startDeckPoller() {
        scope.launch {
            while (isActive && phase.value != PHASE_IDLE) {
                try {
                    if (deckCursor < 0) deckCursor = 0L
                    val evts = CallBridgeApi.peekEvents(applicationContext, sinceId = deckCursor)
                    for (e in evts) {
                        if (e.id > deckCursor) deckCursor = e.id
                        if (e.kind == "reply") recordDeckIncoming(e.body.ifBlank { e.title })
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("JarvisCall", "deck poll hiccup", t)
                }
                delay(2000)
            }
        }
    }

    // ── the glowing orb ──────────────────────────────────────────────────────

    @Composable fun Orb(centerLetter: Boolean) {
        val pulse by rememberInfiniteTransition(label = "p").animateFloat(
            initialValue = 0.96f, targetValue = 1.05f,
            animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pv"
        )
        Box(contentAlignment = Alignment.Center) {
            // outer glow disc
            Box(Modifier
                .size((180 * pulse).dp)
                .background(Brush.radialGradient(
                    listOf(CYAN.copy(alpha = 0.22f), Color.Transparent)), CircleShape))
            Canvas(Modifier.size(160.dp)) {
                drawCircle(color = RING, radius = size.minDimension / 2, style = Stroke(width = 3f))
                drawCircle(color = CYAN, radius = size.minDimension / 2 - 8f, style = Stroke(width = 5f))
                drawCircle(color = KEY_BG, radius = size.minDimension / 2 - 14f)
            }
            if (centerLetter) MonoA()
        }
    }

    @Composable fun MonoA() {
        Canvas(Modifier.size(52.dp)) {
            val w = size.width; val h = size.height
            val stroke = 7f
            drawLine(CYAN_HI, Offset(w * .12f, h * .88f), Offset(w * .5f, h * .12f), stroke)
            drawLine(CYAN_HI, Offset(w * .5f, h * .12f), Offset(w * .88f, h * .88f), stroke)
            drawLine(CYAN_HI, Offset(w * .28f, h * .62f), Offset(w * .72f, h * .62f), stroke * .75f)
        }
    }

    @Composable fun OrbWithWaveform() {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Waveform(amplitudes.take(5), mirror = true)
            Spacer(Modifier.width(18.dp))
            Orb(centerLetter = true)
            Spacer(Modifier.width(18.dp))
            Waveform(amplitudes.takeLast(5), mirror = false)
        }
    }

    @Composable fun Waveform(bars: List<Float>, mirror: Boolean) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            bars.forEachIndexed { i, a ->
                val h = (12 + a * 44).dp
                Box(Modifier.width(3.dp).height(h)
                    .background(if ((i % 2 == 0) != mirror) GREEN else CYAN, RoundedCornerShape(2.dp)))
            }
        }
    }

    @Composable fun EndedGlyph() {
        Box(Modifier.size(110.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(color = RING, radius = size.minDimension / 2, style = Stroke(width = 3f))
                drawCircle(color = CYAN, radius = size.minDimension / 2 - 6f, style = Stroke(width = 4f))
            }
            Icon(Icons.Filled.Check, contentDescription = "ended", tint = CYAN_HI, modifier = Modifier.size(44.dp))
        }
    }

    // ── control rows ─────────────────────────────────────────────────────────

    @Composable fun RoundAction(icon: ImageVector, label: String, bg: Color = KEY_BG, tint: Color = TEXT_LO, onClick: () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
               modifier = Modifier.clickable(onClick = onClick)) {
            Box(Modifier.size(58.dp).background(bg, CircleShape).border(1.dp, Color(0x2222D3EE), CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(label, color = TEXT_LO, fontSize = 11.sp)
        }
    }

    @Composable fun RingingControls() {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundAction(Icons.Filled.Call, "Accept", bg = ACCEPT, tint = Color.Black) { accept() }
            RoundAction(Icons.Filled.Close, "Decline", bg = DANGER, tint = Color.White) { decline() }
        }
        Spacer(Modifier.height(28.dp))
    }

    @Composable fun CallingControls() {
        RoundAction(Icons.Filled.Close, "Cancel", bg = DANGER, tint = Color.White) { hangUp(notify = false) }
        Spacer(Modifier.height(28.dp))
    }

    @Composable fun InCallControls() {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundAction(if (muted.value) Icons.Filled.Close else Icons.Filled.Person, if (muted.value) "Muted" else "Mute",
                tint = if (muted.value) DANGER else TEXT_LO) { toggleMute() }
            RoundAction(Icons.Filled.Menu, "Reply",
                tint = if (showTextReply.value) CYAN_HI else TEXT_LO) { showTextReply.value = !showTextReply.value }
            RoundAction(Icons.Filled.Phone, "End Call", bg = DANGER, tint = Color.White) { hangUp() }
            RoundAction(Icons.Filled.PlayArrow, if (speaker.value) "Speaker" else "Earpiece") { toggleSpeaker() }
            RoundAction(Icons.Filled.MoreVert, "More") { showTextReply.value = !showTextReply.value }
        }
        if (showTextReply.value) {
            Spacer(Modifier.height(14.dp))
            TextReplyBar()
        }
        Spacer(Modifier.height(18.dp))
        CallFooter()
    }

    @Composable fun TextReplyBar() {
        Row(Modifier.fillMaxWidth().background(KEY_BG, RoundedCornerShape(30.dp)).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.TextField(
                value = textReply.value, onValueChange = { textReply.value = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type instead of talking…", color = TEXT_LO, fontSize = 13.sp) },
                singleLine = true
            )
            Spacer(Modifier.width(6.dp))
            Text("SEND", color = CYAN_HI, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp).clickable { sendText() })
        }
    }

    @Composable fun CallFooter() {
        Row(Modifier.fillMaxWidth().background(KEY_BG, RoundedCornerShape(26.dp)).padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Phone, contentDescription = null, tint = GREEN, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Column { Text("Call Duration", color = TEXT_LO, fontSize = 10.sp)
                     Text(formatDuration(durationSec.longValue), color = TEXT_HI, fontSize = 14.sp) }
            Spacer(Modifier.weight(1f))
            Text("▰▰▰", color = CYAN, fontSize = 10.sp)
            Spacer(Modifier.width(6.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("Voice", color = TEXT_LO, fontSize = 10.sp)
                Text("Aurex (Natural)", color = GREEN, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    @Composable fun EndedControls() {
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().background(KEY_BG, RoundedCornerShape(26.dp)).padding(vertical = 14.dp)
            .clickable { finish() }, horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Send, contentDescription = null, tint = GREEN, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Back to Chat", color = TEXT_HI, fontSize = 15.sp)
        }
        Spacer(Modifier.height(24.dp))
    }

    @Composable fun ReplyControls() {
        TextReplyBar(); Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundAction(Icons.Filled.Send, "Send", bg = GREEN, tint = Color.Black) { sendText(); finish() }
            RoundAction(Icons.Filled.Close, "Close", bg = KEY_BG, tint = TEXT_LO) { finish() }
        }
        Spacer(Modifier.height(24.dp))
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // CALL ENGINE
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun accept() { nm().cancel(JarvisCallService.CALL_NOTIFICATION_ID); startCall() }
    private fun decline() {
        nm().cancel(JarvisCallService.CALL_NOTIFICATION_ID)
        // Tell the relay this phone declined. A text message alone left the call
        // ringing on the relay until the timeout expired.
        val callId = ref
        if (callId.isNotBlank()) {
            scope.launch { CallBridgeApi.declineCall(applicationContext, callId) }
        }
        scope.launch { CallBridgeApi.sayToJarvis(applicationContext, "declined", ref) }
        phase.value = PHASE_ENDED
    }

    private fun sendText() {
        val t = textReply.value.trim()
        if (t.isEmpty()) return
        textReply.value = ""
        recordDeckOutgoing(t)
        scope.launch {
            val ok = CallBridgeApi.sayToJarvis(applicationContext, t, ref)
            if (!ok) toast("JARVIS unreachable")
        }
    }

    private fun toggleMute() {
        muted.value = !muted.value
        // Mute the LiveKit microphone track — this is the only audio path now.
        // setMicrophoneEnabled is suspend, so it has to run in the scope.
        val on = !muted.value
        scope.launch {
            try {
                liveKitRoom?.localParticipant?.setMicrophoneEnabled(on)
            } catch (e: Exception) {
                android.util.Log.w("JarvisCall", "mute failed: ${e.message}")
            }
        }
    }

    private fun toggleSpeaker() {
        speaker.value = !speaker.value
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.isSpeakerphoneOn = speaker.value
        } catch (_: Exception) {}
    }

    private fun startCall() {
        phase.value = PHASE_CALLING
        statusText.value = "Calling…"
        subtitle.value = "Connecting you with Aurex…"
        scope.launch(Dispatchers.IO) {
            try {
                // LiveKit is the only voice path. The old plain-WebRTC
                // /api/offer fallback pointed at a Pipecat server on :7860 that
                // no longer exists, and its native lib aborted the whole
                // :jarviscalls process (SIGABRT in signaling_thread), which
                // killed this screen and dumped the user back into the chat.
                // A failed join now reports the error instead of crashing.
                if (!connectLiveKit()) {
                    withContext(Dispatchers.Main) {
                        toast("Could not reach JARVIS")
                        phase.value = PHASE_ENDED
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("Call failed: ${e.message}"); phase.value = PHASE_ENDED }
            }
        }
    }

    // ── LiveKit path ─────────────────────────────────────────────────────────

    /** Join JARVIS's LiveKit room. Returns false (room untouched) when there
     *  are no creds yet or the join failed.
     *  Failure trace (2.18.2 source): SignalClient.onFailure posts
     *  FailedToConnect AND cancels the join continuation, so room.connect()
     *  rethrows the same error — the catch below is the single failure point
     *  and the event handler only logs. */
    private suspend fun connectLiveKit(): Boolean {
        val identity = "aurex-" + System.currentTimeMillis()
        // INCOMING: the agent already created the call; we tell the relay we're
        // taking it and it hands back the LiveKit room to join.
        // OUTGOING: we create the call ourselves.
        val creds = if (ref.isNotBlank()) {
            CallBridgeApi.joinCall(applicationContext, ref, identity)
        } else {
            CallBridgeApi.livekitToken(applicationContext, ROOM_NAME, identity)
        } ?: return false
        relayCallId = creds.callId
        withContext(Dispatchers.Main) { statusText.value = "Connecting via LiveKit…" }

        val room = LiveKit.create(appContext = this@JarvisCallActivity,
            options = RoomOptions(adaptiveStream = true, dynacast = true))
        liveKitRoom = room

        val eventJob = scope.launch {
            room.events.collect { ev ->
                when (ev) {
                    is RoomEvent.Connected -> runOnUiThread { onConnected() }
                    is RoomEvent.FailedToConnect -> runOnUiThread {
                        android.util.Log.w("JarvisCall", "LiveKit connect failed: ${ev.error}")
                        // no fallback here — room.connect() rethrows the same
                        // failure and the catch below falls back exactly once
                    }
                    is RoomEvent.Disconnected -> runOnUiThread {
                        // server ended the call — not our own teardown (that
                        // passes through PHASE_ENDED first)
                        if (phase.value == PHASE_IN_CALL) hangUp(notify = false)
                    }
                    is RoomEvent.Reconnecting -> runOnUiThread { statusText.value = "Reconnecting…" }
                    is RoomEvent.Reconnected -> runOnUiThread { statusText.value = "In Call…" }
                    else -> {}
                }
            }
        }

        try {
            room.connect(url = creds.url, token = creds.token)
            room.localParticipant.setMicrophoneEnabled(true)
        } catch (e: Exception) {
            android.util.Log.w("JarvisCall", "LiveKit connect threw: ${e.message}")
            eventJob.cancel()
            teardownLiveKit()
            return false
        }
        return true
    }

    private fun teardownLiveKit() {
        try { liveKitRoom?.disconnect(); liveKitRoom = null } catch (_: Exception) {}
    }

    private fun onConnected() {
        runOnUiThread {
            phase.value = PHASE_IN_CALL
            statusText.value = "In Call…"; subtitle.value = ""
        }
        startDurationClock()
        startRmsSampler()
        startCaptionPoller()
    }

    private fun startCaptionPoller() {
        scope.launch {
            while (isActive && phase.value == PHASE_IN_CALL) {
                try {
                    // The cursor is the relay's event `seq`, not a timestamp —
                    // seeding it with the wall clock asked for events after a
                    // seq that is far in the future, so no caption ever arrived.
                    // Seed from the log's newest seq so we only see new captions.
                    if (captionCursor < 0) captionCursor = CallBridgeApi.latestSeq(applicationContext)
                    val evts = CallBridgeApi.peekEvents(applicationContext, sinceId = captionCursor)
                    for (e in evts) {
                        if (e.id > captionCursor) captionCursor = e.id
                        when (e.kind) {
                            "transcript" -> captionYou.value = e.body.ifBlank { e.title }
                            "reply"      -> captionJarvis.value = e.body.ifBlank { e.title }
                        }
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("JarvisCall", "caption poll hiccup", t)
                }
                delay(1200)
            }
        }
    }

    private fun startDurationClock() {
        scope.launch {
            while (isActive && phase.value == PHASE_IN_CALL) {
                durationSec.longValue += 1
                delay(1000)
            }
        }
    }

    private fun startRmsSampler() {
        rmsSampler?.cancel()
        rmsSampler = scope.launch(Dispatchers.IO) {
            var record: AudioRecord? = null
            try {
                if (ContextCompat.checkSelfPermission(this@JarvisCallActivity, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) return@launch
                val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf)
                record.startRecording()
                val buf = ShortArray(320)
                while (isActive && phase.value == PHASE_IN_CALL) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) {
                        val rms = kotlin.math.sqrt(buf.take(n).map { it * it.toDouble() }.average()).toFloat()
                        val norm = (rms / 4000f).coerceIn(0.05f, 1f)
                        withContext(Dispatchers.Main) {
                            // shift bars — a live, breathing waveform
                            if (amplitudes.size >= 14) amplitudes.removeAt(0)
                            amplitudes.add(if (muted.value) 0.05f else norm)
                        }
                    }
                    delay(90)
                }
            } catch (_: Exception) {
            } finally { try { record?.stop(); record?.release() } catch (_: Exception) {} }
        }
    }

    private fun hangUp(notify: Boolean = true) {
        if (notify) scope.launch { CallBridgeApi.sayToJarvis(applicationContext, "call ended", ref) }
        // Tell the relay the phone is off the call. Without this the call row
        // stays `active` until the ring timeout and the agent keeps talking
        // into a room nobody is in.
        val callId = relayCallId
        if (callId.isNotBlank()) {
            scope.launch { CallBridgeApi.endCall(applicationContext, callId) }
        }
        relayCallId = ""
        teardownLiveKit()
        rmsSampler?.cancel()
        nm().cancel(JarvisCallService.CALL_NOTIFICATION_ID)
        phase.value = PHASE_ENDED
    }

    private fun nm() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun timeLabel(): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
    private fun formatDuration(s: Long): String = "%02d:%02d".format(s / 60, s % 60)

    override fun onDestroy() { teardownLiveKit(); rmsSampler?.cancel(); scope.cancel(); super.onDestroy() }

    companion object {
        const val MODE_INCOMING = "incoming"; const val MODE_OUTGOING = "outgoing"
        const val MODE_REPLY = "reply";     const val MODE_IDLE = "idle"
        const val EXTRA_MODE = "mode";      const val EXTRA_REF = "ref"
        const val EXTRA_QUESTION = "question"

        // LiveKit room the PC agent hosts; both sides mint/join by name.
        const val ROOM_NAME = "jarvis-room"

        const val PHASE_IDLE = 0;   const val PHASE_RINGING = 1; const val PHASE_CALLING = 2
        const val PHASE_IN_CALL = 3; const val PHASE_ENDED = 4;  const val PHASE_REPLY = 5

        fun ringingIntent(ctx: Context, e: CallBridgeApi.JarvisEvent) =
            Intent(ctx, JarvisCallActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_INCOMING)
                .putExtra(EXTRA_QUESTION, if (e.body.isNotBlank()) e.body else e.title)
                .putExtra(EXTRA_REF, e.ref)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun answerIntent(ctx: Context, e: CallBridgeApi.JarvisEvent) =
            ringingIntent(ctx, e).putExtra(EXTRA_MODE, MODE_OUTGOING)

        fun declineIntent(ctx: Context, e: CallBridgeApi.JarvisEvent) =
            ringingIntent(ctx, e).putExtra(EXTRA_MODE, MODE_IDLE)

        fun replyIntent(ctx: Context, e: CallBridgeApi.JarvisEvent) =
            ringingIntent(ctx, e).putExtra(EXTRA_MODE, MODE_REPLY)

        fun outgoing(ctx: Context) =
            Intent(ctx, JarvisCallActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_OUTGOING)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
