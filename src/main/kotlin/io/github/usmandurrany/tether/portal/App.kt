package io.github.usmandurrany.tether.portal

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.GZIPInputStream
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

private val json = Json {
    ignoreUnknownKeys = true // newer SDKs may add fields
    explicitNulls = false
}

/** New entries for live viewers. A slow viewer loses the oldest updates instead of slowing ingest. */
class LiveFeed {
    private val flow = MutableSharedFlow<List<EntryView>>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val updates: SharedFlow<List<EntryView>> get() = flow
    fun publish(entries: List<EntryView>) {
        if (entries.isNotEmpty()) flow.tryEmit(entries)
    }
}

private class HttpError(val status: HttpStatusCode, message: String) : Exception(message)

fun Application.portal(config: Config, store: Store, live: LiveFeed = LiveFeed(), clock: () -> Long = System::currentTimeMillis) {
    install(SecurityHeaders)
    install(SSE)
    install(StatusPages) {
        exception<HttpError> { call, e -> call.respondText(e.message.orEmpty(), status = e.status) }
    }
    install(Authentication) {
        basic("admin") {
            realm = "Tether Debug"
            validate { c ->
                if (constantTimeEquals(c.name, config.adminUser) && constantTimeEquals(c.password, config.adminPassword)) UserIdPrincipal(c.name) else null
            }
        }
    }

    if (config.retentionDays > 0) {
        launch {
            while (isActive) {
                val cutoff = clock() - config.retentionDays * 24L * 3_600_000L
                runCatching { withContext(Dispatchers.IO) { store.prune(cutoff) } }
                    .onSuccess { if (it > 0) environment.log.info("Retention removed $it entries") }
                    .onFailure { environment.log.warn("Retention failed", it) }
                delay(1.hours)
            }
        }
    }

    routing {
        get("/healthz") { call.respondText("ok") }

        post("/api/v1/ingest") {
            val key = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
            if (key == null || config.ingestKeys.none { constantTimeEquals(it, key) }) throw HttpError(HttpStatusCode.Unauthorized, "unknown ingest key")
            val declared = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
            if (declared != null && declared > config.maxUploadBytes) throw HttpError(HttpStatusCode.PayloadTooLarge, "upload too large")
            val gzip = call.request.header(HttpHeaders.ContentEncoding)?.trim()?.lowercase() == "gzip"
            val raw = call.receiveChannel().toInputStream()
            val text = withContext(Dispatchers.IO) {
                val limited = readLimited(raw, config.maxUploadBytes)
                val body = if (gzip) {
                    try {
                        readLimited(GZIPInputStream(limited.inputStream()), config.maxDecodedBytes)
                    } catch (e: java.io.IOException) {
                        throw HttpError(HttpStatusCode.BadRequest, "invalid gzip")
                    }
                } else {
                    limited
                }
                body.decodeToString()
            }
            val batch = try {
                json.decodeFromString<UploadBatch>(text)
            } catch (e: SerializationException) {
                throw HttpError(HttpStatusCode.BadRequest, "invalid JSON: ${e.message?.take(200)}")
            } catch (e: IllegalArgumentException) {
                throw HttpError(HttpStatusCode.BadRequest, "invalid JSON: ${e.message?.take(200)}")
            }
            UploadRules.problem(batch)?.let { throw HttpError(HttpStatusCode.UnprocessableEntity, it) }
            val inserted = withContext(Dispatchers.IO) { store.ingest(batch, clock()) }
            live.publish(inserted)
            call.respondText("""{"accepted":${batch.entries.size},"new":${inserted.size}}""", ContentType.Application.Json)
        }

        authenticate("admin") {
            route("/api/v1") {
                get("/devices") { call.respondJson(withContext(Dispatchers.IO) { store.devices() }) }
                get("/labels") { call.respondJson(withContext(Dispatchers.IO) { store.labels() }) }
                get("/sessions") {
                    val q = call.request.queryParameters
                    call.respondJson(withContext(Dispatchers.IO) { store.sessions(q["installId"], q["label"], q.int("limit", 200, 1_000)) })
                }
                get("/entries") {
                    val q = call.request.queryParameters
                    val result = withContext(Dispatchers.IO) {
                        store.entries(call.filter(), q["before"]?.toLongOrNull(), q["after"]?.toLongOrNull(), q.int("limit", 200, 1_000))
                    }
                    call.respondJson(result)
                }
                get("/sessions/{id}/export") {
                    val id = call.parameters["id"]!!
                    val entries = withContext(Dispatchers.IO) { store.sessionEntries(id, 200_000) }
                    if (entries.isEmpty()) throw HttpError(HttpStatusCode.NotFound, "no such session")
                    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"tether-$id.log\"")
                    call.respondText(entries.joinToString("\n") { it.line() } + "\n", ContentType.Text.Plain)
                }
                delete("/sessions/{id}") {
                    val deleted = withContext(Dispatchers.IO) { store.deleteSession(call.parameters["id"]!!) }
                    if (!deleted) throw HttpError(HttpStatusCode.NotFound, "no such session")
                    call.respondText("{}", ContentType.Application.Json)
                }
                sse("/stream") {
                    val filter = call.filter()
                    val heartbeat = launch {
                        // Keeps proxies from closing an idle stream.
                        while (isActive) {
                            delay(15.seconds)
                            send(ServerSentEvent(comments = "ping"))
                        }
                    }
                    try {
                        live.updates.collect { batch ->
                            batch.filter(filter::matches).forEach { send(ServerSentEvent(json.encodeToString(it), event = "entry", id = it.id.toString())) }
                        }
                    } finally {
                        heartbeat.cancel()
                    }
                }
            }
            staticResources("/", "static")
        }
    }
}

/** No caching of API data, no framing, and a CSP that forbids inline scripts: log text is untrusted. */
private val SecurityHeaders = createApplicationPlugin("SecurityHeaders") {
    onCall { call ->
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("X-Frame-Options", "DENY")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'",
        )
        call.response.header(HttpHeaders.CacheControl, "no-store")
    }
}

private fun ApplicationCall.filter(): EntryFilter {
    val q = request.queryParameters
    fun p(name: String) = q[name]?.trim()?.takeIf { it.isNotEmpty() }
    return EntryFilter(
        installId = p("installId"), sessionId = p("session"), label = p("label"), account = p("account"),
        dir = p("dir"), kind = p("kind"), level = p("level")?.takeIf { it in UploadRules.LEVELS }, tag = p("tag"), text = p("q"),
    )
}

private fun io.ktor.http.Parameters.int(name: String, default: Int, max: Int): Int =
    (this[name]?.toIntOrNull() ?: default).coerceIn(1, max)

private suspend inline fun <reified T> ApplicationCall.respondJson(value: T) =
    respondText(json.encodeToString(value), ContentType.Application.Json)

private fun readLimited(input: InputStream, max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    input.use {
        while (true) {
            val n = it.read(buffer)
            if (n < 0) break
            if (out.size() + n > max) throw HttpError(HttpStatusCode.PayloadTooLarge, "upload too large")
            out.write(buffer, 0, n)
        }
    }
    return out.toByteArray()
}

private fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.encodeToByteArray(), b.encodeToByteArray())

/** One line per entry, like the SDK's console output. */
private fun EntryView.line(): String = buildString {
    append(Instant.ofEpochMilli(ts)).append(' ').append(level.first().uppercaseChar()).append(' ').append(tag)
    account?.let { append(' ').append(it) }
    append(
        when (dir) {
            "out" -> " → "
            "in" -> " ← "
            else -> ": "
        },
    )
    append(text)
    error?.let { append('\n').append(it) }
}
