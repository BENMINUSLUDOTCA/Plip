package ca.benlu.plip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PlipReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        when (intent.action) {
            Plip.ACTION_REMIND -> {
                Plip.onAlarm(context)
                // Piggybacks on the reminder so update notifications only come during the active hours.
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        Updater.checkAndNotify(context)
                    } finally {
                        pending.finish()
                    }
                }
            }
            Plip.ACTION_DRINK -> Plip.drink(context, Store.current.settings.glass)
            Plip.ACTION_SNOOZE -> Plip.snooze(context, Plip.SNOOZE_MIN)
            Plip.ACTION_TEST -> Plip.notify(context)
            Plip.ACTION_NEW_DAY -> Plip.onNewDay(context)
            Plip.ACTION_COUNTDOWN -> Plip.showStatus(context, Store.current)
            Plip.ACTION_STREAK -> Plip.onStreakAlarm(context)
            Updater.ACTION_INSTALL_STATUS -> Updater.onInstallStatus(context, intent)
            Plip.ACTION_TEST_STREAK -> Plip.showStreak(context, Store.current, test = true)
            else -> Plip.ensureScheduled(context)
        }
    }
}
