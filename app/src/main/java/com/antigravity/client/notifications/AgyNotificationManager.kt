package com.antigravity.client.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.antigravity.client.AntigravityApp
import com.antigravity.client.MainActivity

class AgyNotificationManager(private val context: Context) {

    private val channelId = "agy_runs_channel"
    private val channelName = "Agent Runs"

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for completed and failed Antigravity agent runs"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun showRunNotification(type: String, conversationId: String, runId: String) {
        val title = when (type) {
            "run_finished" -> "Task Completed"
            "run_error" -> "Task Failed"
            else -> "Agent Update"
        }
        val contentText = when (type) {
            "run_finished" -> "Antigravity agent finished turn"
            "run_error" -> "An error occurred during agent turn"
            else -> "New events received"
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("chatId", conversationId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(contentText)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(conversationId.hashCode(), notification)

        // Wakeup / trigger sync replay
        try {
            AntigravityApp.instance.syncEngine.reconnect()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
