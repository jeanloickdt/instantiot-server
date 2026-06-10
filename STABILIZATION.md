# 🛠️ InstantIoT Server — Plan de stabilisation (v1.1.3)

> **Objectif** : rendre le serveur self-hosted **solide, propre et fini** ("production-ready")
> **avant** d'attaquer le futur cloud multi-tenant. Tant que les P0 ci-dessous ne sont
> pas réglés, le serveur peut perdre des données ou crasher sous charge normale.
>
> Audit réalisé sur la version **1.1.3** (`apps/instantiot-server`, AGPLv3).
> Les références `fichier:ligne` ont été vérifiées sur cette version.

---

## 📊 Diagnostic en une phrase

Le moteur (outbox + backpressure, mDNS, backup `VACUUM INTO`, shutdown-flush, systemd durci,
logs qui tronquent les secrets) est **au-dessus de la moyenne**. Ce qui empêche de shipper sereinement
se concentre sur : **(1) la couche SQLite non durcie**, **(2) ~1 seul test**, **(3) des détails de finition**.

Niveau de priorité :
- 🔴 **P0** — bugs de stabilité : *ça VA casser en prod*. Bloquant pour shipper.
- 🟠 **P1** — finition pro : passe de "ça marche chez moi" à "produit fini".
- 🟡 **P2** — polish & dette.

---

## 🔴 P0 — Sprint Stabilité (bloquant)

