package com.simplemobiletools.calendar.extensions

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import com.simplemobiletools.calendar.R
import com.simplemobiletools.calendar.activities.EventActivity
import com.simplemobiletools.calendar.helpers.*
import com.simplemobiletools.calendar.models.Event
import com.simplemobiletools.calendar.receivers.NotificationReceiver
import com.simplemobiletools.commons.extensions.getContrastColor
import org.joda.time.DateTime
import org.joda.time.DateTimeZone

fun Context.updateWidgets() {
    val widgetsCnt = AppWidgetManager.getInstance(this).getAppWidgetIds(ComponentName(this, MyWidgetMonthlyProvider::class.java))
    if (widgetsCnt.isNotEmpty()) {
        val ids = intArrayOf(R.xml.widget_monthly_info)
        Intent(this, MyWidgetMonthlyProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            sendBroadcast(this)
        }
    }

    updateListWidget()
}

fun Context.updateListWidget() {
    val widgetsCnt = AppWidgetManager.getInstance(this).getAppWidgetIds(ComponentName(this, MyWidgetListProvider::class.java))
    if (widgetsCnt.isNotEmpty()) {
        val ids = intArrayOf(R.xml.widget_list_info)
        Intent(this, MyWidgetListProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            sendBroadcast(this)
        }
    }
}

fun Context.scheduleNextEventReminder(event: Event) {
    if (event.getReminders().isEmpty())
        return

    val now = System.currentTimeMillis() / 1000 + 3
    var nextTS = Int.MAX_VALUE
    val reminderSeconds = event.getReminders().reversed().map { it * 60 }
    reminderSeconds.forEach {
        var startTS = event.startTS - it
        if (event.repeatInterval == DAY || event.repeatInterval == WEEK || event.repeatInterval == BIWEEK) {
            while (startTS < now || event.ignoreEventOccurrences.contains(startTS + it)) {
                startTS += event.repeatInterval
            }
            nextTS = Math.min(nextTS, startTS)
        } else if (event.repeatInterval == MONTH) {
            nextTS = Math.min(nextTS, getNewTS(startTS, true))
        } else if (event.repeatInterval == YEAR) {
            nextTS = Math.min(nextTS, getNewTS(startTS, false))
        } else if (startTS > now) {
            nextTS = Math.min(nextTS, startTS)
        }
    }

    if (nextTS == 0 || nextTS < now || nextTS == Int.MAX_VALUE)
        return

    if (event.repeatLimit == 0 || event.repeatLimit > nextTS)
        scheduleEventIn(nextTS, event)
}

private fun getNewTS(ts: Int, isMonthly: Boolean): Int {
    var dateTime = Formatter.getDateTimeFromTS(ts)
    while (dateTime.isBeforeNow) {
        dateTime = if (isMonthly) dateTime.plusMonths(1) else dateTime.plusYears(1)
    }
    return dateTime.seconds()
}

fun Context.scheduleReminder(event: Event) {
    if (event.getReminders().isNotEmpty())
        scheduleNextEventReminder(event)
}

fun Context.scheduleEventIn(notifTS: Int, event: Event) {
    val delayFromNow = notifTS.toLong() * 1000 - System.currentTimeMillis()
    if (delayFromNow <= 0)
        return

    val notifInMs = SystemClock.elapsedRealtime() + delayFromNow
    val pendingIntent = getNotificationIntent(this, event.id)
    val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
        alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, notifInMs, pendingIntent)
    else
        alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, notifInMs, pendingIntent)
}

private fun getNotificationIntent(context: Context, eventId: Int): PendingIntent {
    val intent = Intent(context, NotificationReceiver::class.java)
    intent.putExtra(EVENT_ID, eventId)
    return PendingIntent.getBroadcast(context, eventId, intent, PendingIntent.FLAG_UPDATE_CURRENT)
}

fun Context.getAppropriateTheme() = if (config.backgroundColor.getContrastColor() == Color.WHITE) R.style.MyDialogTheme_Dark else R.style.MyDialogTheme

fun Context.getReminderText(minutes: Int) = when (minutes) {
    -1 -> getString(R.string.no_reminder)
    0 -> getString(R.string.at_start)
    else -> {
        if (minutes % 1440 == 0)
            resources.getQuantityString(R.plurals.days, minutes / 1440, minutes / 1440)
        else if (minutes % 60 == 0)
            resources.getQuantityString(R.plurals.hours, minutes / 60, minutes / 60)
        else
            resources.getQuantityString(R.plurals.minutes, minutes, minutes)
    }
}

fun Context.getRepetitionText(seconds: Int): String {
    val days = seconds / 60 / 60 / 24
    return when (days) {
        0 -> getString(R.string.no_repetition)
        1 -> getString(R.string.daily)
        7 -> getString(R.string.weekly)
        14 -> getString(R.string.biweekly)
        30 -> getString(R.string.monthly)
        365 -> getString(R.string.yearly)
        else -> {
            if (days % 365 == 0)
                resources.getQuantityString(R.plurals.years, days / 365, days / 365)
            else if (days % 30 == 0)
                resources.getQuantityString(R.plurals.months, days / 30, days / 30)
            else if (days % 7 == 0)
                resources.getQuantityString(R.plurals.weeks, days / 7, days / 7)
            else
                resources.getQuantityString(R.plurals.days, days, days)
        }
    }
}

fun Context.getFilteredEvents(events: List<Event>): List<Event> {
    val displayEventTypes = config.displayEventTypes
    return events.filter { displayEventTypes.contains(it.eventType.toString()) }
}

fun Context.launchNewEventIntent(startNewTask: Boolean = false, today: Boolean = false) {
    val code = Formatter.getDayCodeFromDateTime(DateTime(DateTimeZone.getDefault()).plusDays(if (today) 0 else 1))
    Intent(applicationContext, EventActivity::class.java).apply {
        putExtra(NEW_EVENT_START_TS, getNewEventTimestampFromCode(code))
        if (startNewTask)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(this)
    }
}

fun Context.getNewEventTimestampFromCode(dayCode: String) = Formatter.getLocalDateTimeFromCode(dayCode).withTime(13, 0, 0, 0).seconds()

val Context.config: Config get() = Config.newInstance(this)
