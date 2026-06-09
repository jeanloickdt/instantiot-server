# InstantIoT Server — Architecture

> **Self-hosted** server that relays, in real time, the communication between
> IoT boards (ESP32 / Arduino) and the InstantIoT mobile app.
> Stack: **Kotlin 2.3 + Ktor 3.4 + Netty + SQLite (Exposed)**. JDK 21. AGPLv3.
>
> Entry point for picking up the code. Read end to end (~12 min) and you can
> start coding. For the stabilization backlog see [`STABILIZATION.md`](STABILIZATION.md).
> All `file:line` refs are under `src/main/kotlin/com/jeanloickdt/`.

---

## 1. Overview — the 3 network channels

The server exposes **3 distinct network surfaces** in **one JVM process**.

```
                         ┌───────────────────────────────────┐
                         │       INSTANTIOT SERVER           │
                         │       (1 JVM process)             │
   ESP32 / Arduino       │   ┌───────────────────────────┐   │
   ───────────────TCP────┼──▶│  TCP Device Relay  :9001  │   │
   binary frames         │   │  (dedicated thread pool)  │   │
                         │   └────────────┬──────────────┘   │
   Mobile app            │   ┌────────────▼──────────────┐   │
   ───────────WebSocket──┼──▶│  WS App Relay  /ws/app    │   │
   frames + control      │   │  (HTTP port, :8080)       │   │
                         │   └────────────┬──────────────┘   │
   Admin panel / API     │   ┌────────────▼──────────────┐   │
   ───────────HTTP───────┼──▶│  REST API + SPA  :8080    │   │
   browser / app         │   └───────────────────────────┘   │
                         └───────────────────────────────────┘
```

| Channel | Port | Who connects | Protocol |
|---|---|---|---|
| **TCP Device Relay** | 9001 (`tcp.port`) | ESP32 / Arduino | `iWidgets v1` binary frames + token handshake |
| **WS App Relay** | 8080 `/ws/app` | Mobile app | WebSocket: binary frames + JSON control messages |
| **REST API** | 8080 (`http.port`) | App + browser | HTTP/JSON + serves the static admin panel |

> Ports are **auto-bound**: if 8080/9001 are taken, the server tries +1…+5
> (`PortFinder`). The actually-used ports are announced over mDNS (the HTTP
> port is the service port; the TCP port travels in a `tcpPort` TXT record).

---

## 2. The core: the RELAY

The server is first and foremost a **bidirectional relay**. The
`SessionRegistry` (global singleton) holds every live session and buffer in RAM.

```
                    ┌──────────────────────────────────────┐
                    │          SessionRegistry             │
                    ├──────────────────────────────────────┤
                    │ deviceSessions  : DeviceId → Session  │
                    │ appSessions     : UserId   → [Session]│  (multi-device)
                    │ deviceOutboxes  : DeviceId → Outbox   │  (bounded, backpressure)
                    │ lastPayloads    : WidgetId → payload  │  ← RAM, sub-ms read
                    │ historyBuffer        (opaque events)  │  ← 5s flush
                    │ numericHistoryBuffer (raw numeric)    │  ← 5s flush
                    │ knownWidgetIds  (auto-register cache) │
                    └──────────────────────────────────────┘
```

### DEVICE → APP flow (a sensor sends a value) — `relay/DeviceRelay.kt`

```
ESP sends an iWidgets v1 frame
   │
   ▼  handleDeviceFrame()
   │ 0. if TYPE == 0xFE (heartbeat) → return immediately (no dispatch)
   │ 1. extract widgetId + payload          (FrameParser)
   │ 2. auto-register widget if new          (knownWidgetIds + INSERT OR IGNORE)
   │ 3. lastPayloads[widgetId] = base64      (RAM)
   │ 4. historyBuffer += event               (→ 5s flush)
   │ 5. if numeric → HistoryAggregators.{minute,hour,day}.collect()
   │    (+ numericHistoryBuffer if raw tier enabled)
   │ 6. broadcastToApps(projectId, frame)    (intact frame, DEV_COUNT=0)
   ▼
WS App sessions of that project → mobile app updates the widget
```

### APP → DEVICE flow (the user taps a button) — `relay/AppRelay.kt`

