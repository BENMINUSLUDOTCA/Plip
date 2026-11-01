package ca.benlu.plip

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.min

/** Everything that changes drinks or reminders goes through here, so the alarm always matches the data. */
object Plip {
    const val CHANNEL = "reminders"
    const val ACTION_REMIND = "ca.benlu.plip.REMIND"
    const val ACTION_DRINK = "ca.benlu.plip.DRINK"
    const val ACTION_SNOOZE = "ca.benlu.plip.SNOOZE"
    /** Shows a reminder without touching the schedule: `adb shell am broadcast -n ca.benlu.plip/.PlipReceiver -a ca.benlu.plip.TEST` */
    const val ACTION_TEST = "ca.benlu.plip.TEST"
    const val ACTION_NEW_DAY = "ca.benlu.plip.NEW_DAY"
    const val ACTION_COUNTDOWN = "ca.benlu.plip.COUNTDOWN"
    const val ACTION_STREAK = "ca.benlu.plip.STREAK"
    /** Shows the streak warning regardless of time and streak: `adb shell am broadcast -n ca.benlu.plip/.PlipReceiver -a ca.benlu.plip.TEST_STREAK` */
    const val ACTION_TEST_STREAK = "ca.benlu.plip.TEST_STREAK"
    const val SNOOZE_MIN = 10

    private const val STATUS_CHANNEL = "status"
    private const val STREAK_CHANNEL = "streak"
    private const val NOTIFICATION_ID = 1
    private const val STATUS_ID = 2
    private const val STREAK_ID = 3
    private const val MIN = 60_000L

    // Alarms can fire a few seconds early; still count that as inside the countdown window.
    private const val EARLY_SLACK = 60_000L

    fun createChannel(context: Context) {
        val reminders = NotificationChannel(CHANNEL, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH)
        reminders.description = context.getString(R.string.channel_reminders_desc)
        val status = NotificationChannel(STATUS_CHANNEL, context.getString(R.string.live_title), NotificationManager.IMPORTANCE_LOW)
        status.description = context.getString(R.string.channel_live_desc)
        status.setShowBadge(false)
        val streak = NotificationChannel(STREAK_CHANNEL, context.getString(R.string.channel_streak), NotificationManager.IMPORTANCE_HIGH)
        streak.description = context.getString(R.string.channel_streak_desc)
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannels(listOf(reminders, status, streak))
    }

    // ---------- actions ----------

    fun drink(context: Context, ml: Int, date: LocalDate = LocalDate.now()): Drink {
        val drink = Store.addDrink(ml, date)
        if (date == LocalDate.now()) {
            dismiss(context)
            if (!isPaused()) scheduleIn(context, Store.current.settings.interval)
        }
        return drink
    }

    fun removeDrink(drink: Drink) = Store.removeDrink(drink)

    fun snooze(context: Context, minutes: Int) {
        dismiss(context)
        scheduleAt(context, now() + minutes * MIN)
    }

    fun pause(context: Context, minutes: Int) {
        val until = now() + minutes * MIN
        Store.setPausedUntil(until)
        dismiss(context)
        setAlarm(context, until)
    }

    fun resume(context: Context) {
        Store.setPausedUntil(0)
        scheduleIn(context, Store.current.settings.interval)
    }

    fun updateSettings(context: Context, settings: Settings) {
        val old = Store.current.settings
        Store.saveSettings(settings)
        val timingChanged = old.interval != settings.interval ||
            old.activeFrom != settings.activeFrom ||
            old.activeTo != settings.activeTo
        if (timingChanged && !isPaused()) {
            scheduleIn(context, settings.interval)
        } else if (old.countdownLead != settings.countdownLead && !isPaused()) {
            setCountdownAlarm(context, Store.current.nextAt)
        }
        scheduleStreak(context)
    }

    /** Re-arms the alarm after app start, reboot or an update. */
    fun ensureScheduled(context: Context) {
        scheduleMidnight(context)
        scheduleStreak(context)
        val state = Store.current
        when {
            state.pausedUntil > now() -> setAlarm(context, state.pausedUntil)
            state.pausedUntil > 0 -> resume(context)
            state.nextAt > now() -> {
                setAlarm(context, state.nextAt)
                setCountdownAlarm(context, state.nextAt)
            }
            else -> scheduleIn(context, state.settings.interval)
        }
    }

    fun onAlarm(context: Context) {
        Store.refresh()
        val state = Store.current
        if (state.pausedUntil > 0) {
            if (now() < state.pausedUntil) setAlarm(context, state.pausedUntil) else resume(context)
            return
        }
        if (!isActive(state.settings, LocalTime.now())) {
            scheduleIn(context, state.settings.interval)
            return
        }
        notify(context)
        Store.setReminding(true)
        scheduleIn(context, state.settings.interval)
    }

