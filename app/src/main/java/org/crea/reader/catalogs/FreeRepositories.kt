package org.crea.reader.catalogs

/**
 * Directory of free EPUB repositories offered in Explorar's "Agregar repositorios" sheet.
 *
 * A repository with a [Repo.feedHref] is an OPDS catalog: adding it puts it in the user's Explorar list so its
 * books can be browsed, read and downloaded inside the app. One without a feed is a website: it opens in the
 * browser, and the EPUB the user downloads there can be imported into the library. Feeds were checked to return
 * OPDS; Standard Ebooks is web-only because its OPDS feed needs a membership.
 *
 * Only sources that publish public-domain or openly licensed books are listed.
 */
object FreeRepositories {

    enum class Group(val title: String) {
        SPANISH("En español"),
        WORLD("Clásicos y literatura universal"),
        KIDS("Infantil y educativo"),
        ACADEMIC("Académico y científico"),
    }

    data class Repo(
        val name: String,
        val description: String,
        val group: Group,
        val webUrl: String,
        /** OPDS catalog to add inside the app, or null when the source can only be visited on the web. */
        val feedHref: String? = null,
    )

    private val gutenbergSpanish = DefaultCatalogs.all.first { it.source == DefaultCatalogs.Source.GUTENBERG }.href

    val all: List<Repo> = listOf(
        Repo("textos.info", "Biblioteca virtual abierta de obras clásicas en español.", Group.SPANISH, "https://www.textos.info", "https://www.textos.info/populares.atom"),
        Repo("Project Gutenberg · español", "Clásicos en español de dominio público.", Group.SPANISH, "https://www.gutenberg.org/browse/languages/es", gutenbergSpanish),
        Repo("Elejandría", "Más de 2.300 libros gratis en EPUB, sin registro.", Group.SPANISH, "https://www.elejandria.com"),
        Repo("Biblioteca Virtual Miguel de Cervantes", "Literatura española e hispanoamericana, con colección EPUB.", Group.SPANISH, "https://www.cervantesvirtual.com"),
        Repo("Wikisource en español", "Libros clásicos y documentos históricos exportables a EPUB.", Group.SPANISH, "https://es.wikisource.org"),
        Repo("Libroteca", "Literatura universal, novelas y poesía.", Group.SPANISH, "https://libroteca.net"),
        Repo("Freeditorial", "Novelas, cuentos y ensayos de autores independientes.", Group.SPANISH, "https://www.freeditorial.com"),
        Repo("Biblioteca Nacional de España", "Obras patrimoniales digitalizadas.", Group.SPANISH, "https://www.bne.es"),

        Repo("Project Gutenberg · todos los idiomas", "Una de las mayores bibliotecas de libros electrónicos gratuitos.", Group.WORLD, "https://www.gutenberg.org", "https://www.gutenberg.org/ebooks.opds/"),
        Repo("Unglue.it", "Libros abiertos con licencias Creative Commons, sobre todo en inglés.", Group.WORLD, "https://unglue.it", "https://unglue.it/api/opds/epub/"),
        Repo("Standard Ebooks", "Clásicos cuidadosamente editados, con EPUB de gran calidad.", Group.WORLD, "https://standardebooks.org"),
        Repo("Global Grey Ebooks", "Literatura clásica, filosofía, historia y libros antiguos.", Group.WORLD, "https://www.globalgreyebooks.com"),
        Repo("epubBooks", "EPUB de clásicos, principalmente en inglés.", Group.WORLD, "https://www.epubbooks.com"),
        Repo("Loyal Books", "Libros clásicos acompañados de audiolibros.", Group.WORLD, "https://www.loyalbooks.com"),
        Repo("Faded Page", "Obras de dominio público según la ley canadiense.", Group.WORLD, "https://fadedpage.com"),
        Repo("Planet eBook", "Novelas clásicas en EPUB y PDF.", Group.WORLD, "https://planetebook.com"),
        Repo("Open Library", "Clásicos y préstamos digitales (algunos con límite de tiempo).", Group.WORLD, "https://openlibrary.org"),

        Repo("StoryWeaver", "Cuentos ilustrados para niños en muchos idiomas, con descarga EPUB.", Group.KIDS, "https://storyweaver.org.in"),
        Repo("Book Dash", "Libros infantiles ilustrados con licencia abierta CC BY 4.0.", Group.KIDS, "https://bookdash.org"),

        Repo("SciELO Libros", "Investigación científica, ciencias sociales, educación y salud.", Group.ACADEMIC, "https://books.scielo.org"),
        Repo("Big Ten Open Books", "Libros de editoriales universitarias, EPUB3 sin DRM.", Group.ACADEMIC, "https://bigtenopenbooks.org"),
        Repo("OAPEN Library", "Libros de investigación académica de acceso abierto.", Group.ACADEMIC, "https://oapen.org"),
        Repo("Directory of Open Access Books", "Directorio internacional de libros académicos abiertos.", Group.ACADEMIC, "https://www.doabooks.org"),
    )
}
