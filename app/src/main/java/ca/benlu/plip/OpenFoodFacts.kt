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
    private const val FIELDS = "code,product_name,brands,nutriments"

    // How Open Food Facts calls each nutrient. Its *_100g values are in grams.
    private val keys = mapOf(
        Nutrient.Magnesium to "magnesium",
        Nutrient.Calcium to "calcium",
        Nutrient.Potassium to "potassium",
        Nutrient.VitaminC to "vitamin-c",
        Nutrient.Sodium to "sodium",
        Nutrient.Caffeine to "caffeine",
    )

    /** Null when the barcode is unknown. Throws when Open Food Facts can't be reached. */
    suspend fun byBarcode(code: String): Beverage? = withContext(Dispatchers.IO) {
        val json = get("$BASE/api/v2/product/${URLEncoder.encode(code, "UTF-8")}?fields=$FIELDS")
        if (json.optInt("status") != 1) return@withContext null
        json.optJSONObject("product")?.let(::toBeverage)
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
