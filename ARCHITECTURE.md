# InstantIoT Server — Architecture

> **Self-hosted** server that relays, in real time, the communication between
> IoT boards (ESP32 / Arduino) and the InstantIoT mobile app.
> Stack: **Kotlin + Ktor + Netty + SQLite (Exposed)**. JDK 21.
>
> This document is the entry point for picking up the code. Read it end to
> end (~10 min) and you can start coding.

---

## 1. Overview — the 3 network channels

The server exposes **3 distinct network surfaces**. Everything starts here.

```
                         ┌───────────────────────────────────┐
                         │       INSTANTIOT SERVER           │
                         │       (1 JVM process)             │
                         │                                   │
   ESP32 / Arduino       │   ┌───────────────────────────┐   │
   ───────────────TCP────┼──▶│  TCP Device Relay  :9001  │   │
   binary frames         │   │  (1 coroutine / device)   │   │
                         │   └────────────┬──────────────┘   │
                         │                │                  │
   Mobile app            │   ┌────────────▼──────────────┐   │
   ───────────WebSocket──┼──▶│  WS App Relay  /ws/app    │   │
   frames + control      │   │  (HTTP port, :8080)       │   │
                         │   └────────────┬──────────────┘   │
                         │                │                  │
   Admin panel / API     │   ┌────────────▼──────────────┐   │
   ───────────HTTP───────┼──▶│  REST API + SPA  :8080    │   │
   browser / app         │   └───────────────────────────┘   │
                         │                                   │
                         └───────────────────────────────────┘
```

| Channel | Port | Who connects | Protocol |
|---|---|---|---|
| **TCP Device Relay** | 9001 | ESP32 / Arduino | `iWidgets v1` binary frames + token handshake |
| **WS App Relay** | 8080 `/ws/app` | Mobile app | WebSocket: binary frames + JSON control messages |
| **REST API** | 8080 | App + browser | HTTP/JSON + serves the static admin panel |

> Ports are **auto-bound**: if 8080/9001 are taken, the server tries +1…+5
> (`PortFinder`). The actually-used ports are announced over mDNS.

---

## 2. The core: the RELAY

The server is first and foremost a **bidirectional relay**. The
`SessionRegistry` (global singleton) holds every live session and buffer.

```
                    ┌──────────────────────────────────────┐
                    │          SessionRegistry             │
                    │          (ConcurrentHashMap)         │
                    ├──────────────────────────────────────┤
                    │ deviceSessions  : DeviceId → Session  │
                    │ appSessions     : UserId   → [Session]│
                    │ deviceOutboxes  : DeviceId → Outbox   │
                    │ lastPayloads    : WidgetId → payload  │  ← RAM, sub-ms read
                    │ historyBuffer        (opaque events)  │  ← 5s flush
                    │ numericHistoryBuffer (raw numeric)    │  ← 5s flush
                    │ knownWidgetIds  (auto-register cache) │
                    └──────────────────────────────────────┘
```

### DEVICE → APP flow (a sensor sends a value)

```
ESP sends an iWidgets v1 frame
   │
   ▼
DeviceRelay.handleDeviceFrame()      relay/DeviceRelay.kt
   │ 1. validate CRC8
   │ 2. extract widgetId + payload   (FrameParser)
   │ 3. lastPayloads[widgetId] = payload      (RAM)
   │ 4. historyBuffer += event               (→ 5s flush)
   │ 5. auto-register widget if unknown
   │ 6. if numeric value → HistoryAggregators (min/hour/day)
   │ 7. broadcast the frame to the project's apps
   ▼
ControlEventBroadcaster → WS App sessions
   ▼
Mobile app updates the widget on screen
```

### APP → DEVICE flow (the user taps a button)

```
App sends a binary frame over the WebSocket
   │
   ▼
AppRelay.relayFrameToDevices()       relay/AppRelay.kt
   │ 1. extract widgetId + target device(s)
   │ 2. look up DeviceOutbox by device
   │ 3. queue the frame in the Outbox  (handles TCP backpressure)
   ▼
DeviceOutbox → device's TCP socket
   ▼
ESP receives the command
   │
   └─ if device offline/timeout → ControlEventBroadcaster.commandFailed → app
```

### Handshakes

**Device (TCP)** — `DeviceRelay.kt`
```
ESP opens TCP :9001
  → sends its token (UUID v4)          [10s timeout]
  → server: SHA-256(token) → look up devices.token_hash
  → OK: session registered, device.isOnline=true, broadcast device_online
  → then the frame loop
```

**App (WebSocket)** — `AppRelay.kt` (Option B multi-device)
```
App opens /ws/app  (header: JWT)
  → server verifies the JWT → userId
  → message 1 (Text): projectId
  → message 2 (Text): connectionInstanceId  (UUID v4, 1 per install)
  → dedup: kick the previous session (same userId+projectId+instanceId)
  → session registered
  → then the loop: binary frames (commands) + JSON (subscribe_history…)
```

---

## 3. BOOT sequence — `Application.kt`

