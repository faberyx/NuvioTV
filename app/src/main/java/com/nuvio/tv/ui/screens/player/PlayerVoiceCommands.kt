package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.voice.VoiceCommand
import com.nuvio.tv.core.voice.VoiceResult

/**
 * Maps Alexa voice commands onto the player's existing [PlayerEvent]s, so voice goes through
 * exactly the same code paths as the remote (watch-party locking, scrobbling, overlays...).
 *
 * Returns null for commands the player does not own, letting the app-level handler try.
 */
internal fun PlayerViewModel.handleVoiceCommand(
    command: VoiceCommand,
    exitPlayer: () -> Unit,
): VoiceResult? {
    val state = uiState.value
    val timeline = playbackTimeline.value
    return when (command) {
        VoiceCommand.Pause -> when {
            state.isPlaying -> {
                onEvent(PlayerEvent.OnPlayPause)
                VoiceResult.ok("paused")
            }
            // OnPlayPause toggles on the engine's isPlaying, which is false while buffering,
            // so toggling here would start playback instead of pausing it.
            state.isBuffering -> VoiceResult.fail("buffering")
            else -> VoiceResult.ok("already_paused")
        }

        VoiceCommand.Resume -> when {
            state.isPlaying -> VoiceResult.ok("already_playing")
            else -> {
                onEvent(PlayerEvent.OnPlayPause)
                VoiceResult.ok("resumed")
            }
        }

        is VoiceCommand.SeekBy -> when {
            timeline.isLive -> VoiceResult.fail("live")
            else -> {
                onEvent(PlayerEvent.OnSeekBy(deltaMs = command.deltaMs))
                VoiceResult.ok(if (command.deltaMs > 0) "seeked_forward" else "seeked_back")
            }
        }

        is VoiceCommand.SeekTo -> when {
            timeline.isLive -> VoiceResult.fail("live")
            timeline.duration > 0L && command.positionMs >= timeline.duration -> VoiceResult.fail("beyond_end")
            else -> {
                onEvent(PlayerEvent.OnSeekTo(position = command.positionMs))
                VoiceResult.ok("seeked_to")
            }
        }

        VoiceCommand.NextEpisode -> when {
            state.nextEpisode != null -> {
                onEvent(PlayerEvent.OnPlayNextEpisode)
                VoiceResult.ok("next_episode")
            }
            else -> VoiceResult.fail("no_next_episode")
        }

        VoiceCommand.SkipIntro -> when {
            state.activeSkipInterval != null -> {
                onEvent(PlayerEvent.OnSkipIntro)
                VoiceResult.ok("skipped_intro")
            }
            else -> VoiceResult.fail("no_intro")
        }

        VoiceCommand.Stop -> {
            exitPlayer()
            VoiceResult.ok("stopped")
        }

        // "Play <title>" while something is playing: keep playing while the title is looked up
        // (so a failed lookup interrupts nothing), then exit the player exactly like pressing
        // Back (progress saved, player released) and let the app-level handler open the title.
        is VoiceCommand.Play -> VoiceResult.ok(VoiceResult.PLAY_READY_WHILE_PLAYING)
        is VoiceCommand.OpenTitle -> {
            exitPlayer()
            null
        }

        // Search has no "go back to what I was watching" path, so ask the user to stop first.
        is VoiceCommand.Search -> VoiceResult.fail("stop_playback_first")
    }
}