```
App sends a binary frame over the WebSocket
   │
   ▼  relayFrameToDevices()
   │ 1. extractDeviceIds(frame)  → target device UUIDs
   │ 2. in-RAM ownership check: device.ownerId == userId  (else command_failed FORBIDDEN)
   │ 3. trimDeviceHeader(frame)  → DEV_COUNT=0, recompute LEN + CRC8
   │ 4. DeviceOutbox.send()      → bounded Channel(8), handles TCP backpressure
   ▼
DeviceOutbox consumer → device's TCP socket → ESP receives the command
   │
   └─ device offline / outbox closed → ControlEventBroadcaster.commandFailed → app
```

### Handshakes

**Device (TCP)** — `DeviceRelay.kt`
```
ESP opens TCP :9001
  → soTimeout = 10s (provisional, for the handshake)
  → sends  [LEN(1B)][PAYLOAD]   where PAYLOAD = "token"  OR  "token:heartbeatMs"
  → server: SHA-256(token) → look up devices.token_hash
  → adaptive soTimeout = heartbeatMs × 2.5, clamped [2s, 120s]
    (legacy "token" only → 90s).  heartbeat=5000ms ⇒ offline detected in ≤12.5s
  → OK: session registered, device.isOnline=true, broadcast device_online
  → then the frame loop
```

**App (WebSocket)** — `AppRelay.kt` (multi-device)
```
App opens /ws/app   (authenticate("jwt"))
  → server verifies the JWT → userId
  → message 1 (Text): projectId      → check project.ownerId == userId (else close)
  → message 2 (Text): connectionInstanceId  (UUID v4, 1 per install)
  → dedup: kick the previous session of the SAME install (other devices coexist)
  → session registered, activeProject set
  → then the loop: binary frames (commands) + JSON text (subscribe_history…)
```

---

## 3. BOOT sequence — `Application.kt`

```
main()                                          Application.kt:81
 ├─ ServerConfig.load()              ~/.instantiot/server.properties
 ├─ PortFinder.findAvailable()       HTTP 8080→8085, TCP 9001→9005 (exit(1) if none)
 ├─ markRunningPorts() ; SystemTrayManager.init()  (or silent headless)
 └─ embeddedServer(Netty, port=httpPort).start(wait=true) ──┐
                                                            │
Application.module()  ──────────────────────────────────────┘   Application.kt:152
 ├─ install ContentNegotiation / StatusPages / CORS(anyHost) / RateLimit("auth" 10/min)
 ├─ DatabaseFactory.init(9 tables)   SQLiteDataSource: WAL, busy_timeout=5000,
 │                                   synchronous NORMAL, cache 32MB, temp MEMORY
 │                                   + createMissingTablesAndColumns + indexes
 ├─ deviceRepository.markAllOffline()  reset stale is_online after a hard kill
 ├─ SHUTDOWN FLUSH HOOKS             ApplicationStopping  AND  Runtime shutdown hook
 │                                   (the JVM hook covers the tray's System.exit)
 ├─ ApplicationStopping → MdnsPublisher.stop()
 ├─ BOOTSTRAP ADMIN                  admin/admin (passwordChanged=false) — see §6
 ├─ configureAuth(userRepository)    JWT HS256
 ├─ startDeviceRelay(tcpPort)        dedicated relay pool + Semaphore(256) + backoff
 ├─ MdnsPublisher.start(displayName) announce _instantiot._tcp
 ├─ job 5s    : flush history buffers + closed aggregator buckets → DB  (try/catch)
 ├─ job 1h    : cleanup history per-tier retention                      (try/catch)
 ├─ job N-h   : backup VACUUM INTO (configurable interval)              (try/catch)
 ├─ job weekly: DatabaseFactory.vacuum()  reclaim freed pages           (try/catch)
 ├─ configureAppRelay()              bind WebSocket /ws/app
 └─ routing { … }                    /api/status + REST routes + static SPA
```

> All four background loops are wrapped in per-iteration `try/catch` (a thrown
> `SQLITE_BUSY` must not kill the flush loop — that would leak the RAM buffers
> to OOM). The relay runs on a **dedicated thread pool** (`device-relay-N`)
> isolated from `Dispatchers.IO` so blocking socket reads never starve DB work.

---

## 4. Database — SQLite (Exposed DSL)

