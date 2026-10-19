package io.github.usmandurrany.tether.portal

import io.ktor.client.request.basicAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PortalTest {
    private val dir = Files.createTempDirectory("tether-debug-test")
    private val store = Store(dir.resolve("test.db"))
    private val config = Config(
        dataDir = dir,
        ingestKeys = setOf("ingest-key-0123456789"),
        adminUser = "admin",
        adminPassword = "correct horse battery",
        retentionDays = 0,
    )

    @AfterTest
    fun cleanUp() {
        store.close()
        dir.toFile().deleteRecursively()
    }

    private fun batch(session: String = "s1", seqs: LongRange = 1L..2L, dropped: Long = 0, label: String? = "beta-3") = buildString {
        append("""{"v":1,"device":{"installId":"install-1","platform":"android","osVersion":"14 (API 34)","model":"Pixel 8","appVersion":"1.0"},""")
        append("""  "session":{"id":"$session","startedAt":1000${label?.let { ",\"label\":\"$it\"" } ?: ""}},"dropped":$dropped,"entries":[""")
        append(
            seqs.joinToString(",") { seq ->
                if (seq % 2 == 1L) {
                    """{"seq":$seq,"ts":${1000 + seq},"level":"debug","tag":"stanza","account":"alice@example.com/phone","dir":"out",
                       "text":"<message to='bob@example.com' id='m$seq'><body>hi $seq</body></message>",
                       "stanza":{"name":"message","id":"m$seq","to":"bob@example.com"},"futureField":true}"""
                } else {
                    """{"seq":$seq,"ts":${1000 + seq},"level":"info","tag":"connection","text":"Online"}"""
                }
            },
        )
        append("]}")
    }

    private fun ApplicationTestBuilder.portalApp() = application { portal(config, store, clock = { 50_000 }) }

    private suspend fun ApplicationTestBuilder.ingest(body: String, key: String = "ingest-key-0123456789", gzip: Boolean = false) =
        client.post("/api/v1/ingest") {
            header(HttpHeaders.Authorization, "Bearer $key")
            header(HttpHeaders.ContentType, "application/json")
            if (gzip) {
                header(HttpHeaders.ContentEncoding, "gzip")
                setBody(ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(body.encodeToByteArray()) } }.toByteArray())
            } else {
                setBody(body)
            }
        }

    private suspend fun ApplicationTestBuilder.admin(path: String) = client.get(path) { basicAuth("admin", "correct horse battery") }

    @Test
    fun ingestsAndDeduplicatesRetries() = testApplication {
        portalApp()
        val first = ingest(batch())
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals("""{"accepted":2,"new":2}""", first.bodyAsText())
        assertEquals("""{"accepted":2,"new":0}""", ingest(batch()).bodyAsText()) // same batch retried

        val entries = Json.parseToJsonElement(admin("/api/v1/entries").bodyAsText()).jsonArray
        assertEquals(2, entries.size)
        assertEquals(listOf(2L, 1L), entries.map { it.jsonObject["seq"]!!.jsonPrimitive.long }) // newest first
        val devices = Json.parseToJsonElement(admin("/api/v1/devices").bodyAsText()).jsonArray
        assertEquals("alice@example.com/phone", devices.single().jsonObject["lastAccount"]!!.jsonPrimitive.content)
        assertEquals(2L, devices.single().jsonObject["entries"]!!.jsonPrimitive.long)
    }

    @Test
    fun acceptsGzip() = testApplication {
        portalApp()
        assertEquals(HttpStatusCode.OK, ingest(batch(), gzip = true).status)
    }

    @Test
    fun rejectsUnknownKeysAndBadInput() = testApplication {
        portalApp()
        assertEquals(HttpStatusCode.Unauthorized, ingest(batch(), key = "nope").status)
        assertEquals(HttpStatusCode.BadRequest, ingest("{not json").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, ingest(batch().replace("\"v\":1", "\"v\":2")).status)
        assertEquals(HttpStatusCode.UnprocessableEntity, ingest(batch(session = "bad id with spaces")).status)
        assertEquals(HttpStatusCode.UnprocessableEntity, ingest(batch().replace("\"level\":\"info\"", "\"level\":\"loud\"")).status)
    }

    @Test
    fun refusesOversizedUploadsIncludingGzipBombs() = testApplication {
        portalApp()
        val huge = "x".repeat(config.maxDecodedBytes + 1)
        assertEquals(HttpStatusCode.PayloadTooLarge, ingest(huge, gzip = true).status)
        assertEquals(HttpStatusCode.PayloadTooLarge, ingest("y".repeat(config.maxUploadBytes + 1)).status)
    }

    @Test
    fun dashboardNeedsTheAdminPassword() = testApplication {
        portalApp()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/devices").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/devices") { basicAuth("admin", "wrong") }.status)
        assertEquals(HttpStatusCode.OK, admin("/api/v1/devices").status)
        assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
        val page = admin("/")
        assertTrue("Tether Debug" in page.bodyAsText())
        assertTrue(page.headers["Content-Security-Policy"]!!.contains("script-src 'self'"))
    }

    @Test
    fun filters() = testApplication {
        portalApp()
        ingest(batch(seqs = 1L..6L))
        ingest(batch(session = "s2", seqs = 1L..1L, label = null))
        fun seqs(json: String) = Json.parseToJsonElement(json).jsonArray.map { it.jsonObject["seq"]!!.jsonPrimitive.long }
        assertEquals(listOf(5L, 3L, 1L), seqs(admin("/api/v1/entries?session=s1&kind=message").bodyAsText()))
        assertEquals(listOf(6L, 4L, 2L), seqs(admin("/api/v1/entries?session=s1&kind=event").bodyAsText()))
        assertEquals(listOf(6L, 4L, 2L), seqs(admin("/api/v1/entries?session=s1&level=info").bodyAsText()))
        assertEquals(4, seqs(admin("/api/v1/entries?account=alice@example.com").bodyAsText()).size) // bare JID matches resources
        assertEquals(0, seqs(admin("/api/v1/entries?account=alice@example").bodyAsText()).size)
        assertEquals(listOf(3L), seqs(admin("/api/v1/entries?session=s1&q=HI%203").bodyAsText()))
        assertEquals(listOf(5L, 3L, 1L), seqs(admin("/api/v1/entries?label=beta-3&dir=out").bodyAsText()))
        val page = seqs(admin("/api/v1/entries?session=s1&limit=2").bodyAsText())
        assertEquals(listOf(6L, 5L), page)
        val firstId = Json.parseToJsonElement(admin("/api/v1/entries?session=s1&limit=2").bodyAsText()).jsonArray.last().jsonObject["id"]!!.jsonPrimitive.long
        assertEquals(listOf(4L, 3L), seqs(admin("/api/v1/entries?session=s1&limit=2&before=$firstId").bodyAsText()))
        assertEquals("""["beta-3"]""", admin("/api/v1/labels").bodyAsText())
    }

    @Test
    fun countsDropsOnceAndExports() = testApplication {
        portalApp()
        ingest(batch(seqs = 1L..2L, dropped = 7))
        ingest(batch(seqs = 1L..2L, dropped = 7)) // retry of the same batch
        val session = Json.parseToJsonElement(admin("/api/v1/sessions").bodyAsText()).jsonArray.single().jsonObject
        assertEquals(7L, session["dropped"]!!.jsonPrimitive.long)
        val export = admin("/api/v1/sessions/s1/export").bodyAsText()
        assertTrue(export.contains("D stanza alice@example.com/phone → <message to='bob@example.com' id='m1'>"))
        assertTrue(export.contains("I connection: Online"))
        assertEquals(HttpStatusCode.OK, client.delete("/api/v1/sessions/s1") { basicAuth("admin", "correct horse battery") }.status)
        assertEquals("[]", admin("/api/v1/entries").bodyAsText())
    }

    @Test
    fun retentionPrunesOldData() {
        store.ingest(Json { ignoreUnknownKeys = true }.decodeFromString(UploadBatch.serializer(), batch()), now = 1_000)
        assertEquals(2, store.prune(cutoff = 2_000))
        assertTrue(store.devices().isEmpty())
    }

    @Test
    fun liveFilterMatchesLikeTheQuery() {
        val e = EntryView(1, "s1", "i1", null, 1, 1, 1, "warn", "app", "alice@example.com/phone", null, "Something", null, null)
        assertTrue(EntryFilter(account = "alice@example.com", kind = "event", level = "info", text = "some").matches(e))
        assertTrue(!EntryFilter(level = "error").matches(e))
        assertTrue(!EntryFilter(kind = "message").matches(e))
    }
}
