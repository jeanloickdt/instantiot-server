# InstantIoT Server — Architecture

> **Self-hosted** server that relays, in real time, the communication between
> IoT boards (ESP32 / Arduino) and the InstantIoT mobile app.
> Stack: **Kotlin 2.3 + Ktor 3.4 (Netty + ktor-network) + SQLite (Exposed)**. JDK 21. AGPLv3.
>
> Entry point for picking up the code. Read end to end (~12 min) and you can
> start coding. For the stabilization backlog see [`STABILIZATION.md`](STABILIZATION.md).
> All `file:line` refs are under `src/main/kotlin/com/jeanloickdt/`.

> **The 2.0 model, since September 2026.** A board writes a value at an
> **address** — `I0`, `I5`, like `A0` — and the app decides whether that value
> is drawn as a gauge, a chart or a number. The board carries no widgets, no
> widget ids and no drawing code. The old model — a `widgets` table addressed
> by a name chosen in the sketch — is gone, tables included.

---

## 1. Overview — the 3 network channels

The server exposes **3 distinct network surfaces** in **one JVM process**.

```
                         ┌───────────────────────────────────┐
                         │       INSTANTIOT SERVER           │
                         │       (1 JVM process)             │
   ESP32 / Arduino       │   ┌───────────────────────────┐   │
   ───────────────TCP────┼──▶│  TCP Device Relay  :9001  │   │
   binary frames         │   │ (non-blocking, ktor-net)  │   │
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
| **TCP Device Relay** | 9001 (`tcp.port`) | ESP32 / Arduino | binary SIGNAL frames + token handshake |
| **WS App Relay** | 8080 `/ws/app` | Mobile app | WebSocket: binary frames + JSON control messages |
| **REST API** | 8080 (`http.port`) | App + browser | HTTP/JSON + serves the static admin panel |

> Ports are **auto-bound**: if 8080/9001 are taken, the server tries +1…+5
> (`PortFinder`). The actually-used ports are announced over mDNS (the HTTP
> port is the service port; the TCP port travels in a `tcpPort` TXT record).

---

## 2. The core: the RELAY

The server is first and foremost a **bidirectional relay**. Its live state is
held in RAM behind **constructor-injected** seams (no global singleton — built
once in `Application.module()` and passed down; tests build isolated instances):

```
  ConnectionRegistry          HistoryBuffers           LastValueCache (iface)
  ┌────────────────────┐      ┌──────────────────┐     ┌─────────────────────┐
  │ appSessions (+out) │      │ signalRawBuffer  │     │ put/get/drainDirty  │
  │ deviceSessions     │      │ backPressure     │     │ evict — RAM now,     │
  │ deviceOutboxes     │      │ (bounded queues) │     │ Redis later (seam)   │
  └────────────────────┘      └──────────────────┘     └─────────────────────┘
  PresenceStore (iface)       ControlEventBroadcaster
  ┌────────────────────┐      ┌──────────────────┐
  │ markOnline/Offline │      │ device_online/…  │   NOT posed yet (uncertain
  │ isOnline/lastSeen  │      │ command_failed   │   multi-node seam): MessageBus
  │ (DB-backed mono-node)│    │ bucket_updated   │   + shared impls → additive.
  └────────────────────┘      └──────────────────┘
