package dev.knsql.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.knsql.i18n.tr

/** Foreground service that keeps the process, and so the open JDBC sessions, alive in the background. */
class SessionKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            AppRuntime.workbenchOrNull?.closeAll()
            stopSelfNow()
            return START_NOT_STICKY
        }
        val names = intent?.getStringArrayListExtra(EXTRA_NAMES).orEmpty()
        if (names.isEmpty()) {
            stopSelfNow()
            return START_NOT_STICKY
        }
        val notification = buildNotification(names)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun stopSelfNow() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(names: List<String>): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        // Re-creating an existing channel only updates its name and description, so this also follows
        // language changes.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, tr("数据库连接保活"), NotificationManager.IMPORTANCE_LOW).apply {
                description = tr("有打开的数据库连接时常驻，防止系统在后台断开它们")
                setShowBadge(false)
            },
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val disconnect = PendingIntent.getService(
            this,
            1,
            Intent(this, SessionKeepAliveService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val icon = resources.getIdentifier("ic_stat_knsql", "drawable", packageName)
            .takeIf { it != 0 } ?: applicationInfo.icon
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(tr("正在保持 {0} 个数据库连接", names.size))
            .setContentText(names.joinToString(tr("、")))
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, tr("全部断开"), disconnect).build())
            .build()
    }

    companion object {
        const val EXTRA_NAMES = "names"
        private const val ACTION_DISCONNECT = "dev.knsql.action.DISCONNECT_ALL"
        private const val CHANNEL_ID = "sessions"
        private const val NOTIFICATION_ID = 1001
    }
}
