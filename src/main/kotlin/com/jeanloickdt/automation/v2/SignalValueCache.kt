package com.jeanloickdt.automation.v2

import com.jeanloickdt.signal.SignalFrame
import com.jeanloickdt.signal.domain.SignalRepository
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * La dernière valeur connue de chaque signal — ce que la CONDITION interroge.
 *
 * ## Pourquoi ce cache existe
 *
 * C'est tout l'intérêt d'avoir séparé le déclencheur de la condition.
 * `SignalChanged(temp)` réveille la règle avec la valeur de `temp` dans
 * l'événement ; mais `Compare(hum, LT, 40)` demande la valeur de `hum`, qui
 * n'est nulle part dans cet événement. Sans ce cache, « quand la température
 * change, SI l'humidité est basse » exigerait une lecture en base par trame —
 * exactement ce que le chemin chaud ne peut pas payer.
 *
 * ## Lecture traversante, et pourquoi PAS un amorçage au démarrage
 *
 * Un cache vide au démarrage rendrait `Absent` pour tout signal n'ayant pas
 * reparlé depuis, donc `UNKNOWN`, donc aucun tir : après chaque redéploiement,
 * les règles se tairaient silencieusement jusqu'à ce que chaque signal
 * référencé émette. C'est le pire mode de panne du système — celui où rien ne
 * casse visiblement.
 *
 * Un balayage au démarrage aurait corrigé ça, au prix d'une lecture de tout
 * l'inventaire à chaque boot, et d'un amorçage qui se désynchronise dès qu'un
 * signal est créé après coup.
 *
 * D'où la lecture traversante : un manque va chercher `lastPayload` en base,
 * une fois, et s'en souvient. Le coût est une lecture par signal et par vie du
 * processus — le même patron que `CachedSignalRepository`, dont c'est déjà la
 * discipline maison.
 *
 * ## L'absence est mémorisée aussi
 *
 * Un signal déclaré qui n'a jamais parlé n'a pas de `lastPayload`. Sans
 * mémoriser ce vide, chaque évaluation le redemanderait à la base — et c'est
 * précisément le cas d'une règle écrite avant que la carte ne soit branchée,
 * donc évaluée en boucle pour rien.
 *
 * ## Ce que le cache NE fait pas
 *
 * Il ne distingue pas `int` de `float` : le fil rend un double, et la
 * promotion du §2 les rend indiscernables au moment de comparer. La
 * distinction sert à la VALIDATION, qui lit le type déclaré en base, pas à
 * l'évaluation.
 */
class SignalValueCache(
    private val signals: SignalRepository
) {

    private val values = ConcurrentHashMap<String, SignalValue>()

    private fun key(ownerId: String, deviceId: String, address: Int) =
        "$ownerId|$deviceId|$address"

    /**
     * Ce que vaut ce signal maintenant. Jamais `null` : l'absence est une
     * réponse, et elle vaut `Absent` — jamais `0`.
     */
    fun valueOf(ownerId: String, ref: SignalRef): SignalValue =
        values.computeIfAbsent(key(ownerId, ref.deviceId, ref.address)) {
            readFromStore(ownerId, ref.deviceId, ref.address)
        }

    /** Une trame numérique vient d'arriver. Appelé par le moteur, pas par la base. */
    fun putNumeric(ownerId: String, deviceId: String, address: Int, value: Double) {
        values[key(ownerId, deviceId, address)] =
            SignalValue.Present(TypedValue.Float(value))
    }

    /** Une trame textuelle vient d'arriver. */
    fun putText(ownerId: String, deviceId: String, address: Int, text: String) {
        values[key(ownerId, deviceId, address)] =
            SignalValue.Present(TypedValue.Text(text))
    }

    /**
     * Oublie ce signal — à la suppression, ou au changement de type.
     *
     * Un type qui change rend les octets stockés illisibles pour l'ancienne
     * lecture : garder la valeur d'avant reviendrait à comparer un nombre
     * qui n'existe plus.
     */
    fun forget(ownerId: String, deviceId: String, address: Int) {
        values.remove(key(ownerId, deviceId, address))
    }

    /** Vide tout — au rechargement complet du cache des règles. */
    fun clear() = values.clear()

    /** Ce que le cache tient, pour les épreuves et la page de santé. */
    val size: Int get() = values.size

    private fun readFromStore(ownerId: String, deviceId: String, address: Int): SignalValue {
        val row = signals.find(ownerId, deviceId, address) ?: return SignalValue.Absent
        val b64 = row.lastPayload ?: return SignalValue.Absent
        val bytes = runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
            ?: return SignalValue.Absent

        SignalFrame.storedNumeric(row.type, bytes)?.let {
            return SignalValue.Present(TypedValue.Float(it))
        }
        SignalFrame.storedText(row.type, bytes)?.let {
            return SignalValue.Present(TypedValue.Text(it))
        }
        // Des octets qui ne portent pas ce que le type annonce — une carte
        // reflashee, une declaration changee. Absent, jamais une valeur
        // inventee : c'est la meme politique que la garde d'ingestion.
        return SignalValue.Absent
    }
}
