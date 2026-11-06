package ca.benlu.plip

import android.content.Context
import androidx.annotation.StringRes
import org.json.JSONArray
import org.json.JSONObject

/** Drinks with micronutrients, search and barcode scanning. Only in the test version until it is ready. */
val NUTRIENTS = BuildConfig.TEST_BUILD

/**
 * Micronutrients that drinks actually contribute, in mg.
 * [daily] is the EU reference intake, or for [isLimit] the amount not to go over.
 */
enum class Nutrient(val key: String, @StringRes val label: Int, val daily: Int, val isLimit: Boolean = false) {
    Magnesium("mg", R.string.nutrient_magnesium, 375),
    Calcium("ca", R.string.nutrient_calcium, 800),
    Potassium("k", R.string.nutrient_potassium, 2000),
    VitaminC("c", R.string.nutrient_vitamin_c, 80),
    // EU reference intake for salt is 6 g, which is 2.4 g of sodium
    Sodium("na", R.string.nutrient_sodium, 2400, isLimit = true),
    // EFSA: up to 400 mg a day is considered safe for adults
    Caffeine("caf", R.string.nutrient_caffeine, 400, isLimit = true),
}

/** A drink to log, with its micronutrients per 100 ml. */
data class Beverage(
    val name: String,
    val per100ml: Map<Nutrient, Float> = emptyMap(),
    val brand: String? = null,
    val barcode: String? = null,
) {
    /** What [ml] of this drink contains. */
    fun nutrientsFor(ml: Int): Map<Nutrient, Float> = per100ml.mapValues { it.value * ml / 100f }
}

object Beverages {
    private const val MAX_RECENT = 10

    /** Common drinks with average values, so the basics work offline. Per 100 ml, mostly from USDA FoodData Central. */
    fun common(context: Context): List<Beverage> = listOf(
        R.string.bev_water to emptyMap(),
        R.string.bev_coffee to mapOf(Nutrient.Potassium to 49f, Nutrient.Magnesium to 3f, Nutrient.Sodium to 2f, Nutrient.Caffeine to 40f),
        R.string.bev_espresso to mapOf(Nutrient.Potassium to 115f, Nutrient.Magnesium to 80f, Nutrient.Sodium to 14f, Nutrient.Caffeine to 212f),
        R.string.bev_black_tea to mapOf(Nutrient.Potassium to 37f, Nutrient.Magnesium to 3f, Nutrient.Caffeine to 20f),
        R.string.bev_green_tea to mapOf(Nutrient.Potassium to 8f, Nutrient.Magnesium to 1f, Nutrient.Caffeine to 12f),
        R.string.bev_milk to mapOf(Nutrient.Calcium to 120f, Nutrient.Potassium to 150f, Nutrient.Magnesium to 11f, Nutrient.Sodium to 44f),
        R.string.bev_orange_juice to mapOf(Nutrient.Potassium to 200f, Nutrient.VitaminC to 50f, Nutrient.Magnesium to 11f, Nutrient.Calcium to 11f),
        R.string.bev_apple_juice to mapOf(Nutrient.Potassium to 101f, Nutrient.Magnesium to 5f, Nutrient.Calcium to 8f, Nutrient.VitaminC to 1f),
        R.string.bev_coconut_water to mapOf(Nutrient.Potassium to 250f, Nutrient.Sodium to 105f, Nutrient.Magnesium to 25f, Nutrient.Calcium to 24f, Nutrient.VitaminC to 2f),
        R.string.bev_isotonic to mapOf(Nutrient.Sodium to 50f, Nutrient.Potassium to 12f),
        R.string.bev_cola to mapOf(Nutrient.Sodium to 4f, Nutrient.Caffeine to 10f),
        R.string.bev_energy to mapOf(Nutrient.Sodium to 40f, Nutrient.Caffeine to 32f),
    ).map { (name, values) -> Beverage(context.getString(name), values) }

    fun recent(context: Context): List<Beverage> {
        val list = JSONArray(prefs(context).getString("recent", null) ?: "[]")
        return (0 until list.length()).map { fromJson(list.getJSONObject(it)) }
    }

    /** Puts [beverage] at the top of the recent list. */
    fun remember(context: Context, beverage: Beverage) {
        val list = listOf(beverage) + recent(context).filterNot { it.name == beverage.name && it.barcode == beverage.barcode }
        val json = JSONArray().apply { list.take(MAX_RECENT).forEach { put(toJson(it)) } }
        prefs(context).edit().putString("recent", json.toString()).apply()
    }

    fun nutrientsToJson(values: Map<Nutrient, Float>) = JSONObject().apply {
        values.forEach { (n, v) -> put(n.key, v.toDouble()) }
    }

    fun nutrientsFromJson(json: JSONObject?): Map<Nutrient, Float> {
        json ?: return emptyMap()
        return Nutrient.entries.filter { json.has(it.key) }.associateWith { json.getDouble(it.key).toFloat() }
    }

    private fun toJson(b: Beverage) = JSONObject()
        .put("name", b.name)
        .put("brand", b.brand)
        .put("code", b.barcode)
        .put("per100", nutrientsToJson(b.per100ml))

    private fun fromJson(o: JSONObject) = Beverage(
        name = o.getString("name"),
        per100ml = nutrientsFromJson(o.optJSONObject("per100")),
        brand = o.optString("brand").takeIf { it.isNotEmpty() },
        barcode = o.optString("code").takeIf { it.isNotEmpty() },
    )

    private fun prefs(context: Context) = context.getSharedPreferences("beverages", Context.MODE_PRIVATE)
}
