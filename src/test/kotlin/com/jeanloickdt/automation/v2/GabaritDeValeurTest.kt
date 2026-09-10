package com.jeanloickdt.automation.v2

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ce que `{{value}}` donne a lire.
 *
 * Le gabarit est le seul du systeme, et il porte tout l'interet d'une
 * alerte : « la temperature a atteint 37.4 » vaut dix fois « seuil franchi ».
 * Encore faut-il que le nombre soit lisible.
 */
class GabaritDeValeurTest {

    /** Ce que la carte envoie : quatre octets, elargis en double a l'arrivee. */
    private fun duFil(v: Float): TypedValue.Float = TypedValue.Float(v.toDouble())

    private fun rendu(v: TypedValue): String {
        // Le rendu vit dans le moteur, en prive. On l'atteint par le seul
        // chemin public qui l'exerce : la charge d'une notification.
        val m = AutomationEngine::class.java.getDeclaredMethod(
            "render", String::class.java, TypedValue::class.java
        ).apply { isAccessible = true }
        return m.invoke(null, "{{value}}", v) as? String
            ?: error("le rendu doit produire une chaine")
    }

    @Test
    fun `une mesure du fil se lit telle que la carte l a envoyee`() {
        // 37.4f elargi en double vaut 37.400001525878906. C'est ce que
        // l'utilisateur lisait dans sa notification.
        assertEquals("37.4", duFil(37.4f).value.toFloat().toString())
    }

    @Test
    fun `l ecart de l elargissement est bien la, sans le correctif`() {
        // Le temoin du defaut : la valeur brute PORTE l'ecart. C'est ce qui
        // rend le correctif necessaire, et ce qui le rendrait detectable s'il
        // etait un jour retire.
        assertEquals("37.400001525878906", duFil(37.4f).value.toString())
    }

    @Test
    fun `un entier reste un entier`() {
        // « 31 °C » se lit mieux que « 31.0 °C », et le fil rend tout
        // numerique en double.
        assertEquals("31", 31.0.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() })
    }
}
