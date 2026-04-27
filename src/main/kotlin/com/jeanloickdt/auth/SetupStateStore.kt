package com.jeanloickdt.auth

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

/**
 * Marker file-based pour tracker si l'écran welcome a déjà été
 * affiché et acté par l'utilisateur (clic Renew OU Skip).
 *
 * **Sémantique** : la présence du fichier `~/.instantiot/setup.done`
 * signifie "l'user a vu et acté le welcome". L'absence signifie
 * "il faut le montrer (encore)".
 *
 * **Quand est-il créé ?** : par [markComplete] depuis le handler
 * de `POST /api/setup/welcome` (NEXT phase) — pas avant. Si l'user
 * ferme son browser entre l'activation de la licence et le clic
 * sur welcome, le fichier reste absent → welcome réapparaît au
 * prochain refresh (sémantique propre).
 *
 * **Sécurité** : ce fichier n'est PAS une frontière de sécurité.
 * Quiconque a accès filesystem peut aussi lire `licence.key` et
 * `secret.key` → game over avant même de toucher à `setup.done`.
 * Le rôle du fichier est purement UX (éviter de re-spam le welcome).
 *
 * Permissions POSIX 600 sur création — empêche les autres users
 * locaux de tampering. No-op sur Windows (POSIX non supporté).
 */
class SetupStateStore(
    private val markerFile: Path = defaultPath()
) {

    private val log = LoggerFactory.getLogger("SetupStateStore")

    fun isComplete(): Boolean = Files.exists(markerFile)

    /**
     * Marque le setup comme terminé en touchant le fichier.
     * Idempotent (no-op si déjà existant).
     */
    fun markComplete() {
        if (Files.exists(markerFile)) return
        Files.createDirectories(markerFile.parent)
        Files.createFile(markerFile)
        try {
            Files.setPosixFilePermissions(
                markerFile,
                PosixFilePermissions.fromString("rw-------")
            )
        } catch (_: UnsupportedOperationException) {
            // Windows ou FS non-POSIX — on ignore, c'est juste de l'hygiène
        }
        log.info("Setup marked complete: {}", markerFile)
    }

    companion object {
        fun defaultPath(): Path =
            Paths.get(System.getProperty("user.home"), ".instantiot", "setup.done")
    }
}
