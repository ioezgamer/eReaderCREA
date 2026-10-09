package org.crea.reader.online

import androidx.annotation.StringRes
import org.crea.reader.R

object OnlineMessages {
    @StringRes
    fun busy(purpose: OnlineBookManager.Purpose): Int = when (purpose) {
        OnlineBookManager.Purpose.READ -> R.string.catalog_preparing_percent
        OnlineBookManager.Purpose.UPDATE -> R.string.catalog_updating_percent
        OnlineBookManager.Purpose.DOWNLOAD -> R.string.catalog_downloading_percent
    }

    @StringRes
    fun failure(failure: OnlineBookManager.Failure): Int = when (failure) {
        OnlineBookManager.Failure.NO_CONNECTION -> R.string.transfer_error_no_connection
        OnlineBookManager.Failure.NO_SPACE -> R.string.transfer_error_no_space
        OnlineBookManager.Failure.SIGN_IN_NEEDED -> R.string.transfer_error_sign_in
        OnlineBookManager.Failure.UNAVAILABLE -> R.string.transfer_error_unavailable
        OnlineBookManager.Failure.CORRUPT -> R.string.transfer_error_corrupt
        OnlineBookManager.Failure.UNKNOWN -> R.string.transfer_error_unknown
    }
}