### P0-1 — `busy_timeout=0` + PRAGMA inefficaces + pas de pool → "database is locked" garanti
**Fichier** : `src/main/kotlin/com/jeanloickdt/database/DatabaseFactory.kt:36-45`
**Problème** : les `PRAGMA` (`synchronous`, `cache_size`, `temp_store`) sont posés sur une connexion
`DriverManager` ouverte dans un `.use { }` qui la **ferme avant** `Database.connect(url)` (ligne 45).
Ils ne s'appliquent donc à **aucune** connexion réellement utilisée par Exposed.
Seul `journal_mode=WAL` survit (stocké dans l'en-tête du fichier).
`busy_timeout` n'est réglé **nulle part** (`grep busy_timeout|DataSource|Hikari` → 0 résultat) → défaut SQLite = **0**.
**Conséquence** : dès que deux écritures se chevauchent (flush 5 s + `updateLastPayload` + cleanup horaire +
backup + écriture REST), SQLite renvoie `SQLITE_BUSY` **immédiatement** au lieu d'attendre. La transaction
jette, non retentée → trou d'historique ou 500. **C'est l'incident #1 le plus probable en prod.**
**Fix** :
- Créer un `SQLiteConfig`/`SQLiteDataSource` avec `setBusyTimeout(5000)` (+ `synchronous=NORMAL`,
  `cache_size`, `temp_store`, `journal_mode=WAL`) et le passer à `Database.connect(dataSource)`.
- Sérialiser les écritures (une connexion d'écriture unique / un dispatcher mono-thread d'écriture)
  pour éliminer le lock-storm à la source.
**Effort** : ~1 h. **Levier** : énorme.

### P0-2 — La boucle de flush 5 s n'a pas de try/catch → elle meurt → fuite RAM → OOM
**Fichier** : `src/main/kotlin/com/jeanloickdt/Application.kt:316-321`
**Problème** : `launch(Dispatchers.IO) { while (true) { delay(5_000); flush... } }` — **aucune garde**.
La première exception (typiquement le `SQLITE_BUSY` de P0-1) **tue le job définitivement**.
Ensuite les buffers/maps d'agrégation grossissent sans limite → OOM (heap ~256 Mo) → process tué.
**Conséquence** : P0-1 et P0-2 se combinent en un crash dur (perte de données + redémarrage).
**Fix** : `try/catch` autour du **corps** de la boucle (logguer + continuer), jamais autour du `while`.
Idem pour les autres boucles `while(true)` (cleanup horaire ligne ~340, backup ligne ~381).
**Effort** : ~30 min. **Levier** : énorme.

> 💡 **P0-1 + P0-2 ensemble ≈ 1 h 30 et changent radicalement tout le tableau de fiabilité.**

### P0-3 — Accept loop sans cap de connexions ni backoff + reads bloquants sur `Dispatchers.IO`
**Fichier** : `src/main/kotlin/com/jeanloickdt/relay/DeviceRelay.kt:108-123`
**Problème** : `while (!serverSocket.isClosed) { accept(); launch(Dispatchers.IO){...} }`.
Aucun `Semaphore`/cap. Les reads device sont **bloquants** sur `Dispatchers.IO` (64 threads par défaut),
pool **partagé avec toutes les écritures DB**. Au-delà de ~64 devices : famine des écritures.
Le `catch` (ligne 123) logge sans **backoff** → si `accept()` échoue en boucle (FD épuisés / EMFILE),
ça part en **spin loop** qui sature le CPU.
**Fix** :
- Pool de threads dédié pour les reads relais (isole du pool DB).
- Cap de connexions concurrentes (`Semaphore`) + rejet propre au-delà.
- Backoff (`delay`) dans le `catch` de l'accept loop.
**Effort** : ~2-3 h.

### P0-4 — Tray "Quitter/Redémarrer" = `System.exit(0)` → saute le flush → perte jusqu'à 24 h d'agrégat
**Fichiers** : `src/main/kotlin/com/jeanloickdt/common/SystemTrayManager.kt` (actions Quit/Restart),
flush à `Application.kt:206-216` (hook `ApplicationStopping`).
**Problème** : `System.exit(0)` ne garantit pas que le hook `ApplicationStopping` de Ktor s'exécute
avant la mort du process. Le flush des buckets (jour = jusqu'à 24 h d'agrégat en RAM) peut être sauté.
La garantie "zéro perte au restart contrôlé" n'est donc **pas tenue par le chemin tray**.
**Fix** : enregistrer le flush en `Runtime.getRuntime().addShutdownHook(...)` (s'exécute aussi sur
`System.exit`), ou déclencher un arrêt Ktor gracieux **avant** l'exit. Vérifier aussi que
`Restart=on-failure` (systemd) ne bloque pas un restart voulu (exit 0 ≠ failure).
**Effort** : ~1 h.

### P0-5 — Pas de `VACUUM` du DB vivant → le fichier ne fait que grossir
**Problème** : les `DELETE` de rétention (cleanup horaire) ne rendent jamais l'espace au disque ;
les pages libérées restent. Aucun `VACUUM` du DB en service (seul `VACUUM INTO` existe, pour les backups).
**Conséquence** : sur Raspberry Pi / carte SD, le fichier creuse le disque indéfiniment.
**Fix** : `VACUUM` périodique (hebdo, hors heures de pointe) **ou** `PRAGMA auto_vacuum=INCREMENTAL`
+ `incremental_vacuum` régulier. ⚠️ `auto_vacuum` doit être posé **avant** la création des tables
(ou nécessite un `VACUUM` complet pour basculer un DB existant).
**Effort** : ~1 h.

### P0-6 (sécu) — Identifiants par défaut `admin/admin` non forcés au changement
**Contexte** : la licence a été retirée en 1.1.3 (open-source AGPLv3) ; le défaut est désormais `admin/admin`.
**Problème** : sur un serveur self-hosted **public et open-source**, c'est le credential-par-défaut classique.
**Fix** : forcer le changement de mot de passe admin au **premier login** (flag `must_change_password`,
blocage de toutes les routes sauf le changement tant que le défaut n'a pas été modifié).
**Effort** : ~2 h.

---

## 🟠 P1 — Sprint Confiance & Finition

### P1-1 — Tests : <2 % de couverture *(le plus gros déficit de crédibilité)*
**État** : 1 seul test (`src/test/kotlin/ApplicationTest.kt`, smoke `GET /` → 200).
**Plan** (par ordre de ROI) :
1. **`FrameParser`** — fonctions pures (CRC8, offsets, `extractWidgetId/Payload`, trames malformées).
   Code le plus risqué, le plus trivial à tester. **Commencer ici.**
2. **Auth/JWT/ownership** — login bcrypt, génération/validation JWT, isolation device/projet.
3. **Intégration Ktor `testApplication`** — login, gating register, guards admin, CRUD device + propriété.
**Cible** : le seuil 50 % (déjà écrit, commenté, dans l'ancien `qodana.yaml`).
**Effort** : étalé — viser une 1ère vague FrameParser + auth en ~1 jour.

### P1-2 — Contrat API incohérent
**Fichier** : `src/main/kotlin/com/jeanloickdt/auth/AuthRoutes.kt:77` et `:128`
**Problème** : deux formes coexistent. La plupart des routes renvoient `mapOf("error" to ...)`,
**mais** `"Invalid credentials"` (ligne 77) et `"Username already exists"` (ligne 128) sont des **strings brutes**
→ un client qui parse `{error}` plante. `StatusPages` (`Application.kt:158`) renvoie aussi du texte sur 500,
ce qui casse le contrat JSON.
**Fix** : un seul type `ApiError(error: String, code: String?)` (+ éventuellement `ApiResponse<T>`),
utilisé **partout**, y compris dans le handler `StatusPages` (JSON, pas texte).
**Effort** : ~2-3 h.

### P1-3 — Garde admin dupliquée (~9 sites + helper `checkAdmin` redéfini 3×)
**Fichier** : `src/main/kotlin/com/jeanloickdt/auth/AuthRoutes.kt` (≈22 lignes matchant `role != "admin"`/`checkAdmin`)
**Fix** : un seul `Route.requireAdmin { }` (RouteScopedPlugin ou wrapper d'autorisation) ; remplacer tous les sites.
**Effort** : ~1 h.

### P1-4 — Pas de rotation de logs
**Fichier** : `src/main/resources/logback.xml` (uniquement un `ConsoleAppender` STDOUT)
**Problème** : sur macOS / Windows, **aucun fichier de log** → rien à inspecter après un crash.
(Sur Linux systemd, journald couvre la rotation — donc gap surtout desktop.)
**Fix** : `RollingFileAppender` (rotation taille + temps, rétention) vers `~/.instantiot/logs/`.
**Effort** : ~30 min.

### P1-5 — Pas de `/health` ni `/api/version` non authentifiés
**État** : seul `GET /api/status` (`Application.kt:408`) existe — sémantiquement un endpoint de setup,
pas une sonde de liveness propre. La version n'est exposée que via une route admin.
**Fix** : ajouter `GET /health` (liveness non-auth) + `GET /api/version` (non-auth).
**Effort** : ~30 min.

### P1-6 — Runbook opérateur manquant
**État** : le README (`# InstantIoT Server`) est correct mais il manque un **runbook** :
install par OS, emplacement des données (`~/.instantiot/`), backup/restore, lecture des logs,
changement de ports, récupération d'un admin perdu.
**Fix** : `OPERATIONS.md` (ou section dédiée du README).
**Effort** : ~2 h.

---

## 🟡 P2 — Polish & dette

- **Versionner l'API** (`/api/v1/...`) **avant** le premier breaking change.
- **launchd plist (macOS) + Service Windows** pour tourner en daemon, pas seulement en tray.
- **OpenAPI/Swagger** (spec machine + UI servie).
- **Overrides par variables d'env** (12-factor) ; supprimer le `application.yaml` mort (port/JWT non lus).
- **Rate-limit data-plane** (admin, écritures, WS, relais TCP) + vraie IP client derrière proxy
  (aujourd'hui : seulement `/login`, `/register`, `/forgot-password`).
- **TLS optionnel self-hosted** : ACME / Let's Encrypt automatisé (acme4j, challenge HTTP-01, renouvellement
  ≤21 j) en Ktor — utile dès qu'un device/app sort du LAN. *(Détail dans le futur doc cloud.)*
- **Nettoyage** : supprimer `bin/` (miroir mort), `packaging/build-installer.sh` (doublon de jpackage),
  `generateAdminPassword()` (code mort) ; dédupliquer `sha256()` (défini 2×) ; bumper logback 1.4.x → 1.5.x.
- **Métriques** (Micrometer + `/metrics`) — nice-to-have, basse priorité pour une appliance LAN.

---

## 🎯 Ordre recommandé

1. **Sprint Stabilité — P0-1 → P0-6** *(~1-2 jours)* — élimine ~80 % du risque prod. **Non négociable avant de shipper.**
2. **Sprint Confiance — P1-1 → P1-3** *(~1-2 jours)* — tests des chemins critiques + contrat API propre.
3. **Sprint Finition — P1-4 → P1-6 + P2 prioritaires** *(~1 jour)* — logs, health, runbook, nettoyage.
4. **Seulement ensuite : le cloud** — base saine, testée, propre, prête à découper en control/data plane.

---

## ✅ Definition of "ready" (à cocher avant de dire "fini")

- [x] `busy_timeout` réglé (5s) via `SQLiteDataSource` → plus de `SQLITE_BUSY` instantané *(P0-1)*
- [x] Toutes les boucles de fond (`while(true)`) sont gardées par try/catch *(P0-2)*
- [x] Cap de connexions (256) + backoff sur l'accept loop ; pool de reads isolé du pool DB *(P0-3)*
- [x] Flush garanti à l'arrêt (tray + signal) via JVM shutdown hook *(P0-4)*
- [x] `VACUUM` hebdomadaire en place → le fichier DB ne creuse plus le disque *(P0-5)*
- [x] `admin/admin` renvoie `passwordChanged=false` au login → le panel admin force l'écran de changement *(P0-6)*
- [x] Tests : FrameParser + auth + agrégation + ownership + intégration (77 tests, harness isolé) *(P1-1)*
- [x] Un seul format d'erreur JSON sur toutes les routes (type `ApiError`, y compris 500) *(P1-2)*
- [x] Garde admin centralisée (`requireAdmin`) *(P1-3)*
- [x] Rotation de logs vers `~/.instantiot/logs/` *(P1-4)*
- [x] `/health` + `/api/version` non authentifiés *(P1-5)*
- [ ] `OPERATIONS.md` : install / data / backup / restore / logs / recovery *(P1-6 — reste)*
- [ ] Migrer `ApplicationTest` sur le slim harness *(reste)*

> **Branche `stabilization/p0-reliability`** : les 6 P0 sont implémentés, compilent, les tests
> passent et le serveur boote proprement (`Application started`, relais sur pool dédié, pas de lock).
> Note : sur un DB **existant**, l'admin déjà présent a `password_changed=true` → seul un **nouvel**
> install force le changement (on ne peut pas deviner si un admit existant a déjà changé son mot de passe).