```

> SRP split + DI is the mono-node foundation for a future multi-node move:
> swap `InMemoryLastValueCache`/`DbBackedPresenceStore` for shared impls and
> add a `MessageBus` — **adding** code, not modifying `DeviceRelay`/`AppRelay`.

### DEVICE → APP flow (a sensor sends a value) — `relay/DeviceRelay.kt`

The read loop is **sequential** (one frame handled before the next is read —
natural per-device backpressure) and **RAM/CPU only** — no DB on the read path:

```
ESP sends a SIGNAL frame (TYPE 0x20)
   │
   ▼  handleDeviceFrame()   (inline, sequential; own try/catch → one bad
   │                          frame never drops the connection)
   │ 0. TYPE == 0xFE (heartbeat) → return immediately (no dispatch)
   │    TYPE != 0x20            → no recipient: dropped, silently
   │ 1. ingestSignalFrame()                  (signal/SignalIngest.kt)
   │ 2. STRICT MODEL: the address must be DECLARED for this board.
   │    An undeclared address is noise — dropped before any write, and
   │    COUNTED (the sketch reads it back via InstantIoT.ignoredFrames()).
   │ 3. lastValues.put(ownerId, signalKey, base64)   (RAM — coalesced in the 5s flush)
   │ 4. if numeric & value.isFinite() → SignalAggregators.minute.collect()
   │    (+ signalRawBuffer if history.raw.enabled)    ← non-finite rejected
   │ 5. SignalFrame.forApps(frame, deviceId) — the frame is REBUILT with the
   │    board stamped in: an app watches several boards, and I5 is per board.
   │ 6. dispatchToApps(projectId, rebuiltFrame)
   ▼
