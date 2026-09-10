package com.jeanloickdt.signal.data

import com.jeanloickdt.device.data.DeviceTable
import com.jeanloickdt.project.data.ProjectTable
import com.jeanloickdt.signal.domain.SignalContext
import com.jeanloickdt.signal.domain.SignalContextReader
import com.jeanloickdt.signal.domain.SignalRow
import com.jeanloickdt.signal.domain.SignalWithContext
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * La jointure signal ↔ appareil ↔ projet, en une requête.
 *
 * Deux `INNER JOIN` plutôt que N+1 lectures : un compte de 200 signaux
 * répartis sur 20 appareils coûterait 21 allers-retours en naïf, contre un
 * seul ici. Le sélecteur s'ouvre en une fois, c'est tout l'intérêt de la
 * route.
 *
 * `INNER` et non `LEFT`, délibérément. Un signal dont l'appareil ou le projet
 * a disparu est une incohérence référentielle : le proposer dans le sélecteur
 * ne produirait qu'une règle immédiatement marquée `signal-deleted`. Mieux
 * vaut ne pas le montrer que montrer un choix qui ne peut pas marcher.
 *
 * `ownerId` est vérifié sur les TROIS tables, pas seulement sur le signal.
 * Un `device_id` est devinable ; sans la contrainte sur l'appareil et sur le
 * projet, une jointure suffirait à lire le nom du projet de quelqu'un d'autre.
 */
class ExposedSignalContextReader : SignalContextReader {

    override fun listByOwnerWithContext(ownerId: String): List<SignalWithContext> = transaction {
        SignalTable
            .join(
                DeviceTable, JoinType.INNER,
                onColumn = SignalTable.deviceId, otherColumn = DeviceTable.id,
                additionalConstraint = { DeviceTable.ownerId eq ownerId }
            )
            .join(
                ProjectTable, JoinType.INNER,
                onColumn = DeviceTable.projectId, otherColumn = ProjectTable.id,
                additionalConstraint = { ProjectTable.ownerId eq ownerId }
            )
            .selectAll()
            .where { SignalTable.ownerId eq ownerId }
            // L'ordre est celui du sélecteur : projet, puis appareil, puis
            // adresse. Trier ici évite un tri côté app à chaque ouverture, et
            // surtout garantit que deux appels successifs rendent la même
            // liste — une liste qui bouge d'un rafraîchissement à l'autre est
            // illisible.
            .orderBy(ProjectTable.name)
            .orderBy(DeviceTable.name)
            .orderBy(SignalTable.address)
            .map { it.toSignalWithContext() }
    }

    override fun contextOf(ownerId: String, deviceId: String): SignalContext? = transaction {
        DeviceTable
            .join(
                ProjectTable, JoinType.INNER,
                onColumn = DeviceTable.projectId, otherColumn = ProjectTable.id,
                additionalConstraint = { ProjectTable.ownerId eq ownerId }
            )
            .selectAll()
            .where { (DeviceTable.id eq deviceId) and (DeviceTable.ownerId eq ownerId) }
            .limit(1)
            .map { it.toContext() }
            .firstOrNull()
    }

    private fun ResultRow.toContext() = SignalContext(
        projectId    = this[DeviceTable.projectId],
        projectLabel = this[ProjectTable.name],
        deviceLabel  = this[DeviceTable.name]
    )

    private fun ResultRow.toSignalWithContext() = SignalWithContext(
        signal = SignalRow(
            id          = this[SignalTable.id],
            ownerId     = this[SignalTable.ownerId],
            deviceId    = this[SignalTable.deviceId],
            address     = this[SignalTable.address],
            label       = this[SignalTable.label],
            type        = this[SignalTable.type],
            unit        = this[SignalTable.unit],
            decimals    = this[SignalTable.decimals],
            minValue    = this[SignalTable.minValue],
            maxValue    = this[SignalTable.maxValue],
            // Le jumeau porte un booleen la ou le nuage porte trois etats.
            // Voir la note de `SignalDto` : deux vocabulaires pour une idee,
            // et c'est le booleen qui fait foi ici.
            historised  = this[SignalTable.historised],
            replayOnConnect   = this[SignalTable.replayOnConnect],
            automationVisible = this[SignalTable.automationVisible],
            lastPayload = this[SignalTable.lastPayload],
            lastSeenAt  = this[SignalTable.lastSeenAt]
        ),
        context = toContext()
    )
}
