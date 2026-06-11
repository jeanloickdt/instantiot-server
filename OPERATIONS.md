# 🛠️ InstantIoT Server — Operations Runbook

Practical guide to **install, run, back up, recover and troubleshoot** a
self-hosted InstantIoT server. For the internals see [`ARCHITECTURE.md`](ARCHITECTURE.md).

---

## 1. What it is

A self-hosted server that relays, in real time, between your IoT boards
(ESP32 / Arduino) and the InstantIoT mobile app, on your local network.
One process, two ports:

| Port | Default | Used by |
|------|---------|---------|
| HTTP | `8080` | mobile app API + admin panel (browser) + WebSocket `/ws/app` |
| TCP  | `9001` | the device relay (ESP32 / Arduino connect here) |

If a port is taken, the server automatically tries the next few (`8080→8085`,
`9001→9005`) and announces the actual ports over mDNS, so the app finds it by
itself on the LAN — no IP to type.

---

## 2. Install

### macOS / Windows (desktop, tray app)
1. Download the `.dmg` (macOS) or `.msi` (Windows) from the **Releases** page.
2. Install and launch. A tray icon appears (menu: open panel, restart, quit).
3. Open `http://localhost:8080` in your browser.

### Linux / Raspberry Pi (background service)
1. Download the `.deb` (use the **arm64** `.deb` for a 64-bit Raspberry Pi).
2. Install:
   ```bash
   sudo dpkg -i instantiot-server_*.deb
   ```
   The package installs and enables a hardened systemd service
   (`instantiot-server.service`, runs as the dedicated `instantiot` user).
3. Check it:
   ```bash
   systemctl status instantiot-server
   curl http://localhost:8080/health     # → {"status":"ok",...}
   ```

### From source (dev)
```bash
./gradlew run                 # run directly
./gradlew buildFatJar         # → build/libs/instantiot-server-all.jar
./gradlew packageInstaller    # → build/jpackage/*.{deb,dmg,msi} for this OS
```

---

## 3. First login

- Open the panel: `http://<server>:8080`.
- **Default admin credentials: `admin` / `admin`.**
- On the first login the panel **forces you to change the password** before you
  reach the dashboard. Do it — on a network this default is a wide-open door.

Then, to add users: open **registration** in the admin panel while onboarding
them, then close it again (it is closed by default so nobody on the LAN can
self-register).

---

## 4. Where the data lives

