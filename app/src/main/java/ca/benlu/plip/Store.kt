package ca.benlu.plip

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class Settings(
    val goal: Int = 2500,
    val interval: Int = 45,
    val activeFrom: Int = 8 * 60,
    val activeTo: Int = 22 * 60,
    val glass: Int = 250,
    val dynamicColor: Boolean = true,
    val liveMode: LiveMode = LiveMode.Countdown,
    val countdownLead: Int = 10,
    /** Minutes before the active time ends to warn about a streak at risk. 0 = off. */
    val streakWarning: Int = 120,
    val devMode: Boolean = false,
)

/** What the ongoing Live Update notification shows. */
enum class LiveMode { Off, Total, Countdown }

data class Drink(val time: Long, val ml: Int)

data class Day(val date: LocalDate, val ml: Int)

data class PlipState(
    val settings: Settings = Settings(),
    /** Days in a row with the goal reached. Today only counts once it is reached. */
    val streak: Int = 0,
    val today: List<Drink> = emptyList(),
    val week: List<Day> = emptyList(),
    val nextAt: Long = 0,
    val pausedUntil: Long = 0,
    /** A reminder is on screen and not answered yet. */
    val reminding: Boolean = false,
) {
    val total: Int get() = today.sumOf { it.ml }
}

/** Single source of truth, shared by the UI and the alarm receiver. */
object Store {
    const val KEEP_DAYS = 60L

    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(PlipState())
    val state: StateFlow<PlipState> = _state.asStateFlow()
    val current: PlipState get() = _state.value

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("plip", Context.MODE_PRIVATE)
        refresh()
    }

    fun refresh() {
        val log = readLog()
        val today = LocalDate.now()
        val d = Settings()
        val settings = Settings(
            goal = prefs.getInt("goal", d.goal),
            interval = prefs.getInt("interval", d.interval),
            activeFrom = prefs.getInt("activeFrom", d.activeFrom),
            activeTo = prefs.getInt("activeTo", d.activeTo),
            glass = prefs.getInt("glass", d.glass),
            dynamicColor = prefs.getBoolean("dynamicColor", d.dynamicColor),
            liveMode = LiveMode.entries.firstOrNull { it.name == prefs.getString("liveMode", null) } ?: d.liveMode,
            countdownLead = prefs.getInt("countdownLead", d.countdownLead),
            streakWarning = prefs.getInt("streakWarning", d.streakWarning),
            devMode = prefs.getBoolean("devMode", d.devMode),
        )
        _state.value = PlipState(
            settings = settings,
            streak = streak(log, today, settings.goal),
            today = entries(log, today),
            week = (6 downTo 0).map { back ->
                val date = today.minusDays(back.toLong())
                Day(date, entries(log, date).sumOf { it.ml })
            },
            nextAt = prefs.getLong("nextAt", 0),
            pausedUntil = prefs.getLong("pausedUntil", 0),
            reminding = prefs.getBoolean("reminding", false),
        )
    }

    /** Past days get the current time of day on that date, so entries stay in order. */
    fun addDrink(ml: Int, date: LocalDate = LocalDate.now()): Drink {
        val time = if (date == LocalDate.now()) {
            System.currentTimeMillis()
        } else {
            date.atTime(LocalTime.now()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        val log = readLog()
        val key = date.toString()
        val list = log.optJSONArray(key) ?: JSONArray()
        list.put(JSONArray().put(time).put(ml))
        log.put(key, list)
        prune(log)
        writeLog(log)
        return Drink(time, ml)
    }

    fun drinksOn(date: LocalDate): List<Drink> =
        if (date == LocalDate.now()) current.today else entries(readLog(), date).sortedBy { it.time }

    fun removeDrink(drink: Drink) {
        val log = readLog()
        val key = Instant.ofEpochMilli(drink.time).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val list = log.optJSONArray(key) ?: return
        for (i in 0 until list.length()) {
            if (list.getJSONArray(i).getLong(0) == drink.time) {
                list.remove(i)
                break
            }
        }
        writeLog(log)
    }

    fun saveSettings(s: Settings) {
        prefs.edit()
            .putInt("goal", s.goal)
            .putInt("interval", s.interval)
            .putInt("activeFrom", s.activeFrom)
            .putInt("activeTo", s.activeTo)
            .putInt("glass", s.glass)
            .putBoolean("dynamicColor", s.dynamicColor)
            .putString("liveMode", s.liveMode.name)
            .putInt("countdownLead", s.countdownLead)
            .putInt("streakWarning", s.streakWarning)
            .putBoolean("devMode", s.devMode)
            .apply()
        refresh()
    }

    fun setNextAt(time: Long) {
        prefs.edit().putLong("nextAt", time).apply()
        refresh()
    }

    fun setPausedUntil(time: Long) {
        prefs.edit().putLong("pausedUntil", time).apply()
        refresh()
    }

    fun setReminding(on: Boolean) {
        if (current.reminding == on) return
        prefs.edit().putBoolean("reminding", on).apply()
        refresh()
    }

    private fun readLog() = JSONObject(prefs.getString("log", null) ?: "{}")

    private fun writeLog(log: JSONObject) {
        prefs.edit().putString("log", log.toString()).apply()
        refresh()
    }

    private fun entries(log: JSONObject, date: LocalDate): List<Drink> {
        val list = log.optJSONArray(date.toString()) ?: return emptyList()
        return List(list.length()) {
            val e = list.getJSONArray(it)
            Drink(e.getLong(0), e.getInt(1))
        }
    }

    private fun streak(log: JSONObject, today: LocalDate, goal: Int): Int {
        fun reached(date: LocalDate) = entries(log, date).sumOf { it.ml } >= goal
        var day = if (reached(today)) today else today.minusDays(1)
        var count = 0
        while (count < KEEP_DAYS && reached(day)) {
            count++
            day = day.minusDays(1)
        }
        return count
    }

    private fun prune(log: JSONObject) {
        val cutoff = LocalDate.now().minusDays(KEEP_DAYS).toString()
        log.keys().asSequence().toList().filter { it < cutoff }.forEach(log::remove)
    }
}
