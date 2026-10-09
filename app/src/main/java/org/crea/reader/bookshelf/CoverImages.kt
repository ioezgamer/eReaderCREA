package org.crea.reader.bookshelf

import com.squareup.picasso.Picasso
import com.squareup.picasso.RequestCreator
import java.io.File

/**
 * One request shape for every cover (cards, "Continuar leyendo", warm-up). Using a fixed size
 * instead of `fit()` means Picasso does not wait for the view to be measured and reuses the same
 * cached bitmap everywhere, so covers show up in the first frame when coming back to a tab.
 */
object CoverImages {
    private const val WIDTH = 360
    private const val HEIGHT = 540

    fun request(coverPath: String): RequestCreator =
        Picasso.get()
            .load(File(coverPath))
            .resize(WIDTH, HEIGHT)
            .onlyScaleDown()
            .centerCrop()
            .noFade()

    /** Decodes the first covers in the background so the library opens already painted. */
    fun warmUp(coverPaths: List<String>) {
        coverPaths.take(WARM_UP_COUNT).forEach { request(it).fetch() }
    }

    private const val WARM_UP_COUNT = 30
}
