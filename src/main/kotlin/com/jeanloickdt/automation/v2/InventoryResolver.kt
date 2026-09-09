package com.jeanloickdt.automation.v2

import com.jeanloickdt.device.domain.DeviceRepository
import com.jeanloickdt.signal.domain.SignalRepository

/**
 * Le pont entre la validation des règles et l'inventaire réel.
 *
 * [RuleValidation] ne connaît que cette interface, et c'est délibéré : les
 * tables de vérité et la politique de type se prouvent sur un inventaire
 * fabriqué, sans base. Ce fichier est la seule implémentation qui touche du
 * vrai.
 *
 * ## Pourquoi il n'y a rien de plus ici
 *
 * On aurait pu y mettre du cache. Ce serait une erreur : la validation ne
 * tourne qu'à l'enregistrement d'une règle, au chargement du cache, et quand
 * le type d'un signal change. Jamais par trame — le chemin d'ingestion est le
 * goulot CPU n°1, il ne résout aucun type. Un cache ici n'accélérerait que ce
 * qui est déjà rare, et introduirait un libellé périmé de plus.
 *
 * Les lectures passent d'ailleurs par [SignalRepository], qui est déjà caché :
 * le vrai coût est amorti une couche plus bas, là où il l'est pour tout le
 * monde.
 */
class InventoryResolver(
    private val signals: SignalRepository,
    private val devices: DeviceRepository
) : RuleValidation.Resolver {

    override fun signal(ownerId: String, ref: SignalRef): RuleValidation.ResolvedSignal? {
        val row = signals.find(ownerId, ref.deviceId, ref.address) ?: return null
        // Un type que le domaine ne connait pas — une declaration ecrite par
        // une version plus recente, relue apres un retour arriere. On rend
        // `null`, donc `signal-deleted` : la regle ne tourne pas, et le motif
        // est visible. Deviner un type serait exactement la coercition que la
        // politique refuse.
        val type = SignalType.ofWire(row.type) ?: return null
        return RuleValidation.ResolvedSignal(
            type = type,
            automationVisible = row.automationVisible,
            label = row.label
        )
    }

    override fun deviceLabel(ownerId: String, ref: DeviceRef): String? =
        // `findById` ne resout que ce qui est au bon compte : l'appartenance
        // est portee par la signature, pas par une verification qu'on pourrait
        // oublier d'ecrire.
        devices.findById(ownerId, ref.deviceId)?.name
}
