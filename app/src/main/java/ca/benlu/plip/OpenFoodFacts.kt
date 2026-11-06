package ca.benlu.plip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Product lookups in Open Food Facts, the open food database (data under ODbL). */
object OpenFoodFacts {
    private const val BASE = "https://world.openfoodfacts.org"
    private const val FIELDS = "code,product_name,brands,nutriments,categories_tags,product_quantity_unit"

    // Sister databases for things that aren't food, so a scanned shampoo says "not a drink" instead of "not found"
    private val NON_FOOD = listOf("https://world.openbeautyfacts.org", "https://world.openproductsfacts.org")

    /** What a scanned barcode turned out to be. */
    sealed interface Lookup {
        data class Drink(val beverage: Beverage) : Lookup
        /** A product, just not one to drink. [name] is null when the database has none. */
        data class NotDrink(val name: String?) : Lookup
        data object Unknown : Lookup
    }

    // How Open Food Facts calls each nutrient. Its *_100g values are in grams.
    private val keys = mapOf(
        Nutrient.Magnesium to "magnesium",
        Nutrient.Calcium to "calcium",
        Nutrient.Potassium to "potassium",
        Nutrient.VitaminC to "vitamin-c",
        Nutrient.Sodium to "sodium",
        Nutrient.Caffeine to "caffeine",
    )

    /** Throws when Open Food Facts can't be reached. */
    suspend fun byBarcode(code: String): Lookup = withContext(Dispatchers.IO) {
        val path = "/api/v2/product/${URLEncoder.encode(code, "UTF-8")}"
        val product = get("$BASE$path?fields=$FIELDS").takeIf { it.optInt("status") == 1 }?.optJSONObject("product")
        if (product != null) {
            val name = product.optString("product_name").trim().takeIf { it.isNotEmpty() }
            return@withContext if (isDrink(product)) {
                Lookup.Drink(toBeverage(product) ?: Beverage(name = product.optString("brands").ifBlank { code }, barcode = code))
            } else {
                Lookup.NotDrink(name)
            }
        }
        for (site in NON_FOOD) {
            val other = runCatching { get("$site$path?fields=product_name") }.getOrNull()
                ?.takeIf { it.optInt("status") == 1 }?.optJSONObject("product") ?: continue
            return@withContext Lookup.NotDrink(other.optString("product_name").trim().takeIf { it.isNotEmpty() })
        }
        Lookup.Unknown
    }

    /**
     * A drink is in a beverage category and not sold by weight (that would be coffee beans or tea powder).
     * Without categories, being sold by volume is the best hint left.
     */
    private fun isDrink(p: JSONObject): Boolean {
        val tags = p.optJSONArray("categories_tags")
        val categories = if (tags == null) emptyList() else List(tags.length()) { tags.getString(it) }
        val unit = p.optString("product_quantity_unit").lowercase()
        val byWeight = unit == "g" || unit == "kg"
        if (categories.isEmpty()) return unit == "ml" || unit == "cl" || unit == "l"
        // Not "plant-based foods and beverages": that one also holds pasta and bread
        val drinkCategory = categories.filter { it != "en:plant-based-foods-and-beverages" }
            .any { "beverage" in it || it.endsWith("-drinks") || it == "en:milks" || it == "en:waters" }
        return drinkCategory && !byWeight
    }

    /** Drinks matching [query], products without a name left out. Throws when Open Food Facts can't be reached. */
    suspend fun search(query: String): List<Beverage> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        val json = get(
            "$BASE/cgi/search.pl?search_terms=$q&search_simple=1&action=process&json=1&page_size=25&fields=$FIELDS" +
                // Only drinks
                "&tagtype_0=categories&tag_contains_0=contains&tag_0=beverages",
        )
        val products = json.optJSONArray("products") ?: return@withContext emptyList()
        (0 until products.length()).mapNotNull { toBeverage(products.getJSONObject(it)) }
    }

    private fun toBeverage(p: JSONObject): Beverage? {
        val name = p.optString("product_name").trim().takeIf { it.isNotEmpty() } ?: return null
        val nutriments = p.optJSONObject("nutriments")
        val values = keys.mapNotNull { (nutrient, key) ->
            val grams = nutriments?.optDouble("${key}_100g", Double.NaN) ?: Double.NaN
            if (grams.isNaN() || grams <= 0) null else nutrient to (grams * 1000).toFloat()
        }.toMap()
        return Beverage(
            name = name,
            per100ml = values,
            brand = p.optString("brands").split(',').first().trim().takeIf { it.isNotEmpty() },
            barcode = p.optString("code").takeIf { it.isNotEmpty() },
        )
    }

    private fun get(url: String): JSONObject = (URL(url).openConnection() as HttpURLConnection).run {
        // Open Food Facts asks apps to name themselves
        setRequestProperty("User-Agent", "Plip/${BuildConfig.VERSION_NAME} (Android; github.com/BENMINUSLUDOTCA/Plip)")
        connectTimeout = 15_000
        readTimeout = 20_000
        try {
            // An unknown barcode comes back as 404
            if (responseCode == 404) return JSONObject()
            if (responseCode != 200) throw IOException("Open Food Facts answered $responseCode")
            JSONObject(inputStream.bufferedReader().readText())
        } finally {
            disconnect()
        }
    }
}
