package com.example.spotify

import com.example.util.FuzzyMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

data class SpotifyTrack(val uri: String, val name: String, val artist: String)

object SpotifySearchService {

    private val client = OkHttpClient()

    suspend fun findBestTrack(rawQuery: String): SpotifyTrack? = withContext(Dispatchers.IO) {
        try {
            val query = normalize(rawQuery)
            val token = SpotifyAuthManager.getAccessToken()
            val url = "https://api.spotify.com/v1/search?q=${URLEncoder.encode(query, "UTF-8")}&type=track&limit=10"

            val request = Request.Builder().url(url).addHeader("Authorization", "Bearer $token").build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val items = JSONObject(response.body!!.string()).getJSONObject("tracks").getJSONArray("items")
                if (items.length() == 0) return@withContext null

                var best: SpotifyTrack? = null
                var bestScore = -1.0
                for (i in 0 until items.length()) {
                    val t = items.getJSONObject(i)
                    val name = t.getString("name")
                    val popularity = t.optInt("popularity", 0)
                    val score = FuzzyMatch.similarity(query, name) * 0.8 + (popularity / 100.0) * 0.2
                    if (score > bestScore) {
                        bestScore = score
                        best = SpotifyTrack(
                            uri = t.getString("uri"),
                            name = name,
                            artist = t.getJSONArray("artists").getJSONObject(0).getString("name")
                        )
                    }
                }
                best
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun normalize(q: String): String =
        q.lowercase().replace(Regex("\\b(play|song|track|by|the song)\\b"), "").trim()
}
