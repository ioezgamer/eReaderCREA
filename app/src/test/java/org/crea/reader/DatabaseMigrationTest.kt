package org.crea.reader

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.crea.reader.data.db.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Runs the Room migrations on a real SQLite database built from the exported schemas
 * (app/schemas), so an update never loses the books, notes or progress of a tablet.
 */
class DatabaseMigrationTest {

    private lateinit var dbFile: File
    private lateinit var connection: SQLiteConnection

    @Before
    fun open() {
        dbFile = File.createTempFile("crea-migration", ".db")
        connection = BundledSQLiteDriver().open(dbFile.path)
    }

    @After
    fun close() {
        connection.close()
        dbFile.delete()
    }

    @Test
    fun from5To6KeepsEverythingAndCategorisesDriveBooks() = runBlocking {
        create(schema(5))
        connection.execSQL(
            "INSERT INTO books (id, href, title, author, identifier, media_type, cover, content_hash, file_size, " +
                "updated_at, progress_updated_at, remote_file_id, favorite, progression) VALUES " +
                "(1, 'file:///a.epub', 'Plantas', 'Ana', 'id-a', 'application/epub+zip', '', 'hash-a', 10, 1, 5, 'drive-a', 1, '{\"p\":1}'), " +
                "(2, 'file:///b.pdf', 'Mío', NULL, '', 'application/pdf', '', 'hash-b', 20, 1, 0, NULL, 0, '{}')"
        )
        connection.execSQL(
            "INSERT INTO drive_catalog (drive_file_id, file_name, title, format, size, modified_at, category, " +
                "progression_at, reader_id) VALUES ('drive-a', 'Plantas.epub', 'Plantas', 'EPUB', 10, 1, 'Ciencias / Biología', 0, -1)"
        )
        connection.execSQL(
            "INSERT INTO highlights (BOOK_ID, STYLE, TINT, HREF, TYPE, TOTAL_PROGRESSION, LOCATIONS, TEXT, ANNOTATION) " +
                "VALUES (1, 'highlight', 0, 'c1.xhtml', 'application/xhtml+xml', 0.5, '{}', '{}', 'nota')"
        )

        AppDatabase.MIGRATION_5_6.migrate(connection)

        assertEquals(columnsOf(schema(6)), actualColumns())
        assertEquals("Ciencias / Biología", text("SELECT category FROM books WHERE id = 1"))
        assertNull(text("SELECT category FROM books WHERE id = 2"))
        assertEquals("2", text("SELECT COUNT(*) FROM books"))
        assertEquals("1", text("SELECT favorite FROM books WHERE id = 1"))
        assertEquals("{\"p\":1}", text("SELECT progression FROM books WHERE id = 1"))
        assertEquals("nota", text("SELECT ANNOTATION FROM highlights WHERE BOOK_ID = 1"))
    }

    @Test
    fun everyMigrationHasAStep() {
        val steps = AppDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }
        val latest = schema(6)["database"]!!.jsonObject["version"]!!.jsonPrimitive.content.toInt()
        assertEquals((1 until latest).map { it to it + 1 }, steps)
    }

    // region helpers

    private fun schema(version: Int): JsonObject =
        Json.parseToJsonElement(File("schemas/org.crea.reader.data.db.AppDatabase/$version.json").readText()).jsonObject

    private fun entities(schema: JsonObject) = schema["database"]!!.jsonObject["entities"]!!.jsonArray.map { it.jsonObject }

    private fun create(schema: JsonObject) {
        for (entity in entities(schema)) {
            val table = entity["tableName"]!!.jsonPrimitive.content
            connection.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            entity["indices"]?.jsonArray?.forEach { index ->
                connection.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
        }
    }

    private fun columnsOf(schema: JsonObject): Map<String, Set<String>> =
        entities(schema).associate { entity ->
            entity["tableName"]!!.jsonPrimitive.content to
                entity["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }.toSet()
        }

    private fun actualColumns(): Map<String, Set<String>> {
        val tables = mutableListOf<String>()
        connection.prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").use { st ->
            while (st.step()) tables += st.getText(0)
        }
        return tables.associateWith { table ->
            val columns = mutableSetOf<String>()
            connection.prepare("PRAGMA table_info(`$table`)").use { st -> while (st.step()) columns += st.getText(1) }
            columns
        }
    }

    private fun text(sql: String): String? =
        connection.prepare(sql).use { st -> if (st.step() && !st.isNull(0)) st.getText(0) else null }

    // endregion
}
