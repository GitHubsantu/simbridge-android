package `in`.imdevops.simbridge.api

import `in`.imdevops.simbridge.security.ApiKeys
import `in`.imdevops.simbridge.sms.ApiSettings
import `in`.imdevops.simbridge.sms.SendOutcome
import `in`.imdevops.simbridge.sms.SmsRecord
import `in`.imdevops.simbridge.sms.SmsStatus
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.Socket

sealed interface ServerState {
    data object Stopped : ServerState
    data object Starting : ServerState
    /** Only reported after a real TCP connection to the loopback port succeeded. */
    data class Running(val port: Int) : ServerState
    data class Failed(val reason: String) : ServerState
}

data class SendCommand(
    val to: String,
    val body: String,
    val subscriptionId: Int?,
    val requestDelivery: Boolean,
    val idempotencyKey: String?,
)

/** Everything the routes need from the app, so the server has no Android dependency. */
class ApiContext(
    val versionName: String,
    val sdkInt: Int,
    val isSendSmsGranted: () -> Boolean,
    val isAuthorized: (String?) -> Boolean,
    val getMessage: suspend (String) -> SmsRecord?,
    val listMessages: suspend (limit: Int, offset: Int) -> List<SmsRecord>,
    val sendMessage: suspend (SendCommand) -> SendOutcome,
    val limits: () -> ApiSettings,
    val tunnelStatus: () -> String,
)

class ApiServer(private val context: ApiContext) {
    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val lock = Mutex()
    private var engine: EmbeddedServer<*, *>? = null
    private val throttle = FailedAuthThrottle()

