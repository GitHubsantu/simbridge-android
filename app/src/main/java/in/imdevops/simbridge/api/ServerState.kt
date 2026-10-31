package `in`.imdevops.simbridge.api

sealed interface ServerState {
    data object Stopped : ServerState
    data object Starting : ServerState
    /** Only reported after a real TCP connection to the loopback port succeeded. */
    data class Running(val port: Int) : ServerState
    data class Failed(val reason: String) : ServerState
}
