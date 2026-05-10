package com.jeanloickdt.database

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager

object DatabaseFactory {
    fun init(vararg tables: Table) {
        // DB stockée dans ~/.instantiot/ (à côté de licence.key, secret.key,
        // setup.done) — single source of truth pour tous les fichiers d'état
        // serveur. Évite que le CWD du process (systemd, jpackage, gradlew run
        // depuis n'importe où) influence la position de la DB.
        val url = "jdbc:sqlite:${com.jeanloickdt.common.ServerConfig.dbFile.absolutePath}"

        DriverManager.getConnection(url).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL")
                stmt.execute("PRAGMA synchronous=NORMAL")
                stmt.execute("PRAGMA cache_size=-32000")
                stmt.execute("PRAGMA temp_store=MEMORY")
            }
        }

        Database.connect(url = url, driver = "org.sqlite.JDBC")

        transaction {
            // ─── Auto-migration des nouvelles colonnes/tables ───
            // `createMissingTablesAndColumns` :
            //   1. Crée les tables manquantes (comme l'ancien `create`)
            //   2. ALTER TABLE ... ADD COLUMN pour les colonnes nouvelles
            //      ajoutées dans le code mais absentes de la DB existante
            //
            // → Quand on ajoute une feature (ex: tags sur widgets, config
            //   notifications) qui requiert une nouvelle colonne dans une
            //   `Table` Exposed, l'upgrade des users existants migre tout
            //   seul au premier boot. Aucun script manuel à écrire.
            //
            // ⚠️ Règles de safety à respecter dans le code des `Table` :
            //   - Toute nouvelle colonne doit être `.nullable()` ou avoir
            //     un `.default(...)` (sinon ALTER échoue sur les rows
            //     existantes qui n'auraient pas la valeur).
            //   - Ne JAMAIS rename une colonne (Exposed ne le détecte
            //     pas → orphan column dans la DB). Préférer :
            //     ajout nouvelle colonne → migration manuelle des données
            //     → drop ancienne colonne (manuel, plus tard).
            //   - Ne jamais changer le type d'une colonne existante
            //     (idem, non détecté).
            //
            // Pour les changements destructifs/renames, écrire un bloc
            // `runCatching { exec("ALTER TABLE ...") }` ci-dessous (cf.
            // les ALTER legacy sur `devices` conservés en no-op pour les
            // DBs déjà migrées).
            //
            // Note : `createMissingTablesAndColumns` est deprecated par
            // Exposed qui pousse vers Flyway pour les setups prod. Pour
            // un server self-hosted avec des migrations simples additives,
            // cette fonction reste le bon outil. On suppress le warning
            // explicitement plutôt que de surcharger le projet d'un
            // migration tool externe.
            @Suppress("DEPRECATION")
            SchemaUtils.createMissingTablesAndColumns(*tables)

            // ─── Migrations legacy (devices) ──────────────────
            // Conservés pour les DBs qui ont été migrées manuellement à
            // l'époque où `SchemaUtils.create()` était utilisé. No-op
            // sur les nouvelles installs (la colonne existe déjà via
            // createMissingTablesAndColumns) et sur les DBs déjà
            // migrées (duplicate column → caught silencieusement).
            runCatching { exec("ALTER TABLE devices ADD COLUMN device_type TEXT") }
            runCatching { exec("ALTER TABLE devices ADD COLUMN connectivity TEXT") }

            // index pour widget_history — queries rapides par plage de temps
            exec("CREATE INDEX IF NOT EXISTS idx_history_widget  ON widget_history (widget_id, recorded_at)")
            exec("CREATE INDEX IF NOT EXISTS idx_history_project ON widget_history (project_id, recorded_at)")

            // index pour widget_history_numeric — lectures chart time-range
            exec("CREATE INDEX IF NOT EXISTS idx_history_numeric_widget ON widget_history_numeric (widget_id, recorded_at)")
            exec("CREATE INDEX IF NOT EXISTS idx_history_numeric_widget_series ON widget_history_numeric (widget_id, series_id, recorded_at)")
            exec("CREATE INDEX IF NOT EXISTS idx_history_numeric_project ON widget_history_numeric (project_id, recorded_at)")

            // INDEX UNIQUE pour les tables d'agrégation — idempotence via
            // INSERT OR IGNORE dans HistoryAggregator. COALESCE série → ''
            // pour matcher les null (série absente côté gauge/metric/etc).
            exec("CREATE UNIQUE INDEX IF NOT EXISTS uniq_history_min  ON widget_history_min  (widget_id, COALESCE(series_id, ''), bucket_at)")
            exec("CREATE UNIQUE INDEX IF NOT EXISTS uniq_history_hour ON widget_history_hour (widget_id, COALESCE(series_id, ''), bucket_at)")
            exec("CREATE UNIQUE INDEX IF NOT EXISTS uniq_history_day  ON widget_history_day  (widget_id, COALESCE(series_id, ''), bucket_at)")

            // Index de lecture time-range — même stratégie que raw
            exec("CREATE INDEX IF NOT EXISTS idx_history_min_widget_series  ON widget_history_min  (widget_id, series_id, bucket_at)")
            exec("CREATE INDEX IF NOT EXISTS idx_history_hour_widget_series ON widget_history_hour (widget_id, series_id, bucket_at)")
            exec("CREATE INDEX IF NOT EXISTS idx_history_day_widget_series  ON widget_history_day  (widget_id, series_id, bucket_at)")

            // Cleanup par projet (cascade DELETE)
            exec("CREATE INDEX IF NOT EXISTS idx_history_min_project  ON widget_history_min  (project_id)")
            exec("CREATE INDEX IF NOT EXISTS idx_history_hour_project ON widget_history_hour (project_id)")
            exec("CREATE INDEX IF NOT EXISTS idx_history_day_project  ON widget_history_day  (project_id)")
        }
    }
}