package com.jeanloickdt

import io.ktor.server.testing.ApplicationTestBuilder
import java.io.File
import java.net.ServerSocket

/**
 * Monter `module()` dans une epreuve, sans se battre pour un port.
 *
 * ## Ce que ceci repare
 *
 * `module()` OUVRE le relais TCP des cartes, sur le port que
 * `ServerConfig.runningTcpPort` designe. Ce port est un etat GLOBAL et
 * MUTABLE : en production, `main()` l'ecrit une fois apres avoir trouve un
 * port libre.
 *
 * Dans un travailleur de test, plusieurs epreuves montent le module l'une
 * apres l'autre dans la meme JVM. Elles se partagent donc ce global, et la
 * seconde tombe sur « Address already in use » — un echec qui ne dit rien de
 * ce qu'elle eprouvait, et qui apparait ou disparait selon l'ORDRE
 * d'execution. Le pire genre : celui qu'on croit avoir corrige parce qu'il
 * ne s'est pas reproduit.
 *
 * Chaque montage prend ici son propre port. Une seule fonction pour toutes
 * les epreuves, parce que trois copies de cette precaution feraient trois
 * occasions d'en oublier une.
 *
 * ## Ce que ceci n'eprouve pas
 *
 * `main()`, qui CHOISIT les ports, monte l'icone de la barre des taches et
 * peut appeler `System.exit`. Elle n'est pas montable dans un travailleur de
 * test, et ce qu'elle fait de plus est de l'environnement, pas de la
 * composition.
 */
fun ApplicationTestBuilder.monterLeVraiModule(base: File) {
    val libre = ServerSocket(0).use { it.localPort }
    com.jeanloickdt.common.ServerConfig.markRunningPorts(
        http = com.jeanloickdt.common.ServerConfig.runningHttpPort,
        tcp = libre
    )
    application { module(dbFile = base) }
}

/** Une base jetable, jamais celle de l'utilisateur. */
fun baseJetable(nom: String): File =
    File.createTempFile("instantiot-$nom-", ".db")
        .apply { delete(); deleteOnExit() }
