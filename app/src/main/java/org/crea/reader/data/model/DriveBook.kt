package org.crea.reader.data.model

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * One book of the online catalog: metadata of a file in the Drive folder, kept on the device so
 * the catalog can be browsed offline. It never holds the book itself; a downloaded copy lives in
 * [Book] and is linked through [Book.remoteFileId].
 */
@Entity(
    tableName = DriveBook.TABLE_NAME,
    indices = [Index(value = [DriveBook.READER_ID], unique = true)]
)
data class DriveBook(
    @PrimaryKey
    @ColumnInfo(name = DRIVE_FILE_ID)
    val driveFileId: String,
    @ColumnInfo(name = FILE_NAME)
    val fileName: String,
    @ColumnInfo(name = TITLE)
    val title: String,
    @ColumnInfo(name = AUTHOR)
    val author: String? = null,
    /** "EPUB" or "PDF". */
    @ColumnInfo(name = FORMAT)
    val format: String,
    @ColumnInfo(name = SIZE)
    val size: Long,
    @ColumnInfo(name = MD5)
    val md5: String? = null,
    @ColumnInfo(name = MODIFIED_AT)
    val modifiedAt: Long,
    /** Name of the sub-folder (first level) the file lives in, when it is not in the root folder. */
    @ColumnInfo(name = CATEGORY)
    val category: String? = null,
    /** SHA-256 of the content when the file was uploaded by CREA Reader; links old local copies. */
    @ColumnInfo(name = CONTENT_HASH)
    val contentHash: String? = null,
    /** Cover thumbnail kept in app storage, if Drive generated one. */
    @ColumnInfo(name = COVER_PATH)
    val coverPath: String? = null,
    /** Version (md5 or modified time) the cover was fetched for, to refresh it when the file changes. */
    @ColumnInfo(name = COVER_VERSION)
    val coverVersion: String? = null,
    /** Reading position of the online (not downloaded) copy, as a Readium locator JSON. */
    @ColumnInfo(name = PROGRESSION)
    val progression: String? = null,
    @ColumnInfo(name = PROGRESSION_AT)
    val progressionAt: Long,
    /** Negative id the reader uses for this book while it has no local copy. See [OnlineBookIds]. */
    @ColumnInfo(name = READER_ID)
    val readerId: Long,
) {
    /** Changes whenever the Drive file is replaced, which invalidates covers and cached copies. */
    val version: String get() = md5 ?: modifiedAt.toString()

    companion object {
        const val TABLE_NAME = "drive_catalog"
        const val DRIVE_FILE_ID = "drive_file_id"
        const val FILE_NAME = "file_name"
        const val TITLE = "title"
        const val AUTHOR = "author"
        const val FORMAT = "format"
        const val SIZE = "size"
        const val MD5 = "md5"
        const val MODIFIED_AT = "modified_at"
        const val CATEGORY = "category"
        const val CONTENT_HASH = "content_hash"
        const val COVER_PATH = "cover_path"
        const val COVER_VERSION = "cover_version"
        const val PROGRESSION = "progression"
        const val PROGRESSION_AT = "progression_at"
        const val READER_ID = "reader_id"
    }
}