Everything is under **`~/.instantiot/`** (the home of the user running the
server — for the Linux service, the `instantiot` user's home):

| Path | What |
|------|------|
| `instantiot.db` (+ `-wal`, `-shm`) | the SQLite database (users, projects, devices, widgets, history) |
| `server.properties` | configuration (ports, retention, backup, display name) |
| `secret.key` | JWT signing secret (auto-generated, `rw-------`) — **back this up too** |
| `backups/` | automatic DB snapshots |
| `logs/` | rotating log files |
| `reset-admin` | recovery marker (see §8), only when you create it |

> A full backup = copy the whole `~/.instantiot/` folder while the server is
> stopped (or use the snapshot mechanism in §6, which is safe while running).

---

## 5. Configuration

Most settings are changed from the **admin panel** (Settings), or by editing
`~/.instantiot/server.properties` while the server is stopped.

| Setting | Key | Default | Notes |
|---------|-----|---------|-------|
| HTTP port | `http.port` | 8080 | **restart required** |
| TCP port | `tcp.port` | 9001 | **restart required** |
| Server name (mDNS) | `server.displayName` | _(hostname)_ | restart required |
| Public registration | `registration.open` | `false` | hot-reload |
| Keep raw measurements | `history.raw.enabled` | `true` | hot-reload |
| Raw retention (days) | `history.retention.raw.days` | 1 | hot-reload |
| Text-event retention | `history.retention.opaque.days` | 1 | hot-reload |
| Minute-summary retention | `history.retention.min.days` | 90 | hot-reload |
| Hour-summary retention | `history.retention.hour.days` | 365 | hot-reload |
| Day-summary retention | `history.retention.day.days` | -1 (forever) | hot-reload |
| Backup enabled | `backup.enabled` | `true` | hot-reload |
| Backup interval (h) | `backup.interval.hours` | 24 | hot-reload |
| Backups kept | `backup.retention.count` | 30 | hot-reload |

> **Retention deletes old data automatically, but ONLY by these rules.** Set a
> tier to a large value (or `-1` for daily) to keep data longer / forever. A
> weekly incremental vacuum then returns the freed pages to the disk — it never
> deletes data.

---

## 6. Backup & restore

**Automatic snapshots.** Every `backup.interval.hours` the server writes a
consistent snapshot (`VACUUM INTO`) to `~/.instantiot/backups/instantiot-<date>.db`,
keeping the newest `backup.retention.count`. Safe while running.

**Manual snapshot:** admin panel → Backup → *Backup now* (or `POST /api/admin/backup/now`).

**Restore:**
1. Admin panel → Backup → pick a snapshot → *Restore*. This **stages** the
   restore (validated and queued as `instantiot.db.pending-restore`); the live
   database is **not** touched yet — swapping it under the running server would
   leave it in an inconsistent half-state.
2. **Restart the server** — the swap happens *during boot*, before the database
   opens. At that point the current DB is first snapshotted aside as
   `instantiot.db.before-restore-<ts>` (a WAL-complete safety net), then the
   chosen backup is moved into place.
   ```bash
   systemctl restart instantiot-server     # Linux
   ```
   On desktop, use the tray **Restart**.

> A staged backup that fails its integrity check is refused outright; one that
> rots between staging and the restart is discarded at boot and the current DB
> is kept. A restore can never plant a corrupt database.

**Manual restore (offline):** stop the server, copy a `backups/*.db` over
`~/.instantiot/instantiot.db`, delete the stale `instantiot.db-wal` /
`-shm` files, start the server.

---

## 7. Logs

- File: **`~/.instantiot/logs/instantiot.log`** (rotates daily or at 10 MB,
  14 days kept, gzip-compressed, 200 MB cap).
- Live tail:
  ```bash
  tail -f ~/.instantiot/logs/instantiot.log
  ```
- Linux service also logs to journald:
  ```bash
  journalctl -u instantiot-server -f
  ```
- Secrets are never logged in full (tokens/ids are truncated).

---

## 8. Recover a lost admin password

There is **no network reset** route (by design — it would be an attack surface).
Recovery is gated by filesystem access to the machine (= proof you own it):

1. Create the marker file in the data dir:
   ```bash
   touch ~/.instantiot/reset-admin
   ```
   (For the Linux service, create it in the `instantiot` user's home, e.g.
   `sudo -u instantiot touch ~instantiot/.instantiot/reset-admin`.)
2. Restart the server. The admin password is reset to **`admin`** and the
   marker is deleted.
3. Log in with `admin` / `admin` — you will again be forced to set a new password.

To reset a *regular user*'s password: admin panel → Users → Reset password.

> **Changing or resetting a password signs the user out of every other device.**
> The server bumps an internal `token_version`, which invalidates all of that
> user's existing tokens at once (e.g. revoking access for a lost/compromised
> device). The session that performs a self-change stays logged in (it receives
> a fresh token); all others must log in again with the new password.

---

## 9. Health & version checks

Both are unauthenticated:

```bash
curl http://<server>:8080/health        # {"status":"ok","uptimeMs":...}
curl http://<server>:8080/api/version   # {"version":"1.1.3"}
```

Use `/health` for an uptime monitor or a load-balancer probe.

---

## 10. Updating

1. Stop the server (tray Quit, or `systemctl stop instantiot-server`).
2. Install the newer installer over the old one (data in `~/.instantiot/` is
   preserved; the schema auto-migrates on the next boot).
3. Start it again and check `curl .../api/version`.

> Always keep a recent backup (§6) before updating.

---

## 11. Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| Panel won't open on `:8080` | Port was taken → the server moved to `8081…8085`. Check the startup log / `curl .../health` on the next ports, or free 8080 and restart. |
| App can't find the server on the LAN | mDNS blocked (some routers/VLANs). Add the server manually by IP:port in the app. Confirm the server logged `mDNS published`. |
| Device (ESP) won't connect | It must reach the **TCP port** (default 9001), not 8080. Check the device token, the IP, and that 9001 isn't firewalled. |
| `database is locked` in logs | Should be rare (a 5s busy-timeout is set). If persistent, you may be running two instances against the same `~/.instantiot/` — stop the duplicate. |
| Disk filling up | Lower the retention values (§5) and/or wait for the weekly incremental vacuum; backups accumulate in `backups/` per `backup.retention.count`. |
| Forgot admin password | See §8. |
| Service won't start (Linux) | `journalctl -u instantiot-server -e` for the stack trace; check the `instantiot` user can write `~/.instantiot/`. |

---

## 12. Uninstall

- **Linux:** `sudo dpkg -r instantiot-server` (add `--purge` to also remove
  config). The data dir `~/.instantiot/` is **not** removed — delete it manually
  if you want a clean wipe.
- **macOS / Windows:** remove the app the usual way; then delete `~/.instantiot/`
  to remove data.

> ⚠️ Deleting `~/.instantiot/` erases your database, history and JWT secret —
> back it up first if you might want it back.
