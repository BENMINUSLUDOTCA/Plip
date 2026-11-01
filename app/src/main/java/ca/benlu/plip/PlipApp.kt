package ca.benlu.plip

import android.app.Application
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class PlipApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Plip.createChannel(this)
        // Keeps the ongoing "today so far" notification in sync with every change.
        MainScope().launch {
            Store.state.collect {
                Plip.showStatus(this@PlipApp, it)
                Plip.showStreak(this@PlipApp, it)
            }
        }
    }
}
