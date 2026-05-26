# InstantIoT Server

**Self-hosted, open-source IoT relay server.** It relays real-time
communication between IoT boards (ESP32 / Arduino) and the InstantIoT mobile
app — multi-device connections, time-series history, web admin panel.

Install it on your own computer or a Raspberry Pi, it runs at home.

> Stack: **Kotlin · Ktor · Netty · SQLite (Exposed)** · JDK 21.
> License: **GNU AGPLv3** (see [§ License](#license)).

---

## Features

- **Real-time relay** between TCP (boards) and WebSocket (apps) via the
  `iWidgets v1` binary protocol
- **Multi-user**: admin/user accounts, JWT auth
- **Time-series history** with 3 aggregated tiers (minute / hour / day) plus an
  optional raw tier
- **mDNS discovery**: the app finds the server automatically on the LAN
- **Web admin panel**: stats, user management, config, backups
- **Automatic SQLite backups** (`VACUUM INTO`, configurable retention)
- **Native installers** `.deb` / `.dmg` / `.msi` (jpackage)

Detailed architecture: [`ARCHITECTURE.md`](ARCHITECTURE.md).

---

## Quick start

### Option A — native installer

Download the installer for your OS from the [Releases](../../releases) page
(`.deb`, `.dmg`, `.msi`, including `.deb arm64` for Raspberry Pi), install,
and launch.

### Option B — from source

Requirement: **JDK 21**.

```bash
./gradlew run               # run the server in dev
./gradlew buildFatJar       # → build/libs/*-all.jar (standalone JAR)
java -jar build/libs/instantiot-server-all.jar
./gradlew packageInstaller  # → build/jpackage/*.{deb,dmg,msi}
```

On startup you will see:

```
Starting InstantIoT Server v1.x.x
HTTP port: 8080 | TCP port: 9001
```

Open **http://localhost:8080** → admin panel.

---

## First launch & admin account

On the very first start, the server creates a default admin account:

```
username: admin
password: admin
```

> Log in, then **change the password** immediately
> (admin panel → settings).

### Forgot the admin password

There is no network-based recovery (by design — zero attack surface). On the
machine hosting the server:

```bash
touch ~/.instantiot/reset-admin
# restart the server
```

On boot, the server resets the admin password back to `admin` and deletes the
file. Secured by filesystem access = proof that you own the machine.

---

## Configuration

File `~/.instantiot/server.properties` (created on first boot). HTTP / TCP
ports, per-tier history retention, backup interval, registration toggle, etc.
Several settings are hot-reloaded (applied without a restart).

Runtime data, all under `~/.instantiot/`:

| File | Purpose |
|---|---|
| `instantiot.db` | SQLite database (users, projects, devices, widgets, history) |
| `server.properties` | Configuration |
| `secret.key` | JWT secret (generated once) — **do not share** |
| `reset-admin` | Reset marker (see above) |

---

## Device protocol — quick reference

### TCP handshake (port `tcp.port`, default 9001)

```
[PAYLOAD_LEN(1B) | PAYLOAD_BYTES]
```

- `payload = "token"` — legacy, server soTimeout = **90 s**
- `payload = "token:heartbeatMs"` — adaptive, soTimeout = `heartbeatMs × 2.5`
  (clamped 2 s … 120 s)

### Heartbeat frame

An `iWidgets v1` frame with `TYPE = 0xFE`, `WID_LEN = 0`, empty payload.
Emitted by the Arduino library every `heartbeatMs`. The server does not
dispatch it, but receiving the byte resets the socket read timeout.

With `heartbeat = 5000 ms`, an unplugged / crashed board is detected offline
in **≤ 12.5 s**.

---

## Build & Gradle tasks

| Task | Description |
|---|---|
| `./gradlew run` | Run the server |
| `./gradlew build` | Full build |
| `./gradlew test` | Run tests |
| `./gradlew buildFatJar` | Standalone JAR (all dependencies bundled) |
| `./gradlew packageInstaller` | Native installer for the current OS |

CI: pushing a `v*` tag triggers the build of all three installers
(`.github/workflows/release.yml`) and attaches them to a GitHub Release.

---

## Contributing

Contributions are welcome.

1. Read [`ARCHITECTURE.md`](ARCHITECTURE.md) to understand the structure.
2. Fork → branch → commit → pull request.
3. `./gradlew build` must pass.
4. By contributing, you agree that your code is distributed under AGPLv3.

---

## License

This project is licensed under the **GNU Affero General Public License v3.0**
(AGPLv3) — see [`LICENSE`](LICENSE).

In short: you are free to use, study, modify and redistribute this server. Any
modified version — **including one you offer as a network service** — must
also be published under AGPLv3 with its source code. This is copyleft designed
for server software.

```
Copyright (C) 2026 Djoufack Tsobeng Jean Loick (InstantIoT)
Author: Djoufack Tsobeng Jean Loick (@jeanloick_dt)

This program is free software: you can redistribute it and/or modify it
under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or (at your
option) any later version. This program is distributed WITHOUT ANY WARRANTY.
See the GNU Affero General Public License for more details.
```

> The InstantIoT mobile app is a separate project; it communicates with this
> server over a network protocol and is not covered by this license.
