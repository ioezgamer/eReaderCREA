/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader.data.model

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.mediatype.MediaType

@Entity(
    tableName = Book.TABLE_NAME,
    indices = [Index(value = [Book.CONTENT_HASH], unique = true)]
)
data class Book(
    @PrimaryKey
    @ColumnInfo(name = ID)
    var id: Long? = null,
    @ColumnInfo(name = Bookmark.CREATION_DATE, defaultValue = "CURRENT_TIMESTAMP")
    val creation: Long? = null,
    @ColumnInfo(name = HREF)
    val href: String,
    @ColumnInfo(name = TITLE)
    val title: String?,
    @ColumnInfo(name = AUTHOR)
    val author: String? = null,
    @ColumnInfo(name = IDENTIFIER)
    val identifier: String,
    @ColumnInfo(name = PROGRESSION)
    val progression: String? = null,
    @ColumnInfo(name = MEDIA_TYPE)
    val rawMediaType: String,
    @ColumnInfo(name = COVER)
    val cover: String,
    @ColumnInfo(name = CONTENT_HASH)
    val contentHash: String,
    @ColumnInfo(name = FILE_SIZE)
    val fileSize: Long,
    @ColumnInfo(name = UPDATED_AT)
    val updatedAt: Long,
    @ColumnInfo(name = PROGRESS_UPDATED_AT)
    val progressUpdatedAt: Long = 0,
    @ColumnInfo(name = REMOTE_FILE_ID)
    val remoteFileId: String? = null,
    @ColumnInfo(name = FAVORITE, defaultValue = "0")
    val favorite: Boolean = false,
    /** MD5 Drive reported for the file this copy came from; a different value in the catalog means an update. */
    @ColumnInfo(name = REMOTE_MD5)
    val remoteMd5: String? = null,
    /**
     * Category path ("Ciencias / Biología"). Books downloaded from Drive follow the folder they are
     * in; books imported on this device get the one the user chooses (null = sin categoría).
     */
    @ColumnInfo(name = CATEGORY)
    val category: String? = null,
) {

    constructor(
        id: Long? = null,
        creation: Long? = null,
        href: String,
        title: String?,
        author: String? = null,
        identifier: String,
        progression: String? = null,
        mediaType: MediaType,
        cover: String,
        contentHash: String,
        fileSize: Long,
        updatedAt: Long,
        progressUpdatedAt: Long = 0,
        remoteFileId: String? = null,
        favorite: Boolean = false,
        remoteMd5: String? = null,
        category: String? = null,
    ) : this(
        id = id,
        creation = creation,
        href = href,
        title = title,
        author = author,
        identifier = identifier,
        progression = progression,
        rawMediaType = mediaType.toString(),
        cover = cover,
        contentHash = contentHash,
        fileSize = fileSize,
        updatedAt = updatedAt,
        progressUpdatedAt = progressUpdatedAt,
        remoteFileId = remoteFileId,
        favorite = favorite,
        remoteMd5 = remoteMd5,
        category = category,
    )

    val url: AbsoluteUrl get() = AbsoluteUrl(href)!!

    val mediaType: MediaType get() =
        MediaType(rawMediaType)!!

    companion object {

        const val TABLE_NAME = "books"
        const val ID = "id"
        const val CREATION_DATE = "creation_date"
        const val HREF = "href"
        const val TITLE = "title"
        const val AUTHOR = "author"
        const val IDENTIFIER = "identifier"
        const val PROGRESSION = "progression"
        const val MEDIA_TYPE = "media_type"
        const val COVER = "cover"
        const val CONTENT_HASH = "content_hash"
        const val FILE_SIZE = "file_size"
        const val UPDATED_AT = "updated_at"
        const val PROGRESS_UPDATED_AT = "progress_updated_at"
        const val REMOTE_FILE_ID = "remote_file_id"
        const val FAVORITE = "favorite"
        const val REMOTE_MD5 = "remote_md5"
        const val CATEGORY = "category"
    }
}

