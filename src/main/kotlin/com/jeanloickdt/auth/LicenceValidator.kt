package com.jeanloickdt.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.slf4j.LoggerFactory
import java.io.File
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Date

/**
 * Validation de licence par JWT signe (RS256).
 *
 * Flow :
 *   1. La cle publique RSA est embarquee dans le JAR (resources/licence-public.pem)
 *   2. Le client entre son JWT licence dans le dashboard admin
 *   3. Le serveur verifie la signature avec la cle publique
 *   4. Si valide → stocke dans ~/.instantiot/licence.key
 *   5. Aux demarrages suivants → lit le fichier, re-verifie signature + expiration
 *
 * Impossible a forger sans la cle privee (restee chez InstantIoT).
 */
object LicenceValidator {

    private val logger = LoggerFactory.getLogger("LicenceValidator")

    private const val ISSUER = "instantiot.io"

    private val licenceFile = File("${System.getProperty("user.home")}/.instantiot/licence.key")

    // info licence en cache apres validation
    private var cachedLicence: LicenceInfo? = null

    // cle publique RSA chargee depuis les resources
    private val publicKey: RSAPublicKey? by lazy {
        try {
            val pemText = this::class.java.classLoader
                .getResourceAsStream("licence-public.pem")
                ?.bufferedReader()?.readText()
                ?: return@lazy null

            val keyContent = pemText
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\s".toRegex(), "")

            val keyBytes = Base64.getDecoder().decode(keyContent)
            val keySpec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("RSA")
            keyFactory.generatePublic(keySpec) as RSAPublicKey
        } catch (e: Exception) {
            logger.error("Failed to load licence public key", e)
            null
        }
    }

    /**
     * Charger la licence depuis le fichier local.
     * Appele au demarrage du serveur.
     */
    fun load() {
        if (!licenceFile.exists()) {
            logger.info("No licence file found — licence required")
            return
        }

        try {
            val jwt = licenceFile.readText().trim()
            val info = verifyJwt(jwt)
            if (info != null) {
                cachedLicence = info
                logger.info("Licence loaded — id=${info.id} plan=${info.plan} expires=${Date(info.expiresAt)}")
            } else {
                logger.warn("Licence file is invalid or expired — licence required")
                cachedLicence = null
            }
        } catch (e: Exception) {
            logger.warn("Failed to read licence file — ${e.message}")
            cachedLicence = null
        }
    }

    /**
     * La licence est-elle valide et non expiree ?
     */
    fun isActivated(): Boolean {
        val licence = cachedLicence ?: return false
        return licence.expiresAt > System.currentTimeMillis()
    }

    /**
     * Activer une licence — verifier le JWT et stocker dans le fichier.
     * Retourne LicenceInfo si valide, null si invalide.
     */
    fun activate(jwtToken: String): LicenceInfo? {
        val info = verifyJwt(jwtToken.trim()) ?: return null

        // stocker le JWT dans le fichier
        licenceFile.parentFile.mkdirs()
        licenceFile.writeText(jwtToken.trim())

        cachedLicence = info
        logger.info("Licence activated — id=${info.id} plan=${info.plan} expires=${Date(info.expiresAt)}")
        return info
    }

    /**
     * Info licence courante (pour affichage dashboard).
     */
    fun getLicenceInfo(): LicenceInfo? = cachedLicence

    /**
     * Verifier un JWT licence avec la cle publique RSA.
     * Retourne LicenceInfo si valide, null si invalide/expire/signature incorrecte.
     */
    private fun verifyJwt(jwtToken: String): LicenceInfo? {
        val key = publicKey
        if (key == null) {
            logger.error("Public key not loaded — cannot verify licence")
            return null
        }

        return try {
            val verifier = JWT.require(Algorithm.RSA256(key, null))
                .withIssuer(ISSUER)
                .build()

            val decoded = verifier.verify(jwtToken)

            LicenceInfo(
                id        = decoded.subject ?: "unknown",
                plan      = decoded.getClaim("plan").asString() ?: "unknown",
                expiresAt = decoded.expiresAt?.time ?: 0L
            )
        } catch (e: Exception) {
            logger.warn("Licence JWT verification failed — ${e.message}")
            null
        }
    }
}

/**
 * Info licence — donnees extraites du JWT.
 */
data class LicenceInfo(
    val id: String,        // ex: "INST-A7K2-M9X1-P3B8"
    val plan: String,      // ex: "beta", "pro", "enterprise"
    val expiresAt: Long    // timestamp ms
)
