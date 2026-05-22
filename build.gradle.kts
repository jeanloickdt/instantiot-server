plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)
}

group = "com.jeanloickdt"
version = "1.1.0"

application {
    mainClass = "com.jeanloickdt.ApplicationKt"
}

// ════════════════════════════════════════════════════════════
// Version single-source : `version` ci-dessus est l'UNIQUE source
// de vérité. On la matérialise dans une ressource générée que
// ServerConfig lit au runtime → plus jamais de désync entre le
// JAR/installer et ce qu'affiche /api/status + mDNS + log boot.
// Bump = changer SEULEMENT la ligne `version =` plus haut.
// ════════════════════════════════════════════════════════════
val generateVersionResource by tasks.registering {
    val versionValue = project.version.toString()
    val outDir = layout.buildDirectory.dir("generated/version")
    inputs.property("version", versionValue)
    outputs.dir(outDir)
    doLast {
        val f = outDir.get().asFile.apply { mkdirs() }
            .resolve("instantiot-version.properties")
        f.writeText("version=$versionValue\n")
    }
}

sourceSets {
    named("main") {
        resources.srcDir(generateVersionResource)
    }
}

ktor{
    docker {
        jreVersion.set(JavaVersion.VERSION_21)
    }
}
kotlin {
    // Java 21 — LTS, dispo dans apt sur Debian Trixie / Ubuntu 24.04 et
    // partout ailleurs (Homebrew, SDKMAN, jpackage, runners CI). Évite
    // les soucis de Java 22 (non-LTS, pas dans apt) au moment du
    // packaging .deb pour Raspberry Pi.
    jvmToolchain(21)
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.host.common)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)
    implementation(libs.ktor.server.config.yaml)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)

    implementation("org.mindrot:jbcrypt:0.4")


    implementation("org.jetbrains.exposed:exposed-core:0.61.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.61.0")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    // mDNS / Bonjour — annonce le service "_instantiot._tcp" sur le LAN
    // pour que l'app le découvre automatiquement (cf. MdnsPublisher).
    implementation("org.jmdns:jmdns:3.6.1")
}

