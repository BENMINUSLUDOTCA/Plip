package ca.benlu.plip.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ca.benlu.plip.Beverage
import ca.benlu.plip.Beverages
import ca.benlu.plip.Drink
import ca.benlu.plip.Nutrient
import ca.benlu.plip.OpenFoodFacts
import ca.benlu.plip.R
import ca.benlu.plip.ScanActivity
import ca.benlu.plip.formatMl
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** "<1" for traces, so a drink with a little of something doesn't read as none. */
private fun mg(value: Float): String = if (value > 0f && value < 1f) "<1" else formatMl(value.roundToInt())

@Composable
private fun Nutrient.shortLabel(): String = when (this) {
    Nutrient.Magnesium -> "Mg"
    Nutrient.Calcium -> "Ca"
    Nutrient.Potassium -> "K"
    Nutrient.Sodium -> "Na"
    Nutrient.VitaminC -> "Vit. C"
    Nutrient.Caffeine -> stringResource(label)
}

/** "Mg 12 · Ca 30 mg / 100 ml", the biggest three. */
@Composable
private fun summary(b: Beverage): String {
    if (b.per100ml.isEmpty()) return stringResource(R.string.beverage_no_nutrients)
    val parts = b.per100ml.entries.sortedByDescending { it.value / it.key.daily }.take(3)
        .map { (n, v) -> "${n.shortLabel()} ${mg(v)}" }
    return stringResource(R.string.beverage_per_100, parts.joinToString(" · "))
}

/** Which drink the amounts in the add sheet are for. */
@Composable
fun BeverageChoice(beverage: Beverage?, onChange: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        onClick = onChange,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_drop), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(beverage?.name ?: stringResource(R.string.bev_water), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = beverage?.let { summary(it) } ?: stringResource(R.string.beverage_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onChange) { Text(stringResource(R.string.beverage_change)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeverageSheet(onPick: (Beverage) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val common = remember { Beverages.common(context) }
    val recent = remember { Beverages.recent(context) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Beverage>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }

    fun pick(b: Beverage) {
        Beverages.remember(context, b)
        onPick(b)
    }

    // Searches a moment after typing stops, to stay within Open Food Facts' rate limit.
    val term = query.trim()
    LaunchedEffect(term) {
        results = null
        message = null
        if (term.length < 3) return@LaunchedEffect
        delay(700)
        loading = true
        try {
            runCatching { OpenFoodFacts.search(term) }
                .onSuccess {
                    results = it
                    if (it.isEmpty()) message = R.string.beverage_none
                }
                .onFailure { message = R.string.beverage_offline }
        } finally {
            loading = false
        }
    }

    // ZXing's scan screen; it asks for the camera itself. Empty contents means the scan was cancelled.
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@rememberLauncherForActivityResult
        scope.launch {
            loading = true
            message = null
            runCatching { OpenFoodFacts.byBarcode(raw) }
                .onSuccess { if (it != null) pick(it) else message = R.string.beverage_not_found }
                .onFailure { message = R.string.beverage_offline }
            loading = false
        }
    }
    fun scan() {
        scanner.launch(
            ScanOptions()
                .setCaptureActivity(ScanActivity::class.java)
                .setDesiredBarcodeFormats(ScanOptions.PRODUCT_CODE_TYPES)
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    val sheetContainer = MaterialTheme.colorScheme.surfaceContainerLow
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = sheetContainer,
    ) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(stringResource(R.string.beverage_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.beverage_search)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.weight(1f),
                )
                FilledTonalIconButton(onClick = ::scan, modifier = Modifier.padding(top = 8.dp).height(56.dp).width(56.dp)) {
                    Icon(painterResource(R.drawable.ic_barcode), contentDescription = stringResource(R.string.beverage_scan))
                }
            }
            if (loading) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            message?.let {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        val local = common.filter { term.isEmpty() || it.name.contains(term, ignoreCase = true) }
        LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
            if (term.isEmpty() && recent.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.beverage_recent)) }
                items(recent, key = { "r" + it.name + it.barcode }) { BeverageItem(it, sheetContainer) { pick(it) } }
            }
            if (local.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.beverage_common)) }
                items(local, key = { "c" + it.name }) { BeverageItem(it, sheetContainer) { pick(it) } }
            }
            results?.takeIf { it.isNotEmpty() }?.let { found ->
                item { SectionLabel(stringResource(R.string.beverage_results)) }
                items(found, key = { "o" + it.barcode + it.name }) { BeverageItem(it, sheetContainer) { pick(it) } }
            }
            item {
                Text(
                    text = stringResource(R.string.beverage_source),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun BeverageItem(b: Beverage, container: Color, onClick: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = container),
        headlineContent = { Text(b.name, maxLines = 1) },
        overlineContent = b.brand?.let { brand -> { Text(brand, maxLines = 1) } },
        supportingContent = { Text(summary(b), maxLines = 1) },
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .clickable(onClick = onClick),
    )
}

/** What today's (or the selected day's) drinks brought along. Has no effect on the goal or the streak. */
@Composable
fun NutrientCard(drinks: List<Drink>) {
    val totals = Nutrient.entries.associateWith { n -> drinks.sumOf { (it.nutrients[n] ?: 0f).toDouble() }.toFloat() }
    val any = totals.values.any { it > 0f }
    val colors = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.nutrients_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(if (any) R.string.nutrients_hint else R.string.nutrients_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            if (any) {
                Spacer(Modifier.height(8.dp))
                Nutrient.entries.forEach { n ->
                    val total = totals.getValue(n)
                    val over = n.isLimit && total > n.daily
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(stringResource(n.label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            text = stringResource(
                                if (n.isLimit) R.string.nutrient_amount_max else R.string.nutrient_amount_of,
                                mg(total),
                                formatMl(n.daily),
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (over) colors.error else colors.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (total / n.daily).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        // Limits get a calmer color, and turn red once crossed
                        color = when {
                            over -> colors.error
                            n.isLimit -> colors.tertiary
                            else -> colors.primary
                        },
                        trackColor = colors.surfaceContainerHighest,
                        drawStopIndicator = {},
                    )
                }
            }
        }
    }
}