File: `~/.instantiot/instantiot.db` (WAL). Tables created by `DatabaseFactory.init()`.
**No FK constraints** are declared — `owner_id`/`project_id`/`widget_id` are
denormalized TEXT columns (deliberate, "no JOIN"). Referential integrity is
enforced in app code (cascade deletes in route handlers).

```
┌─ users ───────────────┐   ┌─ projects ─────────────┐   ┌─ devices ──────────────┐
│ id            PK      │   │ id            PK       │   │ id            PK       │
│ username      UNIQUE  │   │ owner_id  → users.id   │   │ project_id → projects  │
│ pwd_hash   (bcrypt)   │   │ name                   │   │ owner_id   → users.id  │
│ role  admin|user      │   │ layout_json  (opaque)  │   │ name                   │
│ password_changed      │   │ created_at / updated   │   │ token_hash (SHA-256)   │
│ created_at            │   └────────────────────────┘   │ is_online / last_seen  │
└───────────────────────┘                                │ device_type / connect. │
┌─ widgets ─────────────┐                                └────────────────────────┘
│ id (=widget protocolId)│
│ project_id / owner_id │
│ type                  │
│ last_payload (base64) │
│ last_seen_at          │
└───────────────────────┘

── HISTORY / TIME-SERIES ───────────────────────────────────────────────
widget_history          opaque (non-numeric) events       default 1 day
widget_history_numeric  raw numeric samples (opt-in)       default 1 day
widget_history_min      1-min  buckets {avg,min,max,count} default 90 days
widget_history_hour     1-hour buckets {avg,min,max,count} default 365 days
widget_history_day      1-day  buckets {avg,min,max,count} default unlimited (-1)
```

Every history row also carries denormalized `project_id` + `owner_id` (scoping
and cleanup without a JOIN). Aggregate tables have a UNIQUE index
`(widget_id, COALESCE(series_id,''), bucket_at)` → idempotent `INSERT OR IGNORE`.

> **Migration**: `createMissingTablesAndColumns()` adds missing columns at boot.
> **Golden rule**: every new column must be `.nullable()` or `.default(...)`,
> else the `ALTER` fails on existing rows. Never rename a column (Exposed does
> not track renames).

---

## 5. History — 3 independent tiers + raw + opaque

Every numeric sample feeds **all 3 aggregate tiers directly** (no cascade — the
daily average is over ALL samples, not an average of averages → perfect fidelity).

```
ESP : numeric sample (e.g. gauge = 23.5)
        │
        ├──▶ numericHistoryBuffer        (RAW tier, opt-in, default ON)
        │
        └──▶ HistoryAggregators (RAM, TierAggregator + BucketAccumulator):
               ├─ minute  60 s bucket      keyed by (widgetId, seriesId?, bucketAt)
               ├─ hour    3600 s bucket     bucketAt = (ts / size) * size
               └─ day     86400 s bucket    aggregates: min, max, avg(=sum/count), count

  every 5 s ──▶ flush CLOSED buckets (bucketAt+size ≤ now) → widget_history_* tables
                + emit bucket_updated WS event to subscribed apps
  every 1 h ──▶ cleanup: delete rows older than the per-tier retention
  clean shutdown ──▶ flush ALL buckets (incl. the in-progress one) → 0 loss
```

| Tier | Table | Typical UI usage |
|---|---|---|
| opaque | `widget_history` | non-numeric widgets (buttons, switches, dpad) |
| raw | `widget_history_numeric` | high-resolution zoom — admin opt-in |
| minute | `widget_history_min` | 1 day → 1 week view |
| hour | `widget_history_hour` | 1 week → 1 month view |
| day | `widget_history_day` | long-term view (months / years) |

> ⚠️ **No `last` value is persisted** in any tier — only min/max/avg/count. The
> "last value" comes from `SessionRegistry.lastPayloads` (RAM).

---

## 6. Auth & security

```
JWT HS256
 ├─ secret : ~/.instantiot/secret.key  (2×UUID, generated 1st boot, 600 perms)
 ├─ expiry : 1 year (LAN server, no short rotation — no refresh/revocation yet)
 ├─ claims : subject=userId, issuer instantiot-server, audience instantiot-app
 └─ every authenticate("jwt") route → verify token + look up user in DB
    (role is NOT in the token — re-read from DB on each admin check)

Roles
 ├─ user  : CRUD on THEIR OWN projects / devices / widgets (ownerId == subject, else 404)
 └─ admin : everything + admin panel (manual role!="admin" check per handler)

Admin bootstrap                                  Application.kt (§ boot)
 ├─ 1st start, no admin → create admin/admin  with passwordChanged=false
 │                        → login returns passwordChanged=false (admin panel forces change)
 └─ ~/.instantiot/reset-admin file present → reset admin pwd = "admin"
                                             (passwordChanged=false) + delete file
```