```
main()                                          Application.kt:62
 ├─ ServerConfig.load()              ~/.instantiot/server.properties
 ├─ PortFinder.findAvailable()       HTTP 8080→8085, TCP 9001→9005
 ├─ SystemTrayManager.init()         desktop icon (or silent headless)
 └─ embeddedServer(Netty).start()  ──┐
                                     │
Application.module()  ───────────────┘          Application.kt:133
 ├─ install ContentNegotiation / StatusPages / CORS / RateLimit
 ├─ DatabaseFactory.init(tables…)    SQLite WAL + tables + indexes
 ├─ deviceRepository.markAllOffline()  reset stale state
 ├─ ApplicationStopping hooks        flush buffers + stop mDNS
 ├─ BOOTSTRAP ADMIN                  ── see §6
 ├─ configureAuth(userRepository)    JWT HS256
 ├─ startDeviceRelay()               bind TCP, listen for ESPs
 ├─ MdnsPublisher.start()            announce _instantiot._tcp
 ├─ job 5s   : flush history + closed aggregators → DB
 ├─ job 1h   : cleanup history per-tier retention
 ├─ job cron : backup VACUUM INTO (configurable interval)
 ├─ configureAppRelay()              bind WebSocket /ws/app
 └─ routing { … }                    REST routes + static SPA
```

---

## 4. Database — SQLite (Exposed ORM)

File: `~/.instantiot/instantiot.db` (WAL mode). Tables created by
`DatabaseFactory.init()`.

```
┌─ users ───────────────┐   ┌─ projects ─────────────┐   ┌─ devices ──────────────┐
│ id            PK      │   │ id            PK       │   │ id            PK       │
│ username      UNIQUE  │   │ owner_id  → users.id   │   │ project_id → projects  │
│ pwd_hash   (BCrypt)   │   │ name                   │   │ owner_id   → users.id  │
│ role  admin|user      │   │ layout_json  (opaque)  │   │ name                   │
│ created_at            │   │ created_at / updated   │   │ token_hash (SHA-256)   │
└───────────────────────┘   └────────────────────────┘   │ is_online / last_seen  │
                                                          │ device_type            │
┌─ widgets ─────────────┐                                 └────────────────────────┘
│ id            PK      │
│ project_id            │   ┌─ servers ──────────────┐  (app-side table, multi-server)
│ owner_id              │   │ id / name / host / port│
│ type display|command  │   │ jwt / username / role  │
│ last_payload          │   │ scheme / pathPrefix    │
│ last_seen_at          │   └────────────────────────┘
└───────────────────────┘

── HISTORY / TIME-SERIES ───────────────────────────────────────────────
┌─ widget_history ──────┐  opaque (non-numeric) events     retention ~7d
┌─ widget_history_numeric┐ raw numeric samples (opt-in)     retention ~2d
┌─ widget_history_min ──┐  1-min buckets   {avg,min,max,count} retention ~7d
┌─ widget_history_hour ─┐  1-hour buckets  {avg,min,max,count} retention ~30d
┌─ widget_history_day ──┐  1-day buckets   {avg,min,max,count} retention ~365d
```

> **Migration**: `SchemaUtils.createMissingTablesAndColumns()` adds missing
> columns at boot. **Golden rule**: every new column must be `.nullable()` or
> have a `.default(...)`, otherwise the `ALTER TABLE` fails on existing rows.
> Never rename a column (Exposed does not track renames).

---

## 5. History — 3 independent tiers

Blynk-style architecture: every numeric sample feeds **all 3 tiers directly**
(no cascade — the daily average is over ALL samples, not an average of
averages).

```
ESP : numeric sample (e.g. gauge = 23.5)
        │
        ├──▶ numericHistoryBuffer   (RAW tier, opt-in)
        │
        └──▶ HistoryAggregators (RAM):
               ├─ minute  60 s bucket
               ├─ hour    3600 s bucket
               └─ day     86400 s bucket

  every 5 s ──▶ flush CLOSED buckets → widget_history_* tables
  every 1 h ──▶ cleanup: delete rows older than the retention
  clean shutdown ──▶ flush ALL buckets (even in-progress) → 0 loss
```

| Tier | Table | UI usage |
|---|---|---|
| raw | `widget_history_numeric` | high-resolution zoom (1h/6h) — admin opt-in |
| minute | `widget_history_min` | 1 day → 1 week view |
| hour | `widget_history_hour` | 1 week → 1 month view |
| day | `widget_history_day` | long-term view (months / years) |

> Idempotency: UNIQUE index `(widget_id, series_id, bucket_at)` → a re-flushed
> bucket is an `INSERT OR IGNORE`, a no-op.

---

## 6. Auth & security

```
JWT HS256
 ├─ secret : ~/.instantiot/secret.key  (generated on 1st boot, 600 perms)
 ├─ expiry : 1 year (LAN server, no short rotation)
 └─ every authenticate("jwt") route → verify token + look up user in DB

Roles
 ├─ user  : CRUD on THEIR OWN projects / devices / widgets
 └─ admin : everything + admin panel (stats, config, users, backup, restart)

Admin bootstrap                                  Application.kt (§ boot)
 ├─ 1st start, no admin in DB         → create  admin / admin
 └─ ~/.instantiot/reset-admin file present → reset admin pwd = "admin"
                                             + delete the file
```

