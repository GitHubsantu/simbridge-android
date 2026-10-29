package `in`.imdevops.simbridge.api

import `in`.imdevops.simbridge.security.ApiKeys
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

sealed interface ServerState {
    data object Stopped : ServerState
    data object Starting : ServerState
    /** Only reported after a real TCP connection to the loopback port succeeded. */
    data class Running(val port: Int) : ServerState
    data class Failed(val reason: String) : ServerState
}

/** Everything the routes need from the app, so the server has no Android dependency. */
class ApiContext(
    val versionName: String,
    val sdkInt: Int,
    val isSendSmsGranted: () -> Boolean,
    val isAuthorized: (String?) -> Boolean,
)

class ApiServer(private val context: ApiContext) {
    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val lock = Mutex()
    private var engine: EmbeddedServer<*, *>? = null

    suspend fun start(port: Int = DEFAULT_PORT) = lock.withLock {
        if (engine != null) return@withLock
        _state.value = ServerState.Starting
        withContext(Dispatchers.IO) {
            try {
                // Loopback only: nothing on the LAN can reach this socket.
                val server = embeddedServer(CIO, port = port, host = LOOPBACK) { installRoutes(context) }
                server.start(wait = false)
                engine = server
                if (awaitListening(port)) {
                    _state.value = ServerState.Running(port)
                } else {
                    server.stop(200, 500)
                    engine = null
                    _state.value = ServerState.Failed("Server started but port $port did not accept connections")
                }
            } catch (e: Exception) {
                engine = null
                _state.value = ServerState.Failed("${e.javaClass.simpleName}: ${e.message ?: "unknown error"}")
            }
        }
    }

    suspend fun stop() = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                engine?.stop(500, 1500)
            } finally {
                engine = null
                _state.value = ServerState.Stopped
            }
        }
    }

    private suspend fun awaitListening(port: Int): Boolean {
        repeat(15) {
            try {
                Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), 300) }
                return true
            } catch (_: Exception) {
                delay(200)
            }
        }
        return false
    }

    companion object {
        const val DEFAULT_PORT = 8080
        const val LOOPBACK = "127.0.0.1"
    }
}

private fun Application.installRoutes(context: ApiContext) {
    routing {
        get("/api/v1/health") {
            if (!call.authorize(context)) return@get
            call.respondJson(
                HttpStatusCode.OK,
                """{"success":true,"status":"ok","server":"running","version":${JsonText.quote(context.versionName)},"api_version":"v1"}""",
            )
        }
        get("/api/v1/info") {
            if (!call.authorize(context)) return@get
            // sms_send / tunnel report implementation state, not runtime health. Both are false until built.
            call.respondJson(
                HttpStatusCode.OK,
                """{"success":true,"app":"SimBridge Gateway","version":${JsonText.quote(context.versionName)},"api_version":"v1","android_sdk":${context.sdkInt},"capabilities":{"sms_send":false,"tunnel":false},"permissions":{"send_sms":${context.isSendSmsGranted()}}}""",
            )
        }
    }
}

private suspend fun ApplicationCall.authorize(context: ApiContext): Boolean {
    val token = ApiKeys.parseBearer(request.headers[HttpHeaders.Authorization])
    if (context.isAuthorized(token)) return true
    // Same response for "no key set", "no header" and "wrong key" so nothing is revealed.
    response.header(HttpHeaders.WWWAuthenticate, "Bearer")
    respondJson(
        HttpStatusCode.Unauthorized,
        """{"success":false,"error":{"code":"UNAUTHORIZED","message":"A valid API key is required."}}""",
    )
    return false
}

private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: String) =
    respondText(body, ContentType.Application.Json, status)
