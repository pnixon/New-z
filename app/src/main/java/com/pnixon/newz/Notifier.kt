package com.pnixon.newz

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pnixon.newz.ui.MainActivity
import newz.core.RankItem
import newz.core.Topic
import newz.core.Topics

/** Tells the user about new articles in the topics they asked to be notified about. */
object Notifier {
    private const val CHANNEL = "new_articles"

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "New articles in your topics", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    /** One notification per topic, listing the new headlines. [items] are articles first seen in this check. */
    @SuppressLint("MissingPermission")
    fun notifyNew(context: Context, items: List<RankItem>, topics: List<Topic>) {
        val wanted = topics.filter { it.notify && it.name.isNotBlank() }
        if (wanted.isEmpty() || items.isEmpty()) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(context)

        val matcher = Topics.Matcher(wanted)
        val byTopic = LinkedHashMap<String, MutableList<RankItem>>()
        for (item in items) for (match in matcher.match(item)) byTopic.getOrPut(match.topic) { ArrayList() }.add(item)

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        for ((topic, found) in byTopic) {
            val style = NotificationCompat.InboxStyle()
            found.take(6).forEach { style.addLine("${it.sourceName}: ${it.title}") }
            if (found.size > 6) style.setSummaryText("and ${found.size - 6} more")
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_news)
                .setContentTitle(if (found.size == 1) "New in $topic" else "${found.size} new in $topic")
                .setContentText(found.first().title)
                .setStyle(style)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { manager.notify(topic.hashCode(), notification) }
        }
    }
}
