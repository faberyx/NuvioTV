package com.nuvio.tv.core.voice

import org.json.JSONObject

/**
 * A remote voice command relayed from the Alexa skill (via the Cloudflare relay Worker).
 *
 * Wire format (relay -> TV), one JSON object per WebSocket text frame:
 *   {"id":"<uuid>","cmd":"pause"}
 *   {"id":"<uuid>","cmd":"seek_by","ms":-30000}
 *   {"id":"<uuid>","cmd":"search","query":"the bear"}
 *   {"id":"<uuid>","cmd":"play","query":"the bear"}
 *   {"id":"<uuid>","cmd":"key","key":"down","count":3}
 *
 * Reply (TV -> relay): {"id":"<uuid>","ok":true,"code":"paused"}
 * ("play" replies also carry "title": the name of the title that was opened.)
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

    /** Remote-control key press(es): D-pad arrows or select (OK). */
    data class Key(val key: RemoteKey, val count: Int = 1) : VoiceCommand

    /** Find a title by name and start it (series resume from watch progress). */
    data class Play(val query: String) : VoiceCommand

    /**
     * Internal, never on the wire: produced by [VoicePlayCoordinator] once [Play] has been
     * resolved to a catalog item. The app-level handler opens it with play-on-load.
     */
    data class OpenTitle(
        val id: String,
        val type: String,
        val name: String,
        val addonBaseUrl: String?,
        val year: Int? = null,
    ) : VoiceCommand

    companion object {
        const val MAX_KEY_REPEAT = 10

        fun fromJson(json: JSONObject): VoiceCommand? = when (json.optString("cmd")) {
            "pause" -> Pause
            "resume" -> Resume
            "seek_by" -> json.optLong("ms", 0L).takeIf { it != 0L }?.let(::SeekBy)
            "seek_to" -> json.optLong("ms", -1L).takeIf { it >= 0L }?.let(::SeekTo)
            "next_episode" -> NextEpisode
            "skip_intro" -> SkipIntro
            "stop" -> Stop
            "search" -> json.optString("query").trim().takeIf { it.isNotEmpty() }?.let(::Search)
            "play" -> json.optString("query").trim().takeIf { it.isNotEmpty() }?.let(::Play)
            "key" -> RemoteKey.fromWire(json.optString("key"))?.let { key ->
                Key(key, json.optInt("count", 1).coerceIn(1, MAX_KEY_REPEAT))
            }
            else -> null
        }
    }
}

/** Keys a voice command can press, with their Android key codes. */
enum class RemoteKey(val wire: String, val keyCode: Int) {
    UP("up", android.view.KeyEvent.KEYCODE_DPAD_UP),
    DOWN("down", android.view.KeyEvent.KEYCODE_DPAD_DOWN),
    LEFT("left", android.view.KeyEvent.KEYCODE_DPAD_LEFT),
    RIGHT("right", android.view.KeyEvent.KEYCODE_DPAD_RIGHT),
    SELECT("select", android.view.KeyEvent.KEYCODE_DPAD_CENTER);

    companion object {
        fun fromWire(value: String): RemoteKey? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Outcome of a command. [code] is a stable machine-readable string; the relay maps it to
 * the spoken reply (Italian or English), so the app never has to localize Alexa responses.
 */
data class VoiceResult(val ok: Boolean, val code: String, val title: String? = null) {
    companion object {
        fun ok(code: String, title: String? = null) = VoiceResult(true, code, title)
        fun fail(code: String) = VoiceResult(false, code)

        /** Precheck answer for [VoiceCommand.Play] when a video is on screen. */
        const val PLAY_READY_WHILE_PLAYING = "ready_while_playing"
    }
}
