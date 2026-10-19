package io.github.usmandurrany.tether.portal

import java.nio.file.Path
import java.nio.file.Paths

/** Portal settings, read from environment variables (see README). */
data class Config(
    val port: Int = 8080,
    val dataDir: Path = Paths.get("data"),
    /** Keys apps upload with. Several keys allow rotation between testing rounds. */
    val ingestKeys: Set<String>,
    val adminUser: String,
    val adminPassword: String,
    /** Entries older than this are deleted; 0 keeps everything. */
    val retentionDays: Int = 14,
    /** Upload limit as received (usually gzip). */
    val maxUploadBytes: Int = 2 * 1024 * 1024,
    /** Limit after decompression, so a small gzip bomb cannot exhaust memory. */
    val maxDecodedBytes: Int = 16 * 1024 * 1024,
) {
    init {
        require(ingestKeys.isNotEmpty() && ingestKeys.all { it.length >= 16 }) { "TETHER_DEBUG_INGEST_KEYS: at least one key of 16+ characters" }
        require(adminUser.isNotBlank()) { "TETHER_DEBUG_ADMIN_USER is empty" }
        require(adminPassword.length >= 12) { "TETHER_DEBUG_ADMIN_PASSWORD: 12+ characters" }
        require(port in 1..65535 && retentionDays >= 0 && maxUploadBytes > 0 && maxDecodedBytes >= maxUploadBytes)
    }

    override fun toString(): String = "Config(port=$port, dataDir=$dataDir, ingestKeys=${ingestKeys.size}, adminUser=$adminUser, retentionDays=$retentionDays)"

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun value(name: String): String? = env[name]?.trim()?.takeIf { it.isNotEmpty() }
            fun required(name: String): String = value(name) ?: throw IllegalArgumentException("$name is not set")
            return Config(
                port = value("TETHER_DEBUG_PORT")?.toInt() ?: 8080,
                dataDir = Paths.get(value("TETHER_DEBUG_DATA_DIR") ?: "data"),
                ingestKeys = required("TETHER_DEBUG_INGEST_KEYS").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                adminUser = value("TETHER_DEBUG_ADMIN_USER") ?: "admin",
                adminPassword = required("TETHER_DEBUG_ADMIN_PASSWORD"),
                retentionDays = value("TETHER_DEBUG_RETENTION_DAYS")?.toInt() ?: 14,
                maxUploadBytes = value("TETHER_DEBUG_MAX_UPLOAD_BYTES")?.toInt() ?: (2 * 1024 * 1024),
            )
        }
    }
}
