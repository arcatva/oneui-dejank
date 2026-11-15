package dev.arcatva.dejank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface Screen {
    data object Loading : Screen
    data object NoRoot : Screen
    data object NoModule : Screen
    data class Broken(val detail: String) : Screen
    data class Ready(val status: Status) : Screen
}

data class UiState(
    val screen: Screen = Screen.Loading,
    /** A command is running; the switches wait for it. */
    val busy: Boolean = false,
)

class MainViewModel : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** String resources to show the user once. */
    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    private val mutex = Mutex()

    /** Reads the module state once. Called on a timer while the screen is visible. */
    suspend fun refresh() {
        val probe = mutex.withLock { Ctl.status() }
        val screen = when (probe) {
            is Ctl.Probe.Ok -> Screen.Ready(probe.status)
            Ctl.Probe.NoRoot -> Screen.NoRoot
            Ctl.Probe.NoModule -> Screen.NoModule
            is Ctl.Probe.Broken -> Screen.Broken(probe.detail)
        }
        _state.update { it.copy(screen = screen) }
    }

    fun retry() {
        _state.update { it.copy(screen = Screen.Loading) }
        viewModelScope.launch { refresh() }
    }

    /** [key] is the name dejank.sh knows a switch by. */
    fun set(key: String, on: Boolean) = act { if (Ctl.set(key, on)) null else R.string.msg_failed }

    fun apply() = act { if (Ctl.apply()) null else R.string.msg_failed }

    fun resume() = act { if (Ctl.resume()) R.string.msg_resumed else R.string.msg_failed }

    /** Hands [onReady] the report on the main thread. */
    fun report(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val text = Ctl.report()
            if (text.isBlank()) {
                _messages.tryEmit(R.string.msg_report_failed)
            } else {
                onReady(text)
                _messages.tryEmit(R.string.msg_report_copied)
            }
        }
    }

    /**
     * Runs one command, then reads the state again. [block] returns the
     * message to show the user, or null for nothing.
     */
    private fun act(block: suspend () -> Int?) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val message = mutex.withLock { block() }
            refresh()
            _state.update { it.copy(busy = false) }
            message?.let { _messages.tryEmit(it) }
        }
    }
}
