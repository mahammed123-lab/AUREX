package com.example.spotify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MusicCommandHandler(
    private val context: Context,
    private val spotifyController: SpotifyController
) {
    private val scope = CoroutineScope(Dispatchers.Main)

    fun handlePlayCommand(rawQuery: String) {
        scope.launch {
            val track = try {
                SpotifySearchService.findBestTrack(rawQuery)
            } catch (e: Exception) {
                Log.e("MusicCommandHandler", "Search failed", e)
                null
            }

            if (track != null) {
                spotifyController.connect(
                    onReady = { spotifyController.playUri(track.uri) },
                    onError = { launchViaDeepLink(track.uri) }
                )
            } else {
                launchViaTextSearch(rawQuery)
            }
        }
    }

    private fun launchViaDeepLink(uri: String) {
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

    private fun launchViaTextSearch(query: String) {
        val uri = Uri.parse("spotify:search:${Uri.encode(query)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.spotify.music")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            val searchIntent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, query)
                putExtra("android.intent.extra.focus", "vnd.android.cursor.item/audio")
                putExtra(android.provider.MediaStore.EXTRA_MEDIA_TITLE, query)
                putExtra("autostart", true)
                putExtra("play", true)
                setPackage("com.spotify.music")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(searchIntent)
            } catch (ex: Exception) {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/" + Uri.encode(query))).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            }
        }
    }
}
