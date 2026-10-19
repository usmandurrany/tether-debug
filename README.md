# Tether Debug portal

A self-hosted dashboard for logs from apps built on the
[Tether XMPP SDK](https://github.com/usmandurrany/tether-xmpp). During a
testing round, each test device uploads its stanza log and app events every
couple of seconds, and the portal shows them live, per device, per run and
per account.

- **Live view**: new entries stream in without reloading. If the connection drops, the page catches up on what it missed.
- **Devices and sessions**: every install, with platform, OS, app version, the account it last used and when it was last seen. A session is one run of the app; sessions show how many entries the device had to drop while it couldn't reach the portal.
- **Filters**: testing round, device, session, account (bare JID matches every resource), direction, stanza kind, level, and free text over the XML.
- **Stanza view**: indented, highlighted XML for any entry, with a copy button. Each session can be exported as a plain log file.
- **Self-contained**: one container with SQLite, no external database. Old logs are deleted after `TETHER_DEBUG_RETENTION_DAYS`.

The SDK side, and the upload format this portal implements, are documented
in [tether-xmpp/docs/debug-tools.md](https://github.com/usmandurrany/tether-xmpp/blob/main/docs/debug-tools.md).

## Run it

Requires Docker with Compose.

```sh
cp .env.example .env
# fill in TETHER_DEBUG_INGEST_KEYS (openssl rand -hex 24) and TETHER_DEBUG_ADMIN_PASSWORD
docker compose up -d
```

The dashboard is then at `http://127.0.0.1:8080`. Log in with the admin user and password from `.env`.

### Where devices reach it

| Setup | `.env` | App's `endpoint` |
|---|---|---|
| Same LAN as the test devices | `TETHER_DEBUG_BIND=0.0.0.0` | `http://<host-ip>:8080` |
| Public, with automatic HTTPS (Caddy) | `TETHER_DEBUG_DOMAIN=logs.example.com`, then `docker compose --profile https up -d` | `https://logs.example.com` |
| Behind your own reverse proxy | keep `TETHER_DEBUG_BIND=127.0.0.1`, proxy to port 8080 | your proxy's URL |

Android blocks plain `http://` from API 28 unless the app's network security
config allows the host. For a LAN setup, allow it in the test build only:

```xml
<!-- res/xml/network_security_config.xml in the debug source set -->
<network-security-config>
  <domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="false">192.168.1.20</domain>
  </domain-config>
</network-security-config>
```

Over plain HTTP the admin password and the logs travel unencrypted. That is fine on a trusted LAN, but use HTTPS anywhere else.

## Connect an app

```kotlin
val debug = TetherDebug(sinks = listOf(ConsoleSink()))
val remote = RemoteLogSink.http(
    RemoteLogConfig(
        endpoint = "https://logs.example.com",
        ingestKey = BuildConfig.TETHER_DEBUG_KEY,
        device = DeviceInfo.current(installId = storedInstallId, appId = "com.example.chat", appVersion = "1.4.0"),
        label = "beta-3",
    ),
    appScope,
)
debug.addSink(remote)
val client = TetherAndroid.client(context, config, appScope, logger = debug.trafficLogger())
```

## Configuration

All settings are environment variables (see `.env.example`).

| Variable | Default | |
|---|---|---|
| `TETHER_DEBUG_INGEST_KEYS` | required | Comma-separated upload keys, 16+ characters each. Several keys let you rotate between rounds. |
| `TETHER_DEBUG_ADMIN_USER` | `admin` | Dashboard login. |
| `TETHER_DEBUG_ADMIN_PASSWORD` | required | 12+ characters. |
| `TETHER_DEBUG_RETENTION_DAYS` | `14` | Entries older than this are deleted hourly; `0` keeps everything. |
| `TETHER_DEBUG_PORT` | `8080` | Port inside the container. |
| `TETHER_DEBUG_DATA_DIR` | `/data` in Docker | Where `tether-debug.db` lives. Back up this volume to keep logs. |
| `TETHER_DEBUG_MAX_UPLOAD_BYTES` | `2097152` | Largest upload accepted (compressed). The decompressed limit is 16 MB. |

## Security

- **Ingest keys are not secrets**: they ship inside test builds, so anyone with the APK can upload. They only keep random traffic out. Rotate them per round by listing the new key next to the old one, then removing the old one.
- **Viewing needs the admin password.** Every page and API call except uploads and `/healthz` requires it. Comparisons are constant-time.
- **The SDK redacts before upload**: SASL, FAST tokens, passwords, push secrets and upload auth headers never leave the device. Message bodies are uploaded unless the app sets `redactBodies`; turn that on for testers outside your team.
- **Log content is treated as untrusted** in the dashboard: it is inserted as text only, and a strict Content Security Policy blocks inline scripts.
- **Uploads are bounded**: request size, decompressed size (against gzip bombs), entries per batch and field lengths are all capped. Uploads are validated before anything is stored.

## API

Everything under `/api/v1` except `ingest` uses HTTP basic auth.

| Method and path | |
|---|---|
| `POST /api/v1/ingest` | Upload (bearer ingest key). Format in the SDK docs. Returns `{"accepted":n,"new":m}`; re-sent entries are ignored by `(session, seq)`. |
| `GET /api/v1/devices` | Devices, most recently seen first. |
| `GET /api/v1/sessions?installId=&label=` | Sessions. |
| `GET /api/v1/labels` | Testing round labels. |
| `GET /api/v1/entries?…` | Newest first. Filters: `installId`, `session`, `label`, `account`, `dir` (`in`/`out`), `kind` (`message`, `presence`, `iq`, `stream`, `event`, …), `level` (minimum), `tag`, `q` (text). Paging: `before=<id>`, `after=<id>`, `limit` (max 1000). |
| `GET /api/v1/stream?…` | Server-sent events (`event: entry`) for new entries, same filters. |
| `GET /api/v1/sessions/{id}/export` | One session as a text log, in device order. |
| `DELETE /api/v1/sessions/{id}` | Deletes a session and its entries. |
| `GET /healthz` | Liveness, no auth. |

## Development

JDK 17 or newer.

```sh
./gradlew test
TETHER_DEBUG_INGEST_KEYS=dev-key-0123456789abcdef TETHER_DEBUG_ADMIN_PASSWORD=dev-password-1 ./gradlew run
```

Kotlin, [Ktor](https://ktor.io) and SQLite. The dashboard is plain HTML, CSS and JavaScript in `src/main/resources/static`, with no build step.

## License

[Apache License 2.0](LICENSE).