    suspend fun start(port: Int = DEFAULT_PORT) = lock.withLock {
        if (engine != null) return@withLock
        _state.value = ServerState.Starting
        withContext(Dispatchers.IO) {
            try {
                // Loopback only: nothing on the LAN can reach this socket.
                val server = embeddedServer(CIO, port = port, host = LOOPBACK) { installRoutes(context, throttle) }
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

internal const val MAX_BODY_BYTES = 8192L
private const val BODY_READ_TIMEOUT_MS = 10_000L
private val MESSAGE_ID = Regex("^[0-9a-f]{32}$")
private val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9_.:-]{1,64}$")

internal fun Application.installRoutes(context: ApiContext, throttle: FailedAuthThrottle = FailedAuthThrottle()) {
    routing {
        get("/api/v1/health") {
            if (!call.authorize(context, throttle)) return@get
            call.respondJson(
                HttpStatusCode.OK,
                """{"success":true,"status":"ok","server":"running","version":${JsonText.quote(context.versionName)},"api_version":"v1"}""",
            )
        }
        get("/api/v1/info") {
            if (!call.authorize(context, throttle)) return@get
            val l = context.limits()
            // capabilities report what is implemented, not runtime health. The tunnel is implemented but still being verified on device.
            call.respondJson(
                HttpStatusCode.OK,
                """{"success":true,"app":"SimBridge Gateway","version":${JsonText.quote(context.versionName)},"api_version":"v1","android_sdk":${context.sdkInt},"capabilities":{"sms_engine":true,"sms_send_api":true,"tunnel":true},"permissions":{"send_sms":${context.isSendSmsGranted()}},"limits":{"per_minute":${l.perMinute},"per_day":${l.perDay},"max_message_chars":${l.maxMessageChars}}}""",
            )
        }
        get("/api/v1/messages/{id}") {
            if (!call.authorize(context, throttle)) return@get
            val id = call.parameters["id"].orEmpty()
            val record = if (MESSAGE_ID.matches(id)) context.getMessage(id) else null
            if (record == null) {
                call.respondJson(HttpStatusCode.NotFound, errorJson("NOT_FOUND", "Unknown message id."))
            } else {
                call.respondJson(HttpStatusCode.OK, """{"success":true,"message":${record.toJson()}}""")
            }
        }
        get("/api/v1/messages") {
            if (!call.authorize(context, throttle)) return@get
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)
            val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
            val items = context.listMessages(limit, offset).joinToString(",") { it.toJson() }
            call.respondJson(
                HttpStatusCode.OK,
                """{"success":true,"messages":[$items],"limit":$limit,"offset":$offset}""",
            )
        }
        get("/api/v1/tunnel/status") {
            if (!call.authorize(context, throttle)) return@get
            call.respondJson(HttpStatusCode.OK, context.tunnelStatus())
        }
        post("/api/v1/messages") {
            if (!call.authorize(context, throttle)) return@post
            if (!call.request.contentType().match(ContentType.Application.Json)) {
                call.respondJson(
                    HttpStatusCode.UnsupportedMediaType,
                    errorJson("UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json."),
                )
                return@post
            }
            // A declared, bounded length is required: chunked bodies are refused rather than buffered.
            val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (length == null || call.request.headers[HttpHeaders.TransferEncoding] != null) {
                call.respondJson(
                    HttpStatusCode.LengthRequired,
                    errorJson("LENGTH_REQUIRED", "Send a Content-Length header; chunked bodies are not accepted."),
                )
                return@post
            }
            if (length > MAX_BODY_BYTES) {
                call.respondJson(
                    HttpStatusCode.PayloadTooLarge,
                    errorJson("PAYLOAD_TOO_LARGE", "Body must be at most $MAX_BODY_BYTES bytes."),
                )
                return@post
            }
            val text = try {
                withTimeout(BODY_READ_TIMEOUT_MS) { call.receiveText() }
            } catch (_: TimeoutCancellationException) {
                call.respondJson(HttpStatusCode.RequestTimeout, errorJson("REQUEST_TIMEOUT", "Timed out reading the body."))
                return@post
            }
            val key = call.request.headers["Idempotency-Key"]
            if (key != null && !IDEMPOTENCY_KEY.matches(key)) {
                call.respondJson(
                    HttpStatusCode.BadRequest,
                    errorJson("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be 1-64 characters of A-Z a-z 0-9 _ . : -"),
                )
                return@post
            }
            val parsed = when (val p = SendRequestParser.parse(text)) {
                is ParsedSend.Ok -> p
                is ParsedSend.Bad -> {
                    call.respondJson(HttpStatusCode.BadRequest, errorJson("INVALID_REQUEST", p.message))
                    return@post
                }
            }
            val outcome = context.sendMessage(
                SendCommand(parsed.to, parsed.message, parsed.subscriptionId, parsed.requestDelivery, key),
            )
            when (outcome) {
                is SendOutcome.Created -> {
                    val r = outcome.record
                    if (r.status == SmsStatus.FAILED) {
                        call.respondJson(
                            HttpStatusCode.BadGateway,
                            """{"success":false,"message_id":${JsonText.quote(r.id)},"status":"FAILED","error":{"code":${JsonText.quote(r.errorCode ?: "SUBMIT_FAILED")},"message":${JsonText.quote(r.errorMessage ?: "Android could not accept the message.")}}}""",
                        )
                    } else {
                        // Status is the real current state (normally SUBMITTING), never an optimistic SENT/DELIVERED.
                        call.respondJson(
                            HttpStatusCode.Accepted,
                            """{"success":true,"message_id":${JsonText.quote(r.id)},"status":${JsonText.quote(r.status.name)},"message":"The message has been accepted for processing."}""",
                        )
                    }
                }
                is SendOutcome.Replayed -> call.respondJson(
                    HttpStatusCode.OK,
                    """{"success":true,"message_id":${JsonText.quote(outcome.record.id)},"status":${JsonText.quote(outcome.record.status.name)},"idempotent_replay":true,"message":"This request was already processed; nothing new was sent."}""",
                )
                is SendOutcome.Rejected -> {
                    outcome.retryAfterSeconds?.let { call.response.header(HttpHeaders.RetryAfter, it.toString()) }
                    call.respondJson(statusFor(outcome.code), errorJson(outcome.code, outcome.message))
                }
            }
        }
    }
}

private fun statusFor(code: String): HttpStatusCode = when (code) {
    "INVALID_NUMBER", "EMPTY_MESSAGE", "MESSAGE_TOO_LONG", "INVALID_SUBSCRIPTION", "SIM_SELECTION_REQUIRED" ->
        HttpStatusCode.BadRequest
    "RATE_LIMITED" -> HttpStatusCode.TooManyRequests
    "IDEMPOTENCY_KEY_REUSED" -> HttpStatusCode.UnprocessableEntity
    "IDEMPOTENCY_IN_PROGRESS" -> HttpStatusCode.Conflict
    "BUSY", "PERMISSION_DENIED", "NO_ACTIVE_SIM" -> HttpStatusCode.ServiceUnavailable
    else -> HttpStatusCode.InternalServerError
}

private fun errorJson(code: String, message: String) =
    """{"success":false,"error":{"code":${JsonText.quote(code)},"message":${JsonText.quote(message)}}}"""

private suspend fun ApplicationCall.authorize(context: ApiContext, throttle: FailedAuthThrottle): Boolean {
    val token = ApiKeys.parseBearer(request.headers[HttpHeaders.Authorization])
    if (context.isAuthorized(token)) return true
    // Same body for "no key set", "no header" and "wrong key" so nothing is revealed.
    val retryAfter = throttle.recordFailure()
    if (retryAfter != null) {
        response.header(HttpHeaders.RetryAfter, retryAfter.toString())
        respondJson(
            HttpStatusCode.TooManyRequests,
            errorJson("TOO_MANY_FAILED_ATTEMPTS", "Too many failed authentication attempts."),
        )
    } else {
        response.header(HttpHeaders.WWWAuthenticate, "Bearer")
        respondJson(HttpStatusCode.Unauthorized, errorJson("UNAUTHORIZED", "A valid API key is required."))
    }
    return false
}

private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: String) =
    respondText(body, ContentType.Application.Json, status)
