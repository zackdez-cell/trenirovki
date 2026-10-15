package app.weekplan

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

object AlarmScheduler {

    const val ACTION_FIRE = "app.weekplan.FIRE"
    const val ACTION_DONE = "app.weekplan.DONE"
    const val EXTRA_ID = "id"

    fun nextTrigger(task: Task, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
        var t = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(task.day)))
            .withHour(task.hour).withMinute(task.minute).withSecond(0).withNano(0)
        if (!t.isAfter(now)) t = t.plusWeeks(1)
        return t
    }

    private fun firePending(ctx: Context, id: Int): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(ctx: Context, task: Task) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val at = nextTrigger(task).toInstant().toEpochMilli()
        val pi = firePending(ctx, task.id)
        val exactAllowed = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exactAllowed) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun scheduleAll(ctx: Context) {
        Storage.tasks(ctx).forEach { schedule(ctx, it) }
    }

    fun cancel(ctx: Context, tasks: List<Task>) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        tasks.forEach { am.cancel(firePending(ctx, it.id)) }
    }
}

object Notifs {
    private const val CHANNEL = "workouts"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Напоминания о тренировках", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Уведомления по времени из плана"
            ch.enableVibration(true)
            nm.createNotificationChannel(ch)
        }
    }

    fun show(ctx: Context, notifId: Int, title: String, text: String, doneForTaskId: Int?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(ctx)

        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)

        if (doneForTaskId != null) {
            val doneIntent = Intent(ctx, ReminderReceiver::class.java)
                .setAction(AlarmScheduler.ACTION_DONE)
                .putExtra(AlarmScheduler.EXTRA_ID, doneForTaskId)
            val donePi = PendingIntent.getBroadcast(
                ctx, 100_000 + doneForTaskId, doneIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(Notification.Action.Builder(null, "✓ Готово", donePi).build())
        }

        ctx.getSystemService(NotificationManager::class.java)?.notify(notifId, b.build())
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, -1)
        if (id < 0) return
        when (intent.action) {
            AlarmScheduler.ACTION_FIRE -> {
                val task = Storage.task(ctx, id) ?: return
                // Сразу ставим это же упражнение на следующую неделю
                AlarmScheduler.schedule(ctx, task)
                if (id in Storage.done(ctx)) return
                Notifs.show(ctx, id, "${task.time} — пора!", task.text, id)
            }
            AlarmScheduler.ACTION_DONE -> {
                Storage.setDone(ctx, id, true)
                ctx.getSystemService(NotificationManager::class.java)?.cancel(id)
            }
        }
    }
}

/** После перезагрузки, обновления приложения или смены времени заново ставит напоминания. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        AlarmScheduler.scheduleAll(ctx)
    }
}
