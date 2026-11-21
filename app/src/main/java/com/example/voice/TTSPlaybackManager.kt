package com.example.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build

class TTSPlaybackManager(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    /** [audioUrl] should point at the audio your backend's TTS generated
     *  for the response — a local file path or an HTTP(S) URL both work with MediaPlayer. */
    fun play(audioUrl: String, onComplete: () -> Unit) {
        requestAudioFocus()
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            try {
                setDataSource(context, Uri.parse(audioUrl))
                setOnPreparedListener { start() }
                setOnCompletionListener { abandonAudioFocus(); onComplete() }
                setOnErrorListener { _, _, _ -> abandonAudioFocus(); onComplete(); true }
                prepareAsync()
            } catch (e: Exception) {
                abandonAudioFocus()
                onComplete()
            }
        }
    }

    fun stop() {
        try {
            mediaPlayer?.let { if (it.isPlaying) it.stop() }
            mediaPlayer?.release()
        } catch (e: Exception) {
            android.util.Log.e("TTSPlaybackManager", "Error stopping player", e)
        }
        mediaPlayer = null
        abandonAudioFocus()
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
}