> **Admin password recovery** (forgotten): no network route. The owner creates
> `~/.instantiot/reset-admin` on the machine and restarts. Secured by
> filesystem access = proof of machine ownership.
>
> **Resetting a regular user**: the admin does it from the panel
> (`POST /api/admin/users/{id}/reset-password`).

---

## 7. REST routes

| Method | Path | Auth | Role |
|---|---|---|---|
| GET | `/api/status` | — | Server state (always accessible) |
| POST | `/api/login` | — | Login → JWT + role (rate-limit 10/min/IP) |
| POST | `/api/register` | — | Sign-up, if `registrationOpen=true` |
| PATCH | `/api/users/me/password` | JWT | Change own password |
| GET/POST | `/api/projects` | JWT | List / create projects |
| GET | `/api/projects/{id}` | JWT | Project detail + layout |
| PATCH | `/api/projects/{id}/name` `/layout` | JWT | Rename / sync layout |
| GET/POST | `/api/devices` | JWT | List / register a device (generates token) |
| PATCH/DELETE | `/api/devices/{id}` … | JWT | Rename / delete |
| GET | `/api/widgets/...` | JWT | Query history (raw/min/hour/day) |
| GET | `/api/admin/stats` `/server-info` | JWT admin | Server metrics |
| PATCH | `/api/admin/config` `/history-config` | JWT admin | Config (restart needed for ports) |
| GET/POST | `/api/admin/backup...` | JWT admin | List / snapshot / restore |
| GET | `/api/admin/users` | JWT admin | List all users |
| POST | `/api/admin/users/{id}/reset-password` | JWT admin | Reset a user's password |
| PATCH | `/api/admin/registration/config` | JWT admin | Open / close sign-up |
| POST | `/api/admin/restart` | JWT admin | Restart the server |

---

## 8. Module tree — `src/main/kotlin/com/jeanloickdt/`

```
Application.kt          boot: main() + Application.module()
common/                 ServerConfig, PortFinder, SystemTrayManager, StatusResponse
database/               DatabaseFactory — init SQLite WAL, tables, migrations
auth/                   JWT, login/register, admin panel, user management
  ├─ data/              UserTable, SqliteUserRepository
  └─ domain/            UserRepository, DTOs
device/                 ESP/Arduino registration, tokens
  ├─ data/              DeviceTable, SqliteDeviceRepository
  └─ domain/            DeviceRepository, DeviceRow
project/                user projects (opaque dashboard layout)
widget/                 widgets + time-series history
  ├─ data/              WidgetTable, widget_history*, HistoryAggregators
  └─ domain/            WidgetRepository, WidgetHistory*Repository
relay/                  ★ CORE — DeviceRelay (TCP), AppRelay (WS),
                        SessionRegistry, ControlEventBroadcaster, FrameParser
backup/                 BackupManager — VACUUM INTO snapshot, restore
discovery/              MdnsPublisher — announces _instantiot._tcp on the LAN
```

---

## 9. Packaging & distribution

```
build.gradle.kts
 ├─ version  = single source → generated resource read at runtime
 ├─ shadowJar / buildFatJar   → standalone executable JAR
 └─ packageInstaller (jpackage) → native .deb / .dmg / .msi

.github/workflows/release.yml
 └─ push a v* tag → 3-OS matrix → build the 3 installers
                  → attach to a GitHub Release
                  (.deb arm64 via QEMU for Raspberry Pi)
```

systemd service: the `.deb`'s `postinst` installs and enables
`instantiot-server.service` (see `src/main/packaging/linux/`).

---

## 10. Quick onboarding — where to start

**Read these 7 files in order:**

1. `Application.kt` — big picture: boot + route wiring
2. `relay/SessionRegistry.kt` — the live global state
3. `relay/DeviceRelay.kt` — TCP protocol, ESP side
4. `relay/AppRelay.kt` — WebSocket protocol, app side
5. `relay/FrameParser.kt` — decoding the iWidgets v1 binary frames
6. `widget/data/HistoryAggregators.kt` — real-time aggregation
7. `database/DatabaseFactory.kt` — DB schema

**Add a new functional domain:**

1. Create the package `com/jeanloickdt/<feature>/` with `data/` + `domain/`
2. Define the Exposed `Table` (columns `nullable()` or `default()`)
3. `<Feature>Repository` interface (domain) + SQLite impl (data)
4. Add the table to `DatabaseFactory.init(...)` in `Application.kt`
5. Create `<Feature>Routes.kt`, call it inside the `routing { }` block
6. Numeric history → reuse `HistoryAggregators` + the existing 5s flush
7. Real-time broadcast → extend `ControlEventBroadcaster` + `SessionRegistry`

**In one sentence:** the server is a Ktor TCP↔WebSocket relay between IoT
boards and apps, with SQLite persistence and a 3-tier time-series history
aggregated in RAM then flushed every 5 seconds.
