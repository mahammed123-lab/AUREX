package com.example.api

import okhttp3.Interceptor
import okhttp3.Response
import java.security.SecureRandom

/**
 * W3C traceparent propagation for the Future AGI / OTel observability stack.
 * The Android app stays thin: it only mints a trace/span id per user action and
 * sends it as a header; the backend and Future AGI collector do the heavy span
 * work. No provider/gateway key ever lives on-device.
 *
 * Format: 00-<32 hex trace id>-<16 hex span id>-01
 */
class TraceparentInterceptor : Interceptor {

    private val random = SecureRandom()

    override fun intercept(chain: Interceptor.Chain): Response {
        val traceId = randomHex(16)
        val spanId = randomHex(8)
        val traceparent = "00-$traceId-$spanId-01"
        val request = chain.request().newBuilder()
            .addHeader("traceparent", traceparent)
            .build()
        return chain.proceed(request)
    }

    private fun randomHex(bytes: Int): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }
}