package com.freeturn.app.service
import com.freeturn.app.domain.proxy.ProxyServiceState

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.freeturn.app.R
import java.util.Locale

/**
 * Нотификации прокси-сервиса: foreground-статус (+скорость/число потоков) и
 * отдельный алерт ручной капчи. Статус и скорость держит внутри, перерисовывает
 * сам. Скорость/счётчик показываются только при "активных" статусах.
 */
class ProxyNotifier(private val service: Service) {

    companion object {
        const val NOTIF_ID_FG = 1
        private const val NOTIF_ID_CAPTCHA = 2
        private const val CHANNEL_PROXY = "ProxyChannel"
        private const val CHANNEL_CAPTCHA = "CaptchaChannel"
    }

    private val openAppIntent: PendingIntent? by lazy {
        service.packageManager.getLaunchIntentForPackage(service.packageName)?.let {
            PendingIntent.getActivity(service, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingRefresh = false
    private var lastRefreshMs = 0L

    private var baseStatus = ""
    private var speedText = ""
    private var captchaActive = false
    private var isActive = false

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = service.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROXY,
                service.getString(R.string.notif_channel_proxy),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CAPTCHA,
                service.getString(R.string.notif_channel_captcha),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    /** Базовый статус "Подключение..." для самого первого startForeground. */
    fun prepareConnecting() {
        baseStatus = service.getString(R.string.notif_proxy_connecting)
        isActive = false
        pendingRefresh = false
        handler.removeCallbacksAndMessages(null)
    }

    /** [active] - соединение установлено (прокси или прокси+WG): показываем потоки и скорость. */
    fun setStatus(text: String, active: Boolean = false) {
        baseStatus = text
        isActive = active
        show()
    }

    fun setSpeed(text: String) {
        speedText = text
        if (isActive) show()
    }

    /** Перерисовать (обновился счётчик потоков) - только если статус активен, с дебаунсом 300мс. */
    fun refreshStats() {
        if (!isActive) return
        val now = SystemClock.elapsedRealtime()
        val diff = now - lastRefreshMs
        if (diff >= 300L) {
            lastRefreshMs = now
            pendingRefresh = false
            show()
        } else if (!pendingRefresh) {
            pendingRefresh = true
            handler.postDelayed({
                if (pendingRefresh && isActive) {
                    lastRefreshMs = SystemClock.elapsedRealtime()
                    pendingRefresh = false
                    show()
                }
            }, 300L - diff)
        }
    }

    fun build(): Notification {
        val stats = ProxyServiceState.connectionStats.value
        // Активное соединение: статус уезжает в заголовок, строка текста целиком под
        // потоки и скорость - вместе они в одну строку не влезают.
        val title: String
        val text: String
        if (isActive) {
            title = baseStatus
            text = listOfNotNull(
                if (stats.total > 0) String.format(
                    Locale.US,
                    service.getString(R.string.notif_proxy_threads_format),
                    stats.active,
                    stats.total
                ) else null,
                speedText.takeIf { it.isNotEmpty() }
            ).joinToString(" • ")
        } else {
            title = service.getString(R.string.notif_proxy_title)
            text = baseStatus
        }

        val stopIntent = Intent(service, ProxyReceiver::class.java).apply {
            action = ProxyActions.STOP
        }
        val stopPending = PendingIntent.getBroadcast(
            service, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(service, CHANNEL_PROXY)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, service.getString(R.string.notif_proxy_stop_action), stopPending)
            .build()
    }

    private fun show() {
        try {
            NotificationManagerCompat.from(service).notify(NOTIF_ID_FG, build())
        } catch (_: Exception) {}
    }

    /** Показ алерта капчи. Дедуп: пока предыдущий не закрыт - повторно не шумим. */
    fun showCaptcha() {
        if (captchaActive) return
        captchaActive = true
        val notification = NotificationCompat.Builder(service, CHANNEL_CAPTCHA)
            .setContentTitle(service.getString(R.string.notif_captcha_title))
            .setContentText(service.getString(R.string.notif_captcha_text))
            .setSmallIcon(R.drawable.ic_notification_captcha)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .build()
        try {
            NotificationManagerCompat.from(service).notify(NOTIF_ID_CAPTCHA, notification)
        } catch (_: Exception) {
            // POST_NOTIFICATIONS отозван на API 33+ или RemoteServiceException - молча игнорируем, диалог в UI
            // всё равно откроется через captchaSession StateFlow.
        }
    }

    fun cancelCaptcha() {
        try {
            NotificationManagerCompat.from(service).cancel(NOTIF_ID_CAPTCHA)
        } catch (_: Exception) {}
        captchaActive = false
    }
}
