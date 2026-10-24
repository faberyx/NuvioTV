package com.nuvio.tv.core.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes voice commands to whichever screen can handle them.
 *
 * Screens register a [Handler] while they are composed (see `DisposableEffect` in
 * PlayerScreen / MainActivity). Handlers are asked in descending [priority] order (most
 * recent first on ties); returning `null` means "not mine, ask the next one". So while the
 * player is on screen it gets first pick, and the app-level navigation handler is the fallback.
 */
@Singleton
class VoiceCommandBus @Inject constructor() {

    fun interface Handler {
        /** Runs on the main thread. Return null to pass the command on. */
        fun handle(command: VoiceCommand): VoiceResult?
    }

    private class Entry(val priority: Int, val handler: Handler)

    private val handlers = CopyOnWriteArrayList<Entry>()

    private val _pendingSearchQuery = MutableStateFlow<String?>(null)

    /** Set by the navigation handler; consumed by SearchViewModel once the Search screen exists. */
    val pendingSearchQuery: StateFlow<String?> = _pendingSearchQuery.asStateFlow()

    fun requestSearch(query: String) {
        _pendingSearchQuery.value = query
    }

    fun consumeSearch(query: String) {
        _pendingSearchQuery.compareAndSet(query, null)
    }

    /** Registers [handler]; invoke the returned function to unregister. */
    fun register(priority: Int, handler: Handler): () -> Unit {
        val entry = Entry(priority, handler)
        handlers.add(entry)
        return { handlers.remove(entry) }
    }

    suspend fun dispatch(command: VoiceCommand): VoiceResult = withContext(Dispatchers.Main.immediate) {
        val snapshot = handlers.toList().asReversed().sortedByDescending { it.priority }
        for (entry in snapshot) {
            val result = runCatching { entry.handler.handle(command) }
                .getOrElse { return@withContext VoiceResult.fail("error") }
            if (result != null) return@withContext result
        }
        VoiceResult.fail("not_available")
    }

    companion object {
        const val PRIORITY_APP = 0
        const val PRIORITY_PLAYER = 100
    }
}
