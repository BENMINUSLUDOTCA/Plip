package ca.benlu.plip

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** Per-app language (Android 13+). Without a choice, Plip follows the system language. */
object Language {
    /** Must match res/xml/locales_config.xml. */
    val supported = listOf("en", "de", "fr", "es", "it", "nl")

    val canChoose: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** The chosen language tag, or null when following the system. */
    fun current(context: Context): String? {
        if (!canChoose) return null
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        return if (locales.isEmpty) null else locales[0].language
    }

    /** null switches back to the system language. Android recreates the activity itself. */
    fun set(context: Context, tag: String?) {
        if (!canChoose) return
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }

    /** Each language in its own words, e.g. "Deutsch", "Français". */
    fun nativeName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
    }
}