    /** Rolls the status notification over to the new day. */
    fun onNewDay(context: Context) {
        Store.refresh()
        scheduleMidnight(context)
        scheduleStreak(context)
    }

    fun onStreakAlarm(context: Context) {
        Store.refresh()
        showStreak(context, Store.current)
        scheduleStreak(context)
    }

    // ---------- schedule ----------

    /** When today's streak window closes: end of the active time, or midnight if that comes first. */
    private fun streakDeadline(s: Settings): Long {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val midnight = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        if (s.activeTo <= s.activeFrom) return midnight
        return today.atTime(LocalTime.of(s.activeTo / 60, s.activeTo % 60)).atZone(zone).toInstant().toEpochMilli()
    }

    /** Wakes up when the streak warning should appear, and again at the deadline to take it down. */
    private fun scheduleStreak(context: Context) {
        val s = Store.current.settings
        val intent = PendingIntent.getBroadcast(
            context,
            6,
            Intent(context, PlipReceiver::class.java).setAction(ACTION_STREAK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (s.streakWarning == 0) {
            alarms.cancel(intent)
            return
        }
        val deadline = streakDeadline(s)
        val warnAt = deadline - s.streakWarning * MIN
        val at = when {
            now() < warnAt -> warnAt
            now() < deadline -> deadline
            // Today's window is over; the midnight alarm schedules tomorrow's.
            else -> return
        }
        if (canExact(context)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    private fun scheduleMidnight(context: Context) {
        val at = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 1000
        val intent = PendingIntent.getBroadcast(
            context,
            3,
            Intent(context, PlipReceiver::class.java).setAction(ACTION_NEW_DAY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
    }

    fun isActive(s: Settings, time: LocalTime): Boolean {
        val m = time.hour * 60 + time.minute
        return when {
            s.activeFrom == s.activeTo -> true
            s.activeFrom < s.activeTo -> m >= s.activeFrom && m < s.activeTo
            else -> m >= s.activeFrom || m < s.activeTo
        }
    }

    /** Moves a time that falls into the quiet hours to one interval after the next active start. */
    private fun intoActiveWindow(at: Long, s: Settings): Long {
        val zone = ZoneId.systemDefault()
        val time = Instant.ofEpochMilli(at).atZone(zone)
        if (isActive(s, time.toLocalTime())) return at
        var start = time.toLocalDate().atTime(LocalTime.of(s.activeFrom / 60, s.activeFrom % 60)).atZone(zone)
        if (!start.isAfter(time)) start = start.plusDays(1)
        return start.plusMinutes(s.interval.toLong()).toInstant().toEpochMilli()
    }

    private fun scheduleIn(context: Context, minutes: Int) = scheduleAt(context, now() + minutes * MIN)

    private fun scheduleAt(context: Context, at: Long) {
        val time = intoActiveWindow(at, Store.current.settings)
        Store.setNextAt(time)
        setAlarm(context, time)
        setCountdownAlarm(context, time)
    }

    /** Wakes up when the countdown window before a reminder opens, to show the Live Update. */
    private fun setCountdownAlarm(context: Context, reminderAt: Long) {
        val at = reminderAt - Store.current.settings.countdownLead * MIN
        if (at <= now()) return
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            context,
            5,
            Intent(context, PlipReceiver::class.java).setAction(ACTION_COUNTDOWN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (canExact(context)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    fun canExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun setAlarm(context: Context, at: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, PlipReceiver::class.java).setAction(ACTION_REMIND),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (canExact(context)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    // ---------- notification ----------

    fun notify(context: Context) {
        if (!canNotify(context)) return

        val state = Store.current
        val goal = state.settings.goal
        val text = if (state.total >= goal) {
            context.getString(R.string.notif_goal_done, formatMl(state.total))
        } else {
            context.getString(R.string.notif_today, formatMl(state.total), formatMl(goal))
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_drop)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(text)
            .setProgress(goal, min(state.total, goal), false)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.action_drank, formatMl(state.settings.glass)), action(context, ACTION_DRINK, 1))
            .addAction(0, context.getString(R.string.action_snooze, SNOOZE_MIN), action(context, ACTION_SNOOZE, 2))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    /**
     * The Live Update: either a countdown shortly before the next reminder, or today's total all day.
     * Called on every state change and when the countdown window opens.
     */
    fun showStatus(context: Context, state: PlipState, testCountdown: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val s = state.settings
        // The test always shows the countdown, aimed at the real next reminder or a few minutes ahead.
        val nextAt = if (testCountdown && state.nextAt <= now()) now() + s.countdownLead * MIN else state.nextAt
        val wanted = when (s.liveMode) {
            LiveMode.Off -> false
            LiveMode.Total -> true
            LiveMode.Countdown -> !state.reminding &&
                state.pausedUntil <= now() &&
                state.nextAt > now() &&
                now() >= state.nextAt - s.countdownLead * MIN - EARLY_SLACK
        }
        val visible = canNotify(context) && (wanted || testCountdown)
        if (!visible) {
            manager.cancel(STATUS_ID)
            return
        }

        val goal = s.goal
        // Android 16 Live Update: pinned to the top with a status bar chip.
        val builder = NotificationCompat.Builder(context, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_drop)
            .setStyle(progressStyle(goal, state.total))
            .setProgress(goal, min(state.total, goal), false)
            .setRequestPromotedOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(context))

        if (testCountdown || s.liveMode == LiveMode.Countdown) {
            // No short text here: the status bar chip then shows the ticking countdown itself.
            builder
                .setContentTitle(context.getString(R.string.live_countdown_title))
                .setContentText(context.getString(R.string.live_countdown_text, context.timeOfDay(nextAt), formatMl(state.total), formatMl(goal)))
                .setWhen(nextAt)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .addAction(0, context.getString(R.string.action_drank, formatMl(s.glass)), action(context, ACTION_DRINK, 4))
        } else {
            val title = if (state.total >= goal) {
                context.getString(R.string.live_total_done, formatMl(state.total))
            } else {
                context.getString(R.string.live_total_title, formatMl(state.total), formatMl(goal))
            }
            val text = when {
                state.pausedUntil > now() -> context.getString(R.string.live_paused, context.whenLabel(state.pausedUntil))
                state.nextAt > now() -> {
                    val today = Instant.ofEpochMilli(state.nextAt).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()
                    if (today) context.getString(R.string.live_next_today, context.timeOfDay(state.nextAt))
                    else context.getString(R.string.live_next_later, context.whenLabel(state.nextAt))
                }
                else -> context.getString(R.string.live_drunk_today)
            }
            builder
                .setShortCriticalText(litres(state.total))
                .setContentTitle(title)
                .setContentText(text)
                .setShowWhen(false)
                .addAction(0, "+${formatMl(s.glass)} ml", action(context, ACTION_DRINK, 4))
        }

        manager.notify(STATUS_ID, builder.build())
    }

    /**
     * "Save your streak" Live Update: shown in the last hours of the active time while a streak is at risk.
     * Called on every state change, so it disappears as soon as the goal is reached.
     */
    fun showStreak(context: Context, state: PlipState, test: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val s = state.settings
        val deadline = streakDeadline(s).let { if (test && it <= now()) now() + 60 * MIN else it }
        // Until today's goal is reached, state.streak counts the days up to yesterday, which is what is at risk.
        val atRisk = s.streakWarning > 0 &&
            state.streak > 0 &&
            state.total < s.goal &&
            now() < deadline &&
            now() >= deadline - s.streakWarning * MIN - EARLY_SLACK
        val visible = canNotify(context) && (atRisk || test)
        if (!visible) {
            manager.cancel(STREAK_ID)
            return
        }
        val notification = NotificationCompat.Builder(context, STREAK_CHANNEL)
            .setSmallIcon(R.drawable.ic_flame)
            .setContentTitle(context.resources.getQuantityString(R.plurals.streak_title, maxOf(state.streak, 1), maxOf(state.streak, 1)))
            .setContentText(context.getString(R.string.streak_text, formatMl(maxOf(s.goal - state.total, 0)), context.timeOfDay(deadline)))
            .setStyle(progressStyle(s.goal, state.total))
            .setProgress(s.goal, state.total, false)
            .setRequestPromotedOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            // Alerts once when it appears, then updates quietly
            .setOnlyAlertOnce(true)
            .setWhen(deadline)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setContentIntent(openApp(context))
            .addAction(0, "+${formatMl(s.glass)} ml", action(context, ACTION_DRINK, 7))
            .build()
        manager.notify(STREAK_ID, notification)
    }

    /** Android 16's progress bar for Live Updates; older versions fall back to setProgress. */
    private fun progressStyle(goal: Int, total: Int) = NotificationCompat.ProgressStyle()
        .addProgressSegment(NotificationCompat.ProgressStyle.Segment(goal))
        .setProgress(min(total, goal))

    /** False when the user switched off Live Updates for Plip in the system settings. */
    fun canPromote(context: Context) =
        NotificationManagerCompat.from(context).canPostPromotedNotifications()

    private fun canNotify(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        Store.setReminding(false)
    }

    private fun action(context: Context, action: String, code: Int) = PendingIntent.getBroadcast(
        context,
        code,
        Intent(context, PlipReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun isPaused() = Store.current.pausedUntil > now()

    private fun now() = System.currentTimeMillis()
}
