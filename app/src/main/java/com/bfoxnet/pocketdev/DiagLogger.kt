package com.bfoxnet.pocketdev

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Centralized diagnostics collector that captures `[DIAG]`, `[PRoot]`, `[LSP]`, `[Build]`,
 * and `[Git]` events both in-memory and persisted on disk (`$filesDir/diag_history.log`),
 * surfacing them in `DiagActivity` instead of only inside the terminal scrollback.
 */
object DiagLogger {
    private const val TAG = "PocketDevDiag"
    private const val MAX_MEMORY_ENTRIES = 250
    private const val LOG_FILE_NAME = "diag_history.log"

    private val entries = ArrayDeque<String>(MAX_MEMORY_ENTRIES)
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(category: String, message: String, context: Context? = null, throwable: Throwable? = null) {
        val cleanMsg = message.replace(Regex("\\u001b\\[[0-9;]*m"), "").trim()
        if (cleanMsg.isEmpty()) return
        val timestamp = synchronized(dateFormat) { dateFormat.format(Date()) }
        val errSuffix = throwable?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        val formatted = "[$timestamp] [$category] $cleanMsg$errSuffix"

        Log.d(TAG, formatted, throwable)

        synchronized(entries) {
            if (entries.size >= MAX_MEMORY_ENTRIES) {
                entries.removeFirst()
            }
            entries.addLast(formatted)
        }

        if (context != null) {
            runCatching {
                val f = File(context.filesDir, LOG_FILE_NAME)
                if (f.exists() && f.length() > 128 * 1024) {
                    val tail = f.readLines().takeLast(120)
                    f.writeText(tail.joinToString("\n") + "\n", Charsets.UTF_8)
                }
                f.appendText(formatted + "\n", Charsets.UTF_8)
            }
        }
    }

    /**
     * Inspects progress lines from terminal/PRoot/LSP bootstrap streams and routes `[DIAG]`,
     * error, or verification lines into the persistent Diagnostics log.
     */
    fun captureProgressIfDiagnostic(context: Context?, source: String, rawMessage: String) {
        val clean = rawMessage.replace(Regex("\\u001b\\[[0-9;]*m"), "").trim()
        if (clean.isEmpty()) return
        val shouldRecord = clean.contains("[DIAG]", ignoreCase = true) ||
            clean.contains("error", ignoreCase = true) ||
            clean.contains("failed", ignoreCase = true) ||
            clean.contains("warning", ignoreCase = true) ||
            clean.startsWith("==>") ||
            clean.startsWith("✓") ||
            clean.startsWith("✗")
        if (shouldRecord) {
            log(source, clean, context)
        }
    }

    fun getRecentEntries(context: Context? = null, limit: Int = 100): List<String> {
        val memSnapshot = synchronized(entries) { entries.toList() }
        if (memSnapshot.isNotEmpty()) {
            return memSnapshot.takeLast(limit)
        }
        if (context != null) {
            val f = File(context.filesDir, LOG_FILE_NAME)
            if (f.exists()) {
                return runCatching { f.readLines().takeLast(limit) }.getOrDefault(emptyList())
            }
        }
        return emptyList()
    }

    fun clear(context: Context? = null) {
        synchronized(entries) { entries.clear() }
        if (context != null) {
            runCatching { File(context.filesDir, LOG_FILE_NAME).delete() }
        }
    }
}

/**
 * System Notification Shade progress helper for terminal/PRoot rootfs downloads,
 * LSP server installations, and environment backup/restore operations.
 */
object DownloadNotificationHelper {
    private const val CHANNEL_ID = "pocketdev_downloads"
    private const val CHANNEL_NAME = "PocketDev Downloads & Setup"

    const val NOTIF_ID_BOOTSTRAP = 4101
    const val NOTIF_ID_LSP = 4102
    const val NOTIF_ID_BACKUP = 4103

    private fun ensureChannel(context: Context): NotificationManager {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows progress while Ubuntu rootfs, LSP servers, or environment backups run"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
        return nm
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (!Settings(context).downloadNotificationsEnabled) return false
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    fun showProgress(
        context: Context,
        notificationId: Int,
        title: String,
        subtitle: String,
        progress: Int = -1,
        indeterminate: Boolean = progress < 0
    ) {
        runCatching {
            if (!canPostNotifications(context)) return
            val nm = ensureChannel(context)
            val cleanSub = subtitle.replace(Regex("\\u001b\\[[0-9;]*m"), "").trim()
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context,
                notificationId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_terminal)
                .setContentTitle(title)
                .setContentText(cleanSub.ifEmpty { "Working..." })
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(100, progress.coerceIn(0, 100), indeterminate)
            nm.notify(notificationId, builder.build())
        }
    }

    fun complete(
        context: Context,
        notificationId: Int,
        title: String,
        message: String,
        success: Boolean = true
    ) {
        runCatching {
            if (!canPostNotifications(context)) return
            val nm = ensureChannel(context)
            val cleanMsg = message.replace(Regex("\\u001b\\[[0-9;]*m"), "").trim()
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(if (success) R.drawable.ic_lsp else R.drawable.ic_close)
                .setContentTitle(title)
                .setContentText(cleanMsg)
                .setOngoing(false)
                .setAutoCancel(true)
                .setProgress(0, 0, false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            nm.notify(notificationId, builder.build())
        }
    }
}