WS App sessions of that project → the app updates whatever draws that address
```

The hour and day tiers are NOT accumulated in RAM: they are derived from the
minute tier on a slower loop, so a restart loses at most one tier, not three.

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

**Device (TCP)** — `DeviceRelay.kt` (non-blocking, ktor-network suspending reads)
```
ESP opens TCP :9001  (aSocket(SelectorManager).tcp().bind → suspending accept())
  → withTimeoutOrNull(10s) { readHandshake } (provisional handshake window)
  → sends  [LEN(1B)][PAYLOAD]   where PAYLOAD = "token"  OR  "token:heartbeatMs"
  → server: SHA-256(token) → look up devices.token_hash
  → read timeout = heartbeatMs × 2.5, clamped [2s, 120s]
    (legacy "token" only → 90s).  heartbeat=5000ms ⇒ offline detected in ≤12.5s
    (enforced as withTimeoutOrNull(timeout){ readFrame } ?: break — not soTimeout)
  → OK: session registered, presence online, broadcast device_online
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
 ├─ BackupManager.applyPendingRestore()  swap a staged restore BEFORE the pool
 │                                   opens (no split-brain) + WAL-complete safety net
 ├─ DatabaseFactory.init(tables)     SQLiteDataSource: WAL, busy_timeout=5000,
 │                                   synchronous NORMAL, cache 32MB, temp MEMORY
 │                                   + createMissingTablesAndColumns + indexes
 │                                   + one-time auto_vacuum=INCREMENTAL migration
 ├─ devicePresence.markAllOffline()  reset stale is_online after a hard kill
 ├─ CachedSignalRepository           the DB off the relay's hottest line:
 │                                   one batch write per 5 s round, not one per frame
 ├─ SHUTDOWN FLUSH HOOKS             ApplicationStopping  AND  Runtime shutdown hook
 │                                   (the JVM hook covers the tray's System.exit)
 ├─ ApplicationStopping → MdnsPublisher.stop()
 ├─ BOOTSTRAP ADMIN                  admin/admin (passwordChanged=false) — see §6
 ├─ configureAuth(userRepo, tokenService)  injected TokenService (HS256 + revocation)
 ├─ startDeviceRelay(tcpPort)        ktor-network SelectorManager; SupervisorJob;
 │                                   suspending accept(); no pool, no cap
 ├─ MdnsPublisher.start(displayName) announce _instantiot._tcp
 ├─ job 5s    : coalesced last values + closed minute buckets + raw batch → DB
 │              + the message ledger                                    (try/catch)
 ├─ job 5min  : deriveHour then deriveDay from the minute tier          (try/catch)
 ├─ job 1h    : retention sweep, from server.properties                 (try/catch)
 ├─ job N-h   : backup VACUUM INTO (configurable interval)              (try/catch)
 ├─ job weekly: DatabaseFactory.incrementalVacuum() reclaim freelist     (try/catch)
 ├─ job 1s    : DeliveryWorker — lease, deliver, settle                 (try/catch)
 ├─ job 30s   : AutomationHealthWatch                                   (try/catch)
 ├─ job 10s   : offline confirmations + the 60 s stale sweep            (try/catch)
 ├─ AutomationEngine.run()           drains the event sinks
 ├─ configureAppRelay()              bind WebSocket /ws/app
 └─ routing { … }                    /api/status + REST routes + static SPA
```

> Every background loop is wrapped in a per-iteration `try/catch` (a thrown
> `SQLITE_BUSY` must not kill the flush loop — that would leak the RAM buffers
> to OOM). The relay reads are **non-blocking** (suspending `ByteReadChannel`),
> so each device is a cheap coroutine — no thread-per-device and no dedicated
> pool; `ConnectionGate` caps how many at once. A `SupervisorJob` isolates a
> failing connection from the
> accept loop and the other devices.

---

## 4. Database — SQLite (Exposed DSL)

File: `~/.instantiot/instantiot.db` (WAL). Tables created by
`DatabaseFactory.init()`.

```
┌─ users ───────────────┐   ┌─ projects ─────────────┐   ┌─ devices ──────────────┐
│ id            PK      │   │ id            PK       │   │ id            PK       │
│ username      UNIQUE  │   │ owner_id  → users.id   │   │ project_id → projects  │
│ pwd_hash   (bcrypt)   │   │ name                   │   │ owner_id   → users.id  │
│ role  admin|user      │   │ layout_json  (opaque)  │   │ name                   │
│ password_changed      │   │ version   (optimistic) │   │ token_hash (SHA-256)   │
│ token_version         │   │ created_at / updated   │   │ is_online / last_seen  │
│ created_at            │   └────────────────────────┘   │ device_type / connect. │
└───────────────────────┘                                └────────────────────────┘
┌─ signals ─────────────────────────────────────────────────────────────┐
│ id  INTEGER PK autoincrement  — the history references THIS, not a triple │
│ owner_id / device_id / address (0..255)   UNIQUE (device_id, address)   │
│ label / type / unit / decimals / min / max                             │
│ historised / replay_on_connect / automation_visible                    │
│ last_payload (base64) / last_seen_at                                   │
└───────────────────────────────────────────────────────────────────────┘

── HISTORY / TIME-SERIES — four tables ─────────────────────────────────
signal_raw    raw samples (opt-in)              PK (signal_id, ts)
signal_min    1-min  buckets {avg,min,max,n}    PK (signal_id, bucket_at)
signal_hour   1-hour buckets — DERIVED from min
signal_day    1-day  buckets — DERIVED from hour
```

**The signal carries an integer identity.** A history row references
`signal_id`, not an `(owner, device, address)` triple: one integer instead of
three columns repeated on every sample. Renaming a board or moving an address
does not rewrite history.

**The primary key contains time from the start.** That is what makes a replayed
batch merge rather than collide — and it is what lets the cloud edition turn
the same tables into Timescale hypertables without key surgery.

> **Migration**: `createMissingTablesAndColumns()` adds missing columns at boot
> (additive). **Golden rule**: every new additive column must be `.nullable()`
> or `.default(...)`, else the `ALTER` fails on existing rows. Never rename a
> column — Exposed does not track renames.

> **What was removed in September 2026.** The `widgets` table and its five
> history tables (`widget_history`, `_numeric`, `_min`, `_hour`, `_day`), plus
> the boot rebuild that migrated the `widgets` primary key from `id` to
> `(owner_id, id)`. The 2.0 model is a break: **history stored under the old
> tables is not migrated, it is dropped.** That was a deliberate call, not an
> oversight.

---

## 5. History — one accumulator, three derived tiers

The three tiers used to be fed **in parallel**, each accumulating in RAM. They
are now a **cascade**: only the minute is accumulated, and hour and day are
derived from it on a slower loop.

The reason is a restart. Three RAM accumulators meant a hard stop lost an
in-progress bucket in each of the three tiers — a minute, an hour and a day.
With the cascade it loses one minute, and the hour that contains it is derived
again from what reached the disk.

```
ESP : numeric sample (e.g. 23.5)
        │
        ├──▶ signalRawBuffer                   (RAW tier, history.raw.enabled)
        │
        └──▶ SignalAggregators.minute (RAM)
               60 s bucket, keyed by (signalId, bucketAt)
               aggregates: min, max, avg(=sum/count), count, and the
               INSTANTS of the min and max

  every 5 s ──▶ closed buckets (bucketAt+60s ≤ now) → signal_min
                + the raw buffer, drained in one batch
  every 5 min ─▶ deriveHour(window 24) then deriveDay(window 7)
  every 1 h ──▶ sweep: retention from server.properties, per tier
  clean stop ──▶ flush ALL buckets, in-progress included → 0 loss
```

| Tier | Table | Typical UI usage | Default retention |
|---|---|---|---|
| raw | `signal_raw` | high-resolution zoom | 1 day |
| minute | `signal_min` | 1 day → 1 week view | 90 days |
| hour | `signal_hour` | 1 week → 1 month view | 365 days |
| day | `signal_day` | months / years | unlimited (`-1`) |

**The extremes keep their instants all the way up.** A day bucket can name the
second at which the year's minimum happened, because `minAt`/`maxAt` travel
unchanged through the cascade. That is the one thing an average of averages
could never give back.

**The retention is the operator's, and only theirs.** No plan, no grid, no
account exception: what this machine keeps is decided in `server.properties`,
and the sweep receives that same shape with zero exceptions.

> ⚠️ **No `last` value is persisted** in any tier — only min/max/avg/count. The
> "last value" comes from the `LastValueCache` (RAM; the `signals.last_payload`
> column is a ≤5s-lagged cold-start fallback).

---

## 6. Auth & security

```
TokenService (injected; HmacTokenService = HS256 today)
 ├─ secret : ~/.instantiot/secret.key  (2×UUID, generated 1st boot, 600 perms)
 ├─ expiry : 1 year (LAN server, no short rotation — refresh tokens deferred to cloud)
 ├─ claims : subject=userId, issuer/audience, ver=token_version
 └─ every authenticate("jwt") route → verify signature + look up user in DB +
    REVOCATION check: reject if token's `ver` < users.token_version
    (role is NOT in the token — re-read from DB on each admin check)

Revocation (token_version, int on users, default 0)
 ├─ every password change (self / admin reset / bootstrap reset) bumps it via
 │   updatePassword → ALL prior tokens for that user are rejected at once
 ├─ changePassword then RE-ISSUES a fresh token for the current session
 │   (client swaps it) → the caller stays logged in, other devices are out
 └─ backward-compatible: a token minted before this feature (no `ver`) = v0

Login: constant-time — a dummy bcrypt check runs for an unknown username so
response timing does not reveal whether the account exists.

Roles
 ├─ user  : CRUD on THEIR OWN projects / devices / signals (ownerId == subject, else 404)
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

## 7. The binary frame — `relay/FrameParser.kt`, `signal/SignalFrame.kt`

The wire format shared by the Arduino library, the server and the app. One
layout, and today **two** live type codes.

```
 AA │ VER │ LEN(2B LE) │ DEV_COUNT │ [DEV_LEN│DEV_ID]×N │ WID_LEN │ ADDR │ TYPE │ TAG │ PAYLOAD… │ CRC8
```

| Field | Size | Notes |
|---|---|---|
| SYNC | 1 | `0xAA` |
| VERSION | 1 | `0x01` |
| LEN | 2 | little-endian; length of the body (DEV_COUNT..PAYLOAD) |
| DEV_COUNT | 1 | # target devices; **0 for device→app** |
| [DEV_LEN\|DEV_ID]×N | 1+DEV_LEN | device UUIDs (app→device only) |
| WID_LEN | 1 | always `0x01` — the length prefix of a name that no longer exists |
| ADDR | 1 | the address, `I0..I255` |
| TYPE | 1 | `0x20` SIGNAL · `0xFE` heartbeat. Anything else has no recipient. |
| TAG | 1 | low bits: value type (`01` bool, `02` int, `03` float, `04` text) · **high bit `0x80`: this is a REPLAY** |
| PAYLOAD | var | the value |
| CRC8 | 1 | CRC-8/SMBUS (poly 0x07) over the body |

- **The address sits where a widget name used to.** One byte instead of a
  length-prefixed string: a float frame is ~10 bytes against 18 for `gauge1`.
- **`WID_LEN` is a scar.** It always reads `0x01`. Removing it would save a byte
  and break the layout — so it would cost a frame version, and it is not worth
  one yet.
- **Direction marker** = `DEV_COUNT`: device→server carries `0`; app→device
  carries N UUIDs that the server strips via `trimDeviceHeader` (recomputing
  LEN + CRC8), so the board always sees a `DEV_COUNT=0` frame. On the way back
  the relay REBUILDS the frame with the board stamped in — an app watches
  several boards, and `I5` means nothing without knowing whose.
- **The replay flag never reaches the app.** `tag()` masks it; `forApps`
  rebuilds without it. It exists for one reader: the board, which must not run
  a gesture block for a value nobody just wrote.

  ```cpp
  if (restore && h->gesture) continue;   // a state is restored, a gesture is not
  ```

- Floats are **4-byte little-endian IEEE-754**.
- `isValid()` checks sync + version + LEN/size coherence + CRC8 (both directions).
- `hashDeviceToken()` = SHA-256 hex; the cleartext device token is never stored.

> **What used to be here.** Type codes `0x01`–`0x11` were widget types — GAUGE,
> CHART, SLIDER… — and `0x21` was `EVENT`, the gesture channel. Both are gone:
> a gesture is now a signal value (`1` press, `0` release, `2` long press), and
> the board learns what an address means from its own sketch, not from the wire.

---

## 8. REST routes

| Method | Path | Auth | Role / note |
|---|---|---|---|
| GET | `/api/status` | — | server state, `setup_required`, `tcpPort` |
| POST | `/api/login` | — (10/min) | → `{token, role, passwordChanged}` (panel forces change if false) |
| POST | `/api/register` | — (10/min) | if `registration.open` ; user regex, pwd 8-128 |
| PATCH | `/api/users/me/password` | JWT | change own password → revokes other sessions, returns a fresh token, clears must-change flag |
| GET/POST | `/api/projects` | JWT | list / create (scoped by owner) |
| GET/PATCH/DELETE | `/api/projects/{id}` `/name` `/layout` | JWT | owner check → 404; DELETE cascades |
| GET/POST | `/api/devices` `/projects/{id}/devices` | JWT | list / register (token shown once) |
| PATCH/DELETE | `/api/devices/{id}/name` `/{id}` `/renew-token` | JWT | rename / delete / renew (kicks TCP) |
| GET/POST | `/api/signals` `/devices/{id}/signals` | JWT | the whole project's signals / declare one |
| GET/PATCH/DELETE | `/api/devices/{id}/signals/{addr}` | JWT | read / edit / remove a declaration |
| PUT | `/api/devices/{id}/signals/{addr}/value` | JWT | write a setpoint — stored, sent, replayed on connect |
| GET | `/api/devices/{id}/signals/{addr}/history?from&to&resolution` | JWT | `auto\|raw\|min\|hour\|day` |
| GET/POST/PATCH/DELETE | `/api/rules` `/rules/{id}` | JWT | automation |
| GET | `/api/admin/stats` `/server-info` `/users` | JWT admin | metrics / users |
| PATCH | `/api/admin/config` `/history-config` `/registration/config` `/backup/config` | JWT admin | config (ports need restart) |
| GET/POST | `/api/admin/backup/list` `/now` `/restore` | JWT admin | snapshot / restore (restart required) |
| POST | `/api/admin/users/{id}/reset-password` `/api/admin/restart` | JWT admin | reset a user / restart |

> **Gone with the old model**: `/api/projects/{id}/widgets`, `/bulk`,
> `/widgets/{id}`, `/states` and `/api/widgets/{id}/history`. An address is
> declared through `signals` now.
>
> Known inconsistencies (STABILIZATION P1-2): `/login` & `/register` return bare
> strings instead of `{error}`; `StatusPages` returns text; no `/api/v1` versioning.

---

## 9. Module tree — `src/main/kotlin/com/jeanloickdt/`

```
Application.kt          boot: main() + Application.module() + background loops
common/                 ServerConfig, ServerDispatchers, PortFinder, SystemTrayManager
database/               DatabaseFactory — SQLite hardening, tables, migrations, incrementalVacuum()
auth/                   JWT, login/register, admin panel, user mgmt   (data/ + domain/)
device/                 ESP/Arduino registration, tokens, DeviceType   (data/ + domain/)
project/                user projects (opaque dashboard layout)        (data/ + domain/)
signal/                 ★ THE 2.0 MODEL — SignalFrame (the wire), SignalIngest,
                        SignalSetpoint (write + replay), SignalRoutes,
                        HistoryWindows, and under data/: the minute accumulator,
                        the merge, the rollup, the four tiers, the history query
retention/              the sweep itself — cutoffs, and no exception here
automation/             rules, engine, pending actions, delivery, senders
event/                  the relay's own event bus + the stale sweeper
relay/                  ★ CORE — DeviceRelay (non-blocking TCP), AppRelay (WS),
                        FrameParser, DeviceOutbox, ControlEventBroadcaster, and the
                        injected seams: ConnectionRegistry, HistoryBuffers,
                        LastValueCache, PresenceStore, ConnectionGate
backup/                 BackupManager — VACUUM INTO snapshot, restore
discovery/              MdnsPublisher / DnsSdPublisher — _instantiot._tcp on the LAN

> **No `plan/` here, and that is the point.** The cloud edition sells retention
> and quotas; this one does not. Where a decision could depend on a price grid,
> the route takes an injected gate — `quotaGate`, `historyWindows`,
> `RulePolicies` — whose default lets everything through. `signal/HistoryWindows`
> is the shape only, plus `unlimited()`.
```

> (Removed) the on-disk `bin/` directory was a **stale source mirror** (old `.kt`
> copies, not bytecode) — deleted; `src/main/kotlin` is authoritative.

---

## 10. Concurrency model

| Pool / scope | Used for |
|---|---|
| `Dispatchers.IO` (64) | DB writes (`withContext(IO)`), the ktor `SelectorManager` |
| **relay `SupervisorJob` + `Dispatchers.Default`** | one coroutine per device — **suspending** (non-blocking) socket reads; a failing connection is isolated |
| `Dispatchers.Default` | per-frame CPU work (`handleDeviceFrame`, run inline/sequentially) |

Rules: 1 coroutine per device, child of the relay `SupervisorJob` (device↔device
isolation); reads suspend via `ByteReadChannel` so idle connections cost ~nothing
(no thread-per-device, no pool, no cap); frames are handled **sequentially**
(natural backpressure) and **RAM/CPU only** (no DB on the read path — last_payload
is coalesced into the 5s flush); every `catch` around suspending code rethrows
`CancellationException`; the disconnect `finally` closes the socket first then
cleans up under `NonCancellable`; 1 bounded `DeviceOutbox` (Channel 8) per device
writes via a `ByteWriteChannel`, and symmetrically 1 `AppOutbox` per app session
guards the device→app direction (see below); the `SelectorManager` is closed at shutdown;
the flush is guaranteed on both `ApplicationStopping` and the JVM shutdown hook.

---

### Backpressure both ways — `DeviceOutbox` / `AppOutbox`

A WebSocket `send` suspends once the peer stops draining. Called from the device
read coroutine, that suspension stops the device from being read at all: its
receive buffer fills, the TCP window closes, and the board can no longer emit.
One dead app takes a live device down with it — invisible on a LAN, common on
mobile, where a backgrounded phone or a dropped link leaves a socket that is
open but no longer reading.

Every device→app send therefore goes through a per-session **`AppOutbox`**, and
none of its methods suspend. The suspension still exists — it is TCP
backpressure, it cannot be removed — but it now lives in a coroutine dedicated
to that one app, scoped to its session, so it dies with it. A suspended
coroutine holds no thread: a thousand zombie apps cost a thousand continuations.

Two channels, deliberately, because the drop policies differ and a single
`Channel` cannot express both — `DROP_OLDEST` applies to the whole channel (a
control event could be the oldest, and `trySend` would always succeed, removing
any way to detect a stuck session), while a default-capacity channel only lets
you drop the *newest*. Kotlin exposes no producer-side API to evict the oldest
selectively.

| channel | policy | rationale |
|---|---|---|
| telemetry | `DROP_OLDEST`, capacity 64 | a stale reading is worthless; keep the freshest |
| control | default capacity 64 | a failing `trySend` *is* the zombie signal |

A session that cannot absorb a discrete control event is not slow, it is gone:
it gets **closed** rather than losing the event. The app notices, reconnects and
re-syncs through `/states`. The close is *launched*, never awaited — `close()`
suspends, and calling it inline would reintroduce the very blocking the class
removes.

Capacities are generous on purpose: only a genuinely stuck session should
overflow. A legitimate burst — a device coming back online while several buckets
close — must never cost a healthy app its session. Eviction has to stay a strong
signal, not an accident.

Losing the relative order between the two channels is a feature here: during a
stall you want `device_offline` to land immediately rather than behind sixty
stale gauge values. That ordering never existed anyway — three of the four
control-event sources run in coroutines other than the device's.

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

1. `Application.kt` — big picture: boot + DI wiring + background loops
2. `relay/ConnectionRegistry.kt` (+ `HistoryBuffers`/`LastValueCache`/`PresenceStore`) — the injected live state
3. `relay/DeviceRelay.kt` — non-blocking TCP protocol, ESP side
4. `relay/AppRelay.kt` — WebSocket protocol, app side
5. `signal/SignalFrame.kt` + `relay/FrameParser.kt` — decoding the binary frames
6. `signal/data/SignalMinuteAggregator.kt` (+ `SignalMerge`, `SignalRollup`) — the cascade
7. `database/DatabaseFactory.kt` — DB schema + hardening

**Add a new functional domain:**

1. Create `com/jeanloickdt/<feature>/` with `data/` + `domain/`
2. Define the Exposed `Table` (new columns `nullable()` or `default()`)
3. `<Feature>Repository` interface (domain) + `Exposed<Feature>Repository` (data)
4. Add the table to `DatabaseFactory.init(...)` in `Application.kt`
5. Create `<Feature>Routes.kt`, wrap in `authenticate("jwt")`, call it in `routing { }`
6. Numeric history → reuse `SignalAggregators.minute` + the existing 5 s flush
7. Real-time broadcast → extend `ControlEventBroadcaster` + inject `ConnectionRegistry`

**In one sentence:** the server is a Ktor TCP↔WebSocket relay between IoT boards
and apps, persisting to SQLite, with a minute tier aggregated in RAM and flushed
every 5 seconds, from which the hour and day tiers are derived.
```
