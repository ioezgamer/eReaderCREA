package org.crea.reader.online

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.crea.reader.R

/** The notice on who owns the catalog's books, and where a rights holder asks for a removal. */
object CatalogRights {

    const val CONTACT = "tecnologia@creanicaragua.org"

    fun show(context: Context) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.catalog_rights_title)
            .setMessage(context.getString(R.string.catalog_rights_body, CONTACT))
            .setPositiveButton(R.string.catalog_rights_ok, null)
            .show()
    }
}