> **Admin password recovery** (forgotten): no network route. The owner creates
> `~/.instantiot/reset-admin` on the machine and restarts. Secured by filesystem
> access = proof of machine ownership.
>
> **Ownership model**: list/create endpoints scope by the JWT subject;
> single-resource `{id}` endpoints load the row then return **404** (not 403) if
> `ownerId != subject`, to avoid leaking existence.

---

## 7. The iWidgets v1 binary frame — `relay/FrameParser.kt`

The wire format shared by the Arduino lib, the server, and the app.

```
 AA │ VER │ LEN(2B LE) │ DEV_COUNT │ [DEV_LEN│DEV_ID]×N │ WID_LEN │ WID │ TYPE │ EVENT │ PAYLOAD… │ CRC8
```

| Field | Off | Size | Notes |
|---|---|---|---|
| SYNC | 0 | 1 | `0xAA` |
| VERSION | 1 | 1 | `0x01` |
| LEN | 2 | 2 | little-endian; length of body (DEV_COUNT..PAYLOAD) |
| DEV_COUNT | 4 | 1 | # target devices; **0 for device→app** |
| [DEV_LEN\|DEV_ID]×N | 5 | 1+DEV_LEN | device UUIDs (app→device only) |
| WID_LEN | — | 1 | widgetId length |
| WID | — | WID_LEN | widgetId (UTF-8) |
| TYPE | — | 1 | widget type (GAUGE 0x03 … CHART 0x09 …; **heartbeat 0xFE**) |
| EVENT | — | 1 | event/command code |
| PAYLOAD | — | var | opaque; `= (4 + LEN) − offset` |
| CRC8 | size−1 | 1 | CRC-8/SMBUS (poly 0x07) over the body |

- **Direction marker** = `DEV_COUNT`: device→server carries `0`; app→device carries
  N UUIDs that the server strips via `trimDeviceHeader` (recomputing LEN + CRC8)
  before forwarding, so the ESP always sees a DEV_COUNT=0 frame.
- Floats are **4-byte little-endian IEEE-754**.
- `isValid()` checks sync + version + LEN/size coherence + CRC8 (both directions).
- `hashDeviceToken()` = SHA-256 hex; the cleartext device token is never stored.

---

## 8. REST routes

| Method | Path | Auth | Role / note |
|---|---|---|---|
| GET | `/api/status` | — | server state, `setup_required`, `tcpPort` |
| POST | `/api/login` | — (10/min) | → `{token, role, passwordChanged}` (panel forces change if false) |
| POST | `/api/register` | — (10/min) | if `registration.open` ; user regex, pwd 8-128 |
| PATCH | `/api/users/me/password` | JWT | change own password (clears must-change flag) |
| GET/POST | `/api/projects` | JWT | list / create (scoped by owner) |
| GET/PATCH/DELETE | `/api/projects/{id}` `/name` `/layout` | JWT | owner check → 404; DELETE cascades |
| GET/POST | `/api/devices` `/projects/{id}/devices` | JWT | list / register (token shown once) |
| PATCH/DELETE | `/api/devices/{id}/name` `/{id}` `/renew-token` | JWT | rename / delete / renew (kicks TCP) |
| POST/DELETE/GET | `/api/projects/{id}/widgets` `/bulk` `/widgets/{id}` `/states` | JWT | register (idempotent) / delete / states |
| GET | `/api/widgets/{id}/history?from&to&seriesId&granularity` | JWT | `raw\|min\|hour\|day` |
| GET | `/api/admin/stats` `/server-info` `/users` | JWT admin | metrics / users |
| PATCH | `/api/admin/config` `/history-config` `/registration/config` `/backup/config` | JWT admin | config (ports need restart) |
| GET/POST | `/api/admin/backup/list` `/now` `/restore` | JWT admin | snapshot / restore (restart required) |
| POST | `/api/admin/users/{id}/reset-password` `/api/admin/restart` | JWT admin | reset a user / restart |

