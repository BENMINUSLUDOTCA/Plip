package ca.benlu.plip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PlipReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        when (intent.action) {
            Plip.ACTION_REMIND -> Plip.onAlarm(context)
            Plip.ACTION_DRINK -> Plip.drink(context, Store.current.settings.glass)
            Plip.ACTION_SNOOZE -> Plip.snooze(context, Plip.SNOOZE_MIN)
            Plip.ACTION_TEST -> Plip.notify(context)
            Plip.ACTION_NEW_DAY -> Plip.onNewDay(context)
            Plip.ACTION_COUNTDOWN -> Plip.showStatus(context, Store.current)
            Plip.ACTION_STREAK -> Plip.onStreakAlarm(context)
            Plip.ACTION_TEST_STREAK -> Plip.showStreak(context, Store.current, test = true)
            else -> Plip.ensureScheduled(context)
        }
    }
}
