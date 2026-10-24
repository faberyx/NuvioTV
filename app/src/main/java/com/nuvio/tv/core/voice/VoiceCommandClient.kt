package com.nuvio.tv.core.voice

import android.util.Log
import com.nuvio.tv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps a WebSocket open to the Alexa relay Worker while the app is in the foreground
 * (MainActivity.onStart -> [start], onStop -> [stop]). Incoming commands are dispatched
 * through [VoiceCommandBus] and the result is sent back so Alexa can say what happened.
 *
 * Disabled unless VOICE_RELAY_URL and VOICE_RELAY_TOKEN are set in local.properties.
 */
@Singleton
class VoiceCommandClient @Inject constructor(
    baseClient: OkHttpClient,
    private val bus: VoiceCommandBus,
    private val playCoordinator: VoicePlayCoordinator,
) {
    private val client: OkHttpClient = baseClient.newBuilder()
        .cache(null)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relayUrl = BuildConfig.VOICE_RELAY_URL.trim()
    private val token = BuildConfig.VOICE_RELAY_TOKEN.trim()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var wanted = false
    private var reconnectJob: Job? = null
    private var attempt = 0

    val isConfigured: Boolean get() = relayUrl.isNotEmpty() && token.isNotEmpty()

    @Synchronized
    fun start() {
        if (!isConfigured || wanted) return
        wanted = true
        attempt = 0
        connect()
    }

    @Synchronized
    fun stop() {
        wanted = false
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(NORMAL_CLOSURE, "app in background")
        socket = null
    }

    @Synchronized
    private fun connect() {
        if (!wanted) return
        val request = Request.Builder()
            .url(relayUrl.trimEnd('/') + "/tv")
            .header("Authorization", "Bearer $token")
            .build()
        socket = client.newWebSocket(request, Listener())
    }

    @Synchronized
    private fun scheduleReconnect(failed: WebSocket) {
        if (!wanted || socket !== failed) return
        socket = null
        val backoffMs = (1_000L shl attempt.coerceAtMost(6)).coerceAtMost(60_000L)
        attempt++
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoffMs)
            connect()
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@VoiceCommandClient) { attempt = 0 }
            Log.i(TAG, "connected to voice relay")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return
            val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return
            scope.launch {
                val command = VoiceCommand.fromJson(json)
                val result = when (command) {
                    null, is VoiceCommand.OpenTitle -> VoiceResult.fail("unknown_command")
                    is VoiceCommand.Play -> playCoordinator.play(command.query)
                    else -> bus.dispatch(command)
                }
                Log.d(TAG, "command=$command result=$result")
                val reply = JSONObject()
                    .put("id", id)
                    .put("ok", result.ok)
                    .put("code", result.code)
                result.title?.let { reply.put("title", it) }
                webSocket.send(reply.toString())
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scheduleReconnect(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "voice relay connection failed (${response?.code}): ${t.message}")
            scheduleReconnect(webSocket)
        }
    }

    private companion object {
        const val TAG = "VoiceCommandClient"
        const val NORMAL_CLOSURE = 1000
    }
}
