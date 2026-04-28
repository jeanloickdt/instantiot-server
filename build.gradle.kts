plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)
}

group = "com.jeanloickdt"
version = "0.0.1"

application {
    mainClass = "com.jeanloickdt.ApplicationKt"
}

ktor{
    docker {
        jreVersion.set(JavaVersion.VERSION_22)
    }
}
kotlin {
    jvmToolchain(22)
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
        "--java-options", "-Dfile.encoding=UTF-8"
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
                // Copy le .service dans lib/ (sera installé dans /opt/instantiot-server/lib/)
                val libDir = linuxResourceDir.resolve("lib").apply { mkdirs() }
                file("src/main/packaging/linux/instantiot-server.service")
                    .copyTo(libDir.resolve("instantiot-server.service"), overwrite = true)
                // Scripts dpkg à la racine
                listOf("postinst", "prerm").forEach { name ->
                    val src = file("src/main/packaging/linux/$name")
                    val dst = linuxResourceDir.resolve(name)
                    src.copyTo(dst, overwrite = true)
                    dst.setExecutable(true, false)
                }
            }
            baseArgs += listOf(
                "--linux-shortcut",
                "--linux-menu-group", "Network",
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
