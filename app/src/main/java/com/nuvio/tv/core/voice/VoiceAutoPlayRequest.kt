package com.nuvio.tv.core.voice

import android.os.SystemClock
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Play <title>" by voice should start the first available source even when the user's
 * Stream auto-play setting is Manual (there is no remote in hand to pick one).
 *
 * The voice coordinator records the title it opened; the sources screen for that title
 * consumes the request once and, if the user's mode is Manual, loads with First stream /
 * All sources instead. First stream and Regex modes are left as configured, since they
 * already start on their own. Requests expire so a later manual visit isn't affected.
 */
@Singleton
class VoiceAutoPlayRequest @Inject constructor() {

    private data class Pending(val metaId: String, val atMs: Long)

    @Volatile private var pending: Pending? = null

    /** [metaId]: the movie or show id that was opened (episode ids share its prefix). */
    fun request(metaId: String) {
        pending = Pending(metaId, now())
    }

    /**
     * True once if the sources screen for [contentId] / [videoId] was opened by voice in the
     * last [TTL_MS]. Clears the request either way when it matches.
     */
    @Synchronized
    fun consume(contentId: String?, videoId: String): Boolean {
        val request = pending ?: return false
        if (now() - request.atMs > TTL_MS) {
            pending = null
            return false
        }
        val matches = contentId == request.metaId ||
            videoId == request.metaId ||
            videoId.startsWith("${request.metaId}:")
        if (matches) pending = null
        return matches
    }

    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }
    private fun now() = clock()

    companion object {
        /** Detail metadata + next-episode lookup normally takes a few seconds. */
        const val TTL_MS = 120_000L

        /** The settings the sources screen should use for a voice-started title. */
        fun forVoice(settings: PlayerSettings): PlayerSettings =
            if (settings.streamAutoPlayMode != StreamAutoPlayMode.MANUAL) {
                settings
            } else {
                settings.copy(
                    streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM,
                    streamAutoPlaySource = StreamAutoPlaySource.ALL_SOURCES,
                    streamAutoPlaySelectedAddons = emptySet(),
                    streamAutoPlaySelectedPlugins = emptySet(),
                )
            }
    }
}
