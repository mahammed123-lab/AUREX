package com.example.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object SpotifyAuthManager {

    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    private var clientId = "YOUR_SPOTIFY_CLIENT_ID"
    private var clientSecret = "YOUR_SPOTIFY_CLIENT_SECRET"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile private var cachedToken: String? = null
    @Volatile private var expiresAtMillis: Long = 0L

    fun setCredentials(id: String, secret: String) {
        clientId = id
        clientSecret = secret
        cachedToken = null
    }

    suspend fun getAccessToken(): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cachedToken?.let { token -> if (now < expiresAtMillis - 5000) return@withContext token }

        if (clientId.contains("YOUR_SPOTIFY") || clientSecret.contains("YOUR_SPOTIFY")) {
            throw IllegalStateException("Spotify Client ID / Secret not configured")
        }

        val credentials = Credentials.basic(clientId, clientSecret)
        val body = FormBody.Builder().add("grant_type", "client_credentials").build()
        val request = Request.Builder()
            .url(TOKEN_URL)
            .addHeader("Authorization", credentials)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Spotify token request failed: ${response.code}")
            val json = JSONObject(response.body!!.string())
            val token = json.getString("access_token")
            val expiresIn = json.getInt("expires_in")
            cachedToken = token
            expiresAtMillis = System.currentTimeMillis() + expiresIn * 1000L
            token
        }
    }
}
