package com.nuvio.tv.core.voice

import org.json.JSONObject

/**
 * A remote voice command relayed from the Alexa skill (via the Cloudflare relay Worker).
 *
 * Wire format (relay -> TV), one JSON object per WebSocket text frame:
 *   {"id":"<uuid>","cmd":"pause"}
 *   {"id":"<uuid>","cmd":"seek_by","ms":-30000}
 *   {"id":"<uuid>","cmd":"search","query":"the bear"}
 *
 * Reply (TV -> relay): {"id":"<uuid>","ok":true,"code":"paused"}
 */
sealed interface VoiceCommand {
    data object Pause : VoiceCommand
    data object Resume : VoiceCommand
    data class SeekBy(val deltaMs: Long) : VoiceCommand
    data class SeekTo(val positionMs: Long) : VoiceCommand
    data object NextEpisode : VoiceCommand
    data object SkipIntro : VoiceCommand
    data object Stop : VoiceCommand
    data class Search(val query: String) : VoiceCommand

    companion object {
        fun fromJson(json: JSONObject): VoiceCommand? = when (json.optString("cmd")) {
            "pause" -> Pause
            "resume" -> Resume
            "seek_by" -> json.optLong("ms", 0L).takeIf { it != 0L }?.let(::SeekBy)
            "seek_to" -> json.optLong("ms", -1L).takeIf { it >= 0L }?.let(::SeekTo)
            "next_episode" -> NextEpisode
            "skip_intro" -> SkipIntro
            "stop" -> Stop
            "search" -> json.optString("query").trim().takeIf { it.isNotEmpty() }?.let(::Search)
            else -> null
        }
    }
}

/**
 * Outcome of a command. [code] is a stable machine-readable string; the relay maps it to
 * the spoken Italian reply, so the app never has to localize Alexa responses.
 */
data class VoiceResult(val ok: Boolean, val code: String) {
    companion object {
        fun ok(code: String) = VoiceResult(true, code)
        fun fail(code: String) = VoiceResult(false, code)
    }
}
