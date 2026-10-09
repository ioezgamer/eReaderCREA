package org.crea.reader.catalogs

import org.crea.reader.R

/**
 * Free Spanish-language OPDS catalogs offered out of the box in Explorar.
 *
 * Every feed was checked to return EPUB acquisition links. Gutenberg feeds use its search syntax:
 * `l.es` = Spanish, `s.` = subject, `a.` = author.
 */
object DefaultCatalogs {
    /** Bump when [all] changes so installed apps refresh their list once. */
    const val VERSION = 3

    enum class Source(val subtitleRes: Int) {
        TEXTOS(R.string.catalog_sub_textos),
        GUTENBERG(R.string.catalog_sub_gutenberg),
    }

    data class Entry(
        val title: String,
        val description: String,
        val href: String,
        val source: Source,
        val type: Int = 1,
        val person: Boolean = false,
    )

    private const val TEXTOS = "https://www.textos.info"
    private const val GUTENBERG = "https://www.gutenberg.org/ebooks/search.opds/?query=l.es"

    private fun gutenberg(filter: String? = null, sort: String = "downloads"): String =
        GUTENBERG + (filter?.let { "+$it" } ?: "") + "&sort_order=$sort"

    val all: List<Entry> = listOf(
        Entry("Más populares", "Lo más leído esta semana", "$TEXTOS/populares.atom", Source.TEXTOS),
        Entry("Novedades", "Los últimos libros añadidos", "$TEXTOS/novedades.atom", Source.TEXTOS),
        Entry("Autores", "Explora por escritor", "$TEXTOS/autores.atom", Source.TEXTOS, person = true),
        Entry("Temas y etiquetas", "Explora por tema", "$TEXTOS/etiquetas.atom", Source.TEXTOS),
        Entry("Los más descargados", "Clásicos en español", gutenberg(), Source.GUTENBERG),
        Entry("Recién agregados", "Novedades de la biblioteca", gutenberg(sort = "release_date"), Source.GUTENBERG),
        Entry("Novelas y cuentos", "Ficción clásica", gutenberg("s.fiction"), Source.GUTENBERG),
        Entry("Poesía", "Versos de todas las épocas", gutenberg("s.poetry"), Source.GUTENBERG),
        Entry("Teatro", "Obras dramáticas", gutenberg("s.drama"), Source.GUTENBERG),
        Entry("Aventuras", "Viajes y peripecias", gutenberg("s.adventure"), Source.GUTENBERG),
        Entry("Historia", "Hechos y crónicas", gutenberg("s.history"), Source.GUTENBERG),
        Entry("Filosofía y pensamiento", "Ensayo y reflexión", gutenberg("s.philosophy"), Source.GUTENBERG),
        Entry("Viajes y crónicas", "Relatos de viajeros", gutenberg("s.travel"), Source.GUTENBERG),
        Entry("Infantil y juvenil", "Para jóvenes lectores", gutenberg("s.children"), Source.GUTENBERG),
        Entry("Miguel de Cervantes", "Don Quijote y novelas ejemplares", gutenberg("cervantes"), Source.GUTENBERG, person = true),
        Entry("Rubén Darío", "Poesía y prosa del príncipe de las letras castellanas", gutenberg("a.dar%C3%ADo"), Source.GUTENBERG, person = true),
        Entry("Benito Pérez Galdós", "Episodios y novelas españolas", gutenberg("a.gald%C3%B3s"), Source.GUTENBERG, person = true),
    )

    /** English-only sample catalogs shipped in earlier versions. */
    val retiredHrefs = listOf(
        "https://test.opds.io/2.0/home.json",
        "http://open.minitex.org/textbooks/",
    )

    private val byHref = all.associateBy { it.href }

    fun find(href: String): Entry? = byHref[href]
}
