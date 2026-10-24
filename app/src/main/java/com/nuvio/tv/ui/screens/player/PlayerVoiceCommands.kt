package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.voice.VoiceCommand
import com.nuvio.tv.core.voice.VoiceResult
import com.nuvio.tv.data.local.StreamAutoPlayMode

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

        VoiceCommand.NextEpisode -> nextEpisode(state)

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

/**
 * Mirrors the early returns of PlayerRuntimeController.playNextEpisode, which otherwise fail
 * silently, so Alexa can say why nothing happened instead of claiming success.
 */
private fun PlayerViewModel.nextEpisode(state: PlayerUiState): VoiceResult {
    val next = state.nextEpisode
    val autoPlay = state.postPlayMode as? PostPlayMode.AutoPlay
    val settings = controller.currentPlayerSettingsForReport
    // With Manual auto-play (and no "auto-play next episode" / binge-group preference) the
    // player opens the next episode's source list instead of starting it.
    val opensSourcePicker = settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL &&
        !settings.streamAutoPlayNextEpisodeEnabled &&
        !settings.streamAutoPlayPreferBingeGroupForNextEpisode
    return when {
        next == null || controller.nextEpisodeVideo == null -> VoiceResult.fail("no_next_episode")
        !next.hasAired -> VoiceResult.fail("next_not_aired")
        controller.isPartyControlLocked -> VoiceResult.fail("party_locked")
        autoPlay != null && (autoPlay.searching || autoPlay.countdownSec != null) ->
            VoiceResult.ok("next_episode_starting")
        else -> {
            onEvent(PlayerEvent.OnPlayNextEpisode)
            if (opensSourcePicker) VoiceResult.ok("next_episode_pick_source") else VoiceResult.ok("next_episode")
        }
    }
}
