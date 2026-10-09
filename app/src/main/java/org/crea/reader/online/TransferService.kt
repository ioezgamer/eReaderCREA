package org.crea.reader.online

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.crea.reader.Application
import org.crea.reader.MainActivity
import org.crea.reader.R
import timber.log.Timber

/**
 * Keeps the process alive and shows progress while books are being downloaded, so a download does
 * not die when the app goes to the background. The transfers themselves run in [OnlineBookManager];
 * this service only watches them, and stops as soon as none is left.
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as Application

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(emptyMap()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        if (intent?.action == ACTION_CANCEL_ALL) app.onlineBooks.cancelAll()

        scope.launch {
            val notifications = getSystemService(NotificationManager::class.java)
            app.onlineBooks.transfers.collect { transfers ->
                val active = transfers.filterValues { it.active }
                if (active.isEmpty()) {
                    ServiceCompat.stopForeground(this@TransferService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    notifications.notify(NOTIFICATION_ID, notification(active))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(active: Map<String, OnlineBookManager.Transfer>): Notification {
        val count = active.size
        val percent = if (count == 0) 0 else active.values.sumOf { it.percent } / count
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, TransferService::class.java).setAction(ACTION_CANCEL_ALL),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(resources.getQuantityString(R.plurals.transfer_notification_title, count.coerceAtLeast(1), count))
            .setContentText(getString(R.string.transfer_notification_progress, percent))
            .setProgress(100, percent, count == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.cancel), cancel)
            .build()
    }

    private fun createChannel() {
        // Channels exist from Android 8; older versions show the notification without one.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.transfer_channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "book-transfers"
        private const val NOTIFICATION_ID = 4001
        private const val ACTION_CANCEL_ALL = "org.crea.reader.CANCEL_TRANSFERS"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
            } catch (error: Exception) {
                // Starting a service from the background can be refused; the transfer still runs.
                Timber.w(error, "Could not start the transfer service")
            }
        }
    }
}
