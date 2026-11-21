package com.example.spotify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

class SpotifyController(private val context: Context) {

    companion object {
        private const val TAG = "SpotifyController"
        private const val CLIENT_ID = "YOUR_SPOTIFY_CLIENT_ID"
        private const val REDIRECT_URI = "jarvis://spotify-callback"
    }

    fun connect(onReady: () -> Unit = {}, onError: (Throwable) -> Unit = {}) {
        Log.i(TAG, "Connecting Spotify Controller...")
        onReady()
    }

    fun playUri(uri: String) {
        Log.i(TAG, "Playing URI: $uri")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage("com.spotify.music")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
        }
    }

    fun pause() {
        sendMediaButtonIntent(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    fun resume() {
        sendMediaButtonIntent(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    fun skipNext() {
        sendMediaButtonIntent(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun skipPrevious() {
        sendMediaButtonIntent(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    private fun sendMediaButtonIntent(keyCode: Int) {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
            setPackage("com.spotify.music")
        }
        context.sendOrderedBroadcast(intent, null)
    }

    fun disconnect() {
        Log.i(TAG, "Disconnected Spotify Controller")
    }
}