> Known inconsistencies (STABILIZATION P1-2): `/login` & `/register` return bare
> strings instead of `{error}`; `StatusPages` returns text; no `/api/v1` versioning.

---

## 9. Module tree — `src/main/kotlin/com/jeanloickdt/`

```
Application.kt          boot: main() + Application.module() + background loops
common/                 ServerConfig, PortFinder, SystemTrayManager, StatusResponse
database/               DatabaseFactory — SQLite hardening, tables, migrations, vacuum()
auth/                   JWT, login/register, admin panel, user mgmt   (data/ + domain/)
device/                 ESP/Arduino registration, tokens, DeviceType   (data/ + domain/)
project/                user projects (opaque dashboard layout)        (data/ + domain/)
widget/                 widgets + 5-tier time-series history           (data/ + domain/)
relay/                  ★ CORE — DeviceRelay (TCP), AppRelay (WS), FrameParser,
                        SessionRegistry, DeviceOutbox, ControlEventBroadcaster
backup/                 BackupManager — VACUUM INTO snapshot, restore
discovery/              MdnsPublisher / DnsSdPublisher — _instantiot._tcp on the LAN
```

> The on-disk `bin/` directory is a **stale compiled mirror** — ignore it,
> `src/main/kotlin` is authoritative (STABILIZATION P2: delete it).

---

## 10. Concurrency model

| Pool / scope | Used for |
|---|---|
| `Dispatchers.IO` (64) | DB writes, short blocking ops |
| **dedicated relay pool** `device-relay-N` | **blocking** device socket reads (isolated from IO) |
| `Dispatchers.Default` | per-frame CPU parsing (`handleDeviceFrame`) |
| `applicationScope` (= Application) | long-lived: outbox consumers, background loops |

Rules: 1 reader coroutine per device (blocking read on the relay pool, parsing on
Default); 1 bounded `DeviceOutbox` (Channel 8) per device with 1 consumer on the
app scope; background loops guarded by try/catch; shutdown flush guaranteed on
both `ApplicationStopping` and the JVM shutdown hook.

---

## 11. Packaging & distribution

```
build.gradle.kts
 ├─ version  = single source → instantiot-version.properties → ServerConfig.version
 ├─ buildFatJar              → build/libs/instantiot-server-all.jar
 └─ packageInstaller (jpackage) → native .deb / .dmg / .msi

.github/workflows/release.yml : push a v* tag → 3-OS matrix → 3 installers
                                (.deb arm64 via QEMU for Raspberry Pi) → GitHub Release
```

systemd: the `.deb` `postinst` installs `instantiot-server.service` (hardened:
NoNewPrivileges, ProtectSystem=strict, MemoryMax=512M, Restart=on-failure,
journald). No launchd/Windows-Service yet — macOS/Windows run as a tray app.

---

## 12. Quick onboarding — where to start

**Read these 7 files in order:**

1. `Application.kt` — big picture: boot + route wiring + background loops
2. `relay/SessionRegistry.kt` — the live global state
3. `relay/DeviceRelay.kt` — TCP protocol, ESP side
4. `relay/AppRelay.kt` — WebSocket protocol, app side
5. `relay/FrameParser.kt` — decoding the iWidgets v1 binary frames
6. `widget/data/HistoryAggregators.kt` (+ `TierAggregator`, `BucketAccumulator`) — RAM aggregation
7. `database/DatabaseFactory.kt` — DB schema + hardening

**Add a new functional domain:**

1. Create `com/jeanloickdt/<feature>/` with `data/` + `domain/`
2. Define the Exposed `Table` (new columns `nullable()` or `default()`)
3. `<Feature>Repository` interface (domain) + `Sqlite<Feature>Repository` (data)
4. Add the table to `DatabaseFactory.init(...)` in `Application.kt`
5. Create `<Feature>Routes.kt`, wrap in `authenticate("jwt")`, call it in `routing { }`
6. Numeric history → reuse `HistoryAggregators` + the existing 5s flush
7. Real-time broadcast → extend `ControlEventBroadcaster` + `SessionRegistry`

**In one sentence:** the server is a Ktor TCP↔WebSocket relay between IoT boards
and apps, with SQLite persistence and a 3-tier time-series history aggregated in
RAM then flushed every 5 seconds.
```