// ════════════════════════════════════════════════════════════
// jpackage — installer natif par OS (macOS .dmg, Win .msi, Linux .deb)
// ────────────────────────────────────────────────────────────
// Stratégie V1 : chaque OS build son propre installer via jpackage.
// Pas de cross-compilation pour V1 (CI matrix viendra plus tard).
//
// Usage local :
//   ./gradlew packageInstaller
//
// Output : build/jpackage/InstantIoT-Server-X.Y.Z.{dmg,msi,deb}
//
// Pré-requis : `jpackage` est dans le JDK 22 (JAVA_HOME/bin/jpackage)
// ════════════════════════════════════════════════════════════
val packageInstaller by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Build native installer for current OS (.dmg / .msi / .deb)"

    // jpackage a besoin du fat JAR — dépend de la tâche Ktor
    dependsOn("buildFatJar")

    val osName = System.getProperty("os.name").lowercase()
    val installerType = when {
        osName.contains("mac")   -> "dmg"
        osName.contains("win")   -> "msi"
        osName.contains("linux") -> "deb"
        else -> error("Unsupported OS for jpackage: $osName")
    }

    val outDir = layout.buildDirectory.dir("jpackage").get().asFile
    val fatJarDir = layout.buildDirectory.dir("libs").get().asFile
    // Le buildFatJar du plugin Ktor produit `<project>-all.jar`
    val fatJarName = "${project.name}-all.jar"

    doFirst {
        outDir.deleteRecursively()
        outDir.mkdirs()
    }

    val jpackageBin = "${System.getProperty("java.home")}/bin/jpackage"

    val baseArgs = mutableListOf(
        jpackageBin,
        "--type", installerType,
        "--input", fatJarDir.absolutePath,
        "--main-jar", fatJarName,
        "--main-class", "com.jeanloickdt.ApplicationKt",
        "--name", "InstantIoT Server",
        // jpackage refuse les versions commençant par 0 (genre "0.0.1")
        // Pour une version semver "MAJOR.MINOR.PATCH" si MAJOR == 0, on la
        // bumppe à 1.0.0 pour le packaging — la version "logique" du
        // serveur reste celle de project.version, juste le binaire packaged
        // affiche 1.0.0 dans les métadonnées OS.
        "--app-version", project.version.toString().let { v ->
            if (v.startsWith("0.")) "1.0.0" else v
        },
        "--vendor", "InstantIoT",
        "--description", "Self-hosted IoT dashboard server for makers",
        "--copyright", "© 2026 InstantIoT",
        "--dest", outDir.absolutePath,
        // -Xmx128m suffit largement (relay TCP léger + SQLite local)
        // -Dfile.encoding=UTF-8 pour cohérence cross-platform
        "--java-options", "-Xmx256m",
        "--java-options", "-Dfile.encoding=UTF-8",
        // SQLite JDBC charge une lib native (System.load). Sans ça, JDK 24+
        // émet un WARNING au boot et **bloquera** dans une future release.
        // On autorise explicitement l'accès natif pour le module unnamed.
        "--java-options", "--enable-native-access=ALL-UNNAMED"
    )

    // Options par OS
    when (installerType) {
        "dmg" -> {
            baseArgs += listOf(
                "--mac-package-name", "InstantIoTServer"
            )
        }
        "msi" -> {
            baseArgs += listOf(
                "--win-menu",
                "--win-shortcut",
                "--win-dir-chooser",
                "--win-menu-group", "InstantIoT"
            )
        }
        "deb" -> {
            // ─── Resources Linux : service systemd + scripts dpkg ─
            // jpackage --resource-dir prend un dossier dont :
            //   - le contenu de `lib/` est copié dans /opt/<pkg>/lib/
            //   - les scripts `postinst`, `prerm`, `postrm`, `preinst`
            //     sont utilisés tels quels par dpkg
            // On stage tout ça dans build/jpackage-linux-resources/.
            val linuxResourceDir = layout.buildDirectory.dir("jpackage-linux-resources").get().asFile
            doFirst {
                linuxResourceDir.deleteRecursively()
                linuxResourceDir.mkdirs()

                // ─── Service systemd : INLINÉ dans le postinst ───
                // jpackage --resource-dir n'embarque PAS les fichiers
                // d'un sous-dossier lib/ dans le .deb final ; on injecte
                // donc le contenu du .service directement dans le postinst
                // via un marqueur unique. À l'install, le postinst écrit
                // le fichier dans /etc/systemd/system/ avec un cat <<EOF.
                // Source-of-truth unique : le .service dans
                // src/main/packaging/linux/ reste lisible/versionné.
                //
                // ⚠️ Le marqueur "@@SERVICE_INLINE_MARKER@@" doit être
                // unique dans le postinst — ne JAMAIS l'utiliser dans
                // un commentaire car String.replace() est global et
                // toute occurrence sera substituée (bug subtil : si
                // marqueur dans un commentaire shell, le contenu .service
                // est injecté HORS du heredoc → bash exécute chaque ligne).
                val serviceContent = file("src/main/packaging/linux/instantiot-server.service").readText()
                val marker = "@@SERVICE_INLINE_MARKER@@"

                listOf("postinst", "prerm").forEach { name ->
                    val src = file("src/main/packaging/linux/$name")
                    val dst = linuxResourceDir.resolve(name)
                    val srcText = src.readText()
                    // Garde-fou : on s'attend à exactement 1 occurrence du marqueur
                    // dans le postinst, 0 dans le prerm. Plus = bug à corriger.
                    val occurrences = srcText.split(marker).size - 1
                    val expected = if (name == "postinst") 1 else 0
                    require(occurrences == expected) {
                        "$name: expected $expected occurrence(s) of $marker, found $occurrences"
                    }
                    val content = srcText.replace(marker, serviceContent.trimEnd())
                    dst.writeText(content)
                    dst.setExecutable(true, false)
                }
            }
            // ⚠️ PAS de --linux-shortcut : un server headless (cas
            // typique sur Raspberry Pi sans desktop) n'a aucun usage
            // d'un raccourci .desktop, et cette flag ajoute une dep
            // sur xdg-utils qui n'est PAS pré-installé sur Raspberry
            // Pi OS Lite → install bloque avec "depends on xdg-utils".
            // Pareil pour --linux-menu-group qui implique aussi xdg.
            baseArgs += listOf(
                "--linux-app-category", "utils",
                "--linux-package-name", "instantiot-server",
                "--resource-dir", linuxResourceDir.absolutePath
            )
        }
    }

    commandLine = baseArgs

    doLast {
        println()
        println("✅ Installer built in: $outDir")
        outDir.listFiles()?.forEach { println("   $it (${it.length() / 1024 / 1024} MB)") }
    }
}
