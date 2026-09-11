package com.jeanloickdt.project

import com.jeanloickdt.database.TestDatabase
import com.jeanloickdt.project.data.ExposedProjectRepository
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * L'apparence d'un projet : une icône et une couleur, rangées sur le serveur.
 *
 * Rangées LÀ et pas sur le téléphone, parce qu'un projet cloud doit se
 * retrouver identique sur un autre appareil. C'est toute la raison du choix.
 */
class ProjectAppearanceTest {

    private lateinit var projects: ExposedProjectRepository

    @BeforeTest
    fun setup() {
        TestDatabase.fresh()
        projects = ExposedProjectRepository()
    }

    @Test
    fun `un projet nait sans apparence`() {
        // `null` dit « jamais choisie », ce qui n'est pas « choisie puis
        // retiree » — l'app peut proposer un defaut sans ecraser un choix.
        val projet = projects.create("u1", "Serre")

        assertNull(projet.icon)
        assertNull(projet.color)
    }

    @Test
    fun `l'apparence se pose et se relit`() {
        val projet = projects.create("u1", "Serre")

        val ecrit = projects.updateAppearance("u1", projet.id, "greenhouse", "teal")!!

        assertEquals("greenhouse", ecrit.icon)
        assertEquals("teal", ecrit.color)
        // Et elle survit a une relecture, pas seulement au retour de l'ecriture.
        val relu = projects.findById("u1", projet.id)!!
        assertEquals("greenhouse", relu.icon)
        assertEquals("teal", relu.color)
    }

    @Test
    fun `la liste porte l'apparence`() {
        // C'est la liste qui DESSINE les cartes. La demander projet par projet
        // ferait un aller reseau par carte pour deux mots.
        val projet = projects.create("u1", "Serre")
        projects.updateAppearance("u1", projet.id, "greenhouse", "teal")

        val sommaire = projects.findAllByOwnerSummary("u1").single()

        assertEquals("greenhouse", sommaire.icon)
        assertEquals("teal", sommaire.color)
    }

    @Test
    fun `poser null retire ce qui y etait`() {
        // C'est une ecriture complete, pas une fusion : « je ne veux plus
        // d'icone » doit pouvoir se dire.
        val projet = projects.create("u1", "Serre")
        projects.updateAppearance("u1", projet.id, "greenhouse", "teal")

        val vide = projects.updateAppearance("u1", projet.id, null, null)!!

        assertNull(vide.icon)
        assertNull(vide.color)
    }

    @Test
    fun `l'apparence se pose des la creation`() {
        // Ce que fait l'IMPORT : le fichier la porte, et un second appel pour
        // la poser serait un aller-retour de plus a rater.
        val projet = projects.create("u1", "Serre", icon = "greenhouse", color = "teal")

        assertEquals("greenhouse", projet.icon)
        assertEquals("teal", projet.color)
    }

    @Test
    fun `changer l'apparence ne fait PAS avancer la version`() {
        // `version` protege le LAYOUT contre deux ecrivains concurrents. La
        // faire avancer pour une icone ferait refuser la prochaine sauvegarde
        // de tableau de bord d'un autre appareil, sans aucune raison.
        val projet = projects.create("u1", "Serre")
        val avant = projet.version

        val apres = projects.updateAppearance("u1", projet.id, "garage", "amber")!!

        assertEquals(avant, apres.version)
    }

    @Test
    fun `le projet d'un autre compte reste hors de portee`() {
        val sien = projects.create("u2", "Sa serre")

        assertNull(projects.updateAppearance("u1", sien.id, "greenhouse", "teal"))
        assertNull(projects.findById("u2", sien.id)!!.icon)
    }
}
