package io.github.usmandurrany.tether.portal

import kotlinx.serialization.Serializable

/*
 * Upload format v1, as sent by tether-debug's RemoteLogSink.
 * The reference is docs/debug-tools.md in tether-xmpp.
 */

@Serializable
data class UploadBatch(
    val v: Int,
    val device: UploadDevice,
    val session: UploadSession,
    val dropped: Long = 0,
    val entries: List<UploadEntry>,
)

@Serializable
data class UploadDevice(
    val installId: String,
    val platform: String,
    val osVersion: String? = null,
    val model: String? = null,
    val appId: String? = null,
    val appVersion: String? = null,
)

@Serializable
data class UploadSession(val id: String, val startedAt: Long, val label: String? = null)

@Serializable
data class UploadEntry(
    val seq: Long,
    val ts: Long,
    val level: String,
    val tag: String,
    val account: String? = null,
    val dir: String? = null,
    val text: String,
    val stanza: UploadStanza? = null,
    val error: String? = null,
)

@Serializable
data class UploadStanza(
    val name: String,
    val type: String? = null,
    val id: String? = null,
    val from: String? = null,
    val to: String? = null,
    val payload: String? = null,
)

/** Rejects uploads that would store junk or let one client bloat the database. */
object UploadRules {
    val LEVELS = setOf("verbose", "debug", "info", "warn", "error")
    private val ID = Regex("[A-Za-z0-9._:-]{1,128}")
    const val MAX_ENTRIES = 2_000
    const val MAX_FIELD = 1_024
    const val MAX_TEXT = 1_048_576

    /** Returns why [batch] is invalid, or null. */
    fun problem(batch: UploadBatch): String? {
        if (batch.v != 1) return "unsupported version ${batch.v}"
        if (!ID.matches(batch.device.installId)) return "invalid device.installId"
        if (!ID.matches(batch.session.id)) return "invalid session.id"
        if (batch.entries.size > MAX_ENTRIES) return "more than $MAX_ENTRIES entries"
        val fields = listOfNotNull(
            batch.device.platform, batch.device.osVersion, batch.device.model, batch.device.appId,
            batch.device.appVersion, batch.session.label,
        )
        if (fields.any { it.length > MAX_FIELD }) return "device or session field too long"
        for (e in batch.entries) {
            if (e.level !in LEVELS) return "invalid level '${e.level.take(16)}'"
            if (e.dir != null && e.dir != "in" && e.dir != "out") return "invalid dir"
            if (e.text.length > MAX_TEXT) return "entry text too long"
            val short = listOfNotNull(e.tag, e.account, e.stanza?.name, e.stanza?.type, e.stanza?.id, e.stanza?.from, e.stanza?.to, e.stanza?.payload)
            if (short.any { it.length > MAX_FIELD }) return "entry field too long"
        }
        return null
    }
}
