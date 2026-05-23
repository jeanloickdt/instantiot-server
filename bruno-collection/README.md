# InstantIoT Server — API test collection (Bruno)

A ready-to-use [Bruno](https://www.usebruno.com/) collection that covers
every public route of the InstantIoT Server REST API. Useful for trying
the server out, exploring the API, and writing integration scripts.

> Bruno is an open-source alternative to Postman, file-based and
> git-friendly — every request is a plain `.bru` file you can read and
> edit by hand.

## Setup

1. Install Bruno: <https://www.usebruno.com/downloads>
2. In Bruno → **Open Collection** → select this `bruno-collection/`
   folder.
3. Top right → switch the environment to **`local`** (this fills in
   `baseUrl` = `http://localhost:8080`).
4. Make sure the server is running — see the [root README](../README.md).

## Typical flow

Run the requests in this order to populate the environment variables
(`token`, `projectId`, `deviceId`, …) used by the rest of the collection.

```
1. auth/Login                 → sets   token         (default admin/admin)
2. projects/Create Project    → sets   projectId
3. devices/Create Device      → sets   deviceId + deviceToken
4. widgets/Register Widget    → sets   widgetId
5. widgets/Get History (…)    → query the time-series
```

The post-response scripts in each request automatically capture the
relevant IDs into the environment, so you almost never need to copy/paste
anything by hand.

## Collection layout

| Folder | Routes |
|---|---|
| `auth/` | `/api/status`, login, register, change own password |
| `projects/` | CRUD for projects + layout sync |
| `devices/` | CRUD for devices + token renewal + rename |
| `widgets/` | Register / get state / query history (raw/min/hour/day) / delete |
| `admin/` | Admin-only routes: stats, server info, history & backup config, user reset, registration toggle, restart |

Every authenticated route uses `Authorization: Bearer {{token}}`. The
admin folder additionally requires the logged-in user to have
`role = admin` (which is the case for the bootstrap `admin` account).

## Variables (environment `local`)

| Variable | Filled by | Used in |
|---|---|---|
| `baseUrl` | static (`http://localhost:8080`) | every request |
| `token` | `auth/Login` post-response | every authenticated request |
| `projectId` | `projects/Create Project` post-response | most project/device/widget routes |
| `deviceId`, `deviceToken` | `devices/Create Device` post-response | device + widget routes |
| `widgetId` | `widgets/Register Widget` post-response | widget routes |
| `userId` | set manually (from `admin/List Users`) | `admin/Reset User Password` |
| `seriesId`, `fromMs`, `toMs` | set manually for history queries | `widgets/Get History …` |

Pull requests welcome to add new requests or refine existing ones.
