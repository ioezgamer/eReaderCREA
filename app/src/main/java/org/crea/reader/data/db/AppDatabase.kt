/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader.data.db

import android.content.Context
import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import org.crea.reader.data.model.*
import org.crea.reader.data.model.Book
import org.crea.reader.data.model.Bookmark
import org.crea.reader.data.model.Catalog
import org.crea.reader.data.model.DriveBook
import org.crea.reader.data.model.Highlight
import org.crea.reader.data.model.SyncTombstone

@Database(
    entities = [
        Book::class, Bookmark::class, Highlight::class, Catalog::class, SyncTombstone::class, DriveBook::class,
        HighlightStash::class, BookCollection::class, CollectionBook::class, BookInsight::class,
    ],
    version = 5,
    exportSchema = false
)
@ColumnTypeConverters(
    HighlightConverters::class
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun booksDao(): BooksDao

    abstract fun catalogDao(): CatalogDao

    abstract fun driveCatalogDao(): DriveCatalogDao

    abstract fun collectionsDao(): CollectionsDao

    abstract fun insightsDao(): InsightsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** Adds the online catalog table; the books already downloaded are left untouched. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `drive_catalog` (`drive_file_id` TEXT NOT NULL, " +
                        "`file_name` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT, " +
                        "`format` TEXT NOT NULL, `size` INTEGER NOT NULL, `md5` TEXT, " +
                        "`modified_at` INTEGER NOT NULL, `category` TEXT, `content_hash` TEXT, " +
                        "`cover_path` TEXT, `cover_version` TEXT, `progression` TEXT, " +
                        "`progression_at` INTEGER NOT NULL, `reader_id` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`drive_file_id`))"
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_drive_catalog_reader_id` " +
                        "ON `drive_catalog` (`reader_id`)"
                )
            }
        }

        /** Adds the favourite flag to the books already downloaded. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `books` ADD COLUMN `favorite` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Update tracking, notes kept across deleting a book, and the user's collections. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `books` ADD COLUMN `remote_md5` TEXT")
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `highlight_stash` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`content_hash` TEXT NOT NULL, `creation_date` INTEGER, `style` TEXT NOT NULL, " +
                        "`tint` INTEGER NOT NULL, `href` TEXT NOT NULL, `type` TEXT NOT NULL, `title` TEXT, " +
                        "`total_progression` REAL NOT NULL, `locations` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                        "`annotation` TEXT NOT NULL)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_highlight_stash_content_hash` ON `highlight_stash` (`content_hash`)"
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `collections` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `created_at` INTEGER NOT NULL)"
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `collection_books` (`collection_id` INTEGER NOT NULL, " +
                        "`content_hash` TEXT NOT NULL, PRIMARY KEY(`collection_id`, `content_hash`), " +
                        "FOREIGN KEY(`collection_id`) REFERENCES `collections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_collection_books_content_hash` ON `collection_books` (`content_hash`)"
                )
            }
        }

        /** AI-generated book information for the online catalog. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `book_insights` (`drive_file_id` TEXT NOT NULL, `version` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `author` TEXT, `summary` TEXT NOT NULL, `categories` TEXT NOT NULL, " +
                        "`level` TEXT NOT NULL, `confidence` TEXT NOT NULL, PRIMARY KEY(`drive_file_id`))"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            val tempInstance = INSTANCE
            if (tempInstance != null) {
                return tempInstance
            }
            synchronized(this) {
                val instance = Room.databaseBuilder<AppDatabase>(
                    context.applicationContext,
                    "database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                INSTANCE = instance
                return instance
            }
        }
    }
}
