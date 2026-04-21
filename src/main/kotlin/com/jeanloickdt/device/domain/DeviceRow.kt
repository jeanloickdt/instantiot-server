// device/domain/DeviceRow.kt
package com.jeanloickdt.device.domain

data class DeviceRow(
    val id: String,
    val projectId: String,
    val ownerId: String,
    val name: String,
    val tokenHash: String,
    val lastSeen: Long?,
    val isOnline: Boolean,
    /**
     * Type matériel — nullable pour les devices antérieurs à l'ajout de
     * cette colonne. Les nouveaux devices exigent un type non-null.
     */
    val deviceType: DeviceType?,
    /**
     * Mode de connectivité physique. Nullable pour la même raison.
     * Doit être dans [DEVICE_CONNECTIVITY_MAP]\[deviceType\] lors de la
     * création pour être accepté.
     */
    val connectivity: DeviceConnectivity?
)