package com.nuvio.tv.core.voice

import android.util.Log
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Play <title>" in three steps:
 *  1. ask the screens whether a title may be opened right now (the app refuses before
 *     onboarding is done; the player answers "ready while playing" and keeps playing);
 *  2. resolve the spoken title against the addon catalogs;
 *  3. open it with play-on-load (series resume from watch progress, sources follow the user's
 *     stream auto-play setting). If a video is playing, the player exits first.
 * No match: open the Search screen, or, if a video is playing, leave it playing.
 */
@Singleton
class VoicePlayCoordinator @Inject constructor(
    private val bus: VoiceCommandBus,
    private val resolver: VoiceTitleResolver,
) {
    suspend fun play(query: String): VoiceResult {
        val precheck = bus.dispatch(VoiceCommand.Play(query))
        if (!precheck.ok) return precheck
        val videoPlaying = precheck.code == VoiceResult.PLAY_READY_WHILE_PLAYING

        val match = runCatching { withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolver.resolve(query) } }
            .onFailure { Log.w(TAG, "title lookup failed for '$query'", it) }
            .getOrNull()

        if (match == null) {
            if (videoPlaying) return VoiceResult.fail("not_found")
            val search = bus.dispatch(VoiceCommand.Search(query))
            return if (search.ok) VoiceResult.fail("not_found_searching") else search
        }

        val opened = bus.dispatch(match)
        val spokenTitle = match.year?.let { "${match.name} ($it)" } ?: match.name
        return if (opened.ok) opened.copy(title = spokenTitle) else opened
    }

    private companion object {
        const val TAG = "VoicePlayCoordinator"

        // The relay waits ~7 s for the TV and Alexa allows ~8 s overall.
        const val RESOLVE_TIMEOUT_MS = 5_000L
    }
}
