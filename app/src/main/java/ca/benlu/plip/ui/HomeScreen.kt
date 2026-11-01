package ca.benlu.plip.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ca.benlu.plip.Drink
import ca.benlu.plip.Plip
import ca.benlu.plip.PlipState
import ca.benlu.plip.R
import ca.benlu.plip.Release
import ca.benlu.plip.Store
import ca.benlu.plip.Updater
import ca.benlu.plip.dayLabel
import ca.benlu.plip.timeOfDay
import ca.benlu.plip.formatMl
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val Amounts = listOf(
    100 to R.string.amount_sip,
    200 to R.string.amount_cup,
    250 to R.string.amount_glass,
    330 to R.string.amount_can,
    500 to R.string.amount_bottle,
    750 to R.string.amount_big_bottle,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: PlipState, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    var notificationsOn by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsOn = it
        if (it) Plip.showStatus(context, Store.current)
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationManagerCompat.from(context).areNotificationsEnabled()
        ) {
            askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val update by Updater.available.collectAsStateWithLifecycle()
    val install by Updater.install.collectAsStateWithLifecycle()
    var waitingForPermission by remember { mutableStateOf(false) }
    fun startUpdate(release: Release) {
        if (!Updater.canInstall(context)) {
            // Continues on return from the system screen once it is allowed.
            waitingForPermission = true
            context.startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
            )
            return
        }
        scope.launch { Updater.install(context, release) }
    }
    LifecycleResumeEffect(waitingForPermission) {
        if (waitingForPermission && Updater.canInstall(context)) {
            waitingForPermission = false
            update?.let(::startUpdate)
        }
        onPauseOrDispose { }
    }

    val today = LocalDate.now()
    var dayIndex by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    val day = LocalDate.ofEpochDay(dayIndex)
    val isToday = day == today
    // state changes on every write, so this re-reads the log whenever something was added or removed
    val drinks = remember(state, day) { Store.drinksOn(day) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }

    fun add(ml: Int) {
        val drink = Plip.drink(context, ml, day)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val message = context.getString(if (isToday) R.string.logged else R.string.logged_past, formatMl(ml))
            val result = snackbar.showSnackbar(message, context.getString(R.string.undo), duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) Plip.removeDrink(drink)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_drop),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.app_name))
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FloatingActionButton(
                    onClick = { historyOpen = true },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Icon(painterResource(R.drawable.ic_history), contentDescription = stringResource(R.string.history))
                }
                ExtendedFloatingActionButton(
                    onClick = { sheetOpen = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(if (isToday) R.string.add else R.string.add_past)) },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                // room for the floating buttons
                bottom = padding.calculateBottomPadding() + 160.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!notificationsOn) {
                item {
                    NotificationsOffCard {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    }
                }
            }
            update?.let { release ->
                item { UpdateCard(release, install, onUpdate = { startUpdate(release) }) }
            }
            item {
                DaySwitcher(
                    day = day,
                    canGoBack = day > today.minusDays(Store.KEEP_DAYS),
                    onBack = { dayIndex-- },
                    onForward = { dayIndex++ },
                    onToday = { dayIndex = today.toEpochDay() },
                )
            }
            item { Overview(state, drinks, isToday) }
            item { TimelineCard(drinks, state.settings, day, isToday) }
            item { WeekCard(state, selected = day, onSelect = { dayIndex = it.toEpochDay() }) }
        }
    }

    if (historyOpen) {
        HistorySheet(context.dayLabel(day), drinks, onDismiss = { historyOpen = false })
    }

    if (sheetOpen) {
        AddSheet(
            title = when (day) {
                today -> stringResource(R.string.add_title_today)
                today.minusDays(1) -> stringResource(R.string.add_title_yesterday)
                else -> stringResource(R.string.add_title_day, context.dayLabel(day))
            },
            onAdd = ::add,
            onDismiss = { sheetOpen = false },
        )
    }
}

@Composable
private fun DaySwitcher(
    day: LocalDate,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onToday: () -> Unit,
) {
    val today = LocalDate.now()
    val label = LocalContext.current.dayLabel(day)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, enabled = canGoBack) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.previous_day))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = day != today, onClick = onToday)
                .padding(vertical = 6.dp),
        )
        IconButton(onClick = onForward, enabled = day < today) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.next_day))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(title: String, onAdd: (Int) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var custom by rememberSaveable { mutableStateOf("") }
    val customMl = custom.toIntOrNull()?.takeIf { it in 1..3000 }

    fun pick(ml: Int) {
        onAdd(ml)
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Amounts.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (ml, label) ->
                        FilledTonalButton(
                            onClick = { pick(ml) },
                            modifier = Modifier
                                .weight(1f)
                                .height(76.dp),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(4.dp),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${formatMl(ml)} ml", style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(label), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.other_amount)) },
                    suffix = { Text("ml") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { customMl?.let(::pick) }),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { customMl?.let(::pick) },
                    enabled = customMl != null,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .height(56.dp),
                ) {
                    Text(stringResource(R.string.add))
                }
            }
        }
    }
}

@Composable
private fun WeekCard(state: PlipState, selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    val goal = state.settings.goal
    val reached = state.week.count { it.ml >= goal }
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.last_7_days), style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (reached == 0) {
                    stringResource(R.string.week_none)
                } else {
                    pluralStringResource(R.plurals.week_reached, reached, reached)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                state.week.forEach { day ->
                    val active = day.date == selected
                    val fraction = (day.ml.toFloat() / goal).coerceIn(0f, 1f)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onSelect(day.date) }
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(28.dp)
                                .height(88.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(track),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            if (fraction > 0f) {
                                // Flat top like a water level; the track already rounds the bottom
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight(fraction)
                                        .background(if (fraction >= 1f) primary else primary.copy(alpha = 0.45f)),
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).removeSuffix("."),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = if (active) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(dayName: String, drinks: List<Drink>, onDismiss: () -> Unit) {
    val sheetContainer = MaterialTheme.colorScheme.surfaceContainerLow
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = sheetContainer) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(stringResource(R.string.history), style = MaterialTheme.typography.titleLarge)
            Text(
                text = if (drinks.isEmpty()) {
                    dayName
                } else {
                    pluralStringResource(R.plurals.history_summary, drinks.size, dayName, drinks.size, formatMl(drinks.sumOf { it.ml }))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (drinks.isEmpty()) {
            Text(
                text = stringResource(R.string.history_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                items(drinks.asReversed(), key = { it.time }) { drink ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = sheetContainer),
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.ic_drop),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        headlineContent = { Text("${formatMl(drink.ml)} ml") },
                        supportingContent = { Text(LocalContext.current.timeOfDay(drink.time)) },
                        trailingContent = {
                            IconButton(onClick = { Plip.removeDrink(drink) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete_entry))
                            }
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationsOffCard(onFix: () -> Unit) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.notifications_off_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.notifications_off_text),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onFix, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.turn_on), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun UpdateCard(release: Release, install: Updater.Install, onUpdate: () -> Unit) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.update_title, release.version), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(
                    when (install) {
                        is Updater.Install.Downloading -> R.string.update_downloading
                        Updater.Install.Failed -> R.string.update_failed
                        Updater.Install.Idle -> R.string.update_text
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (install is Updater.Install.Downloading) {
                Spacer(Modifier.height(12.dp))
                val progress = install.progress
                if (progress == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            } else {
                Button(onClick = onUpdate, modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) {
                    Text(stringResource(R.string.update_install))
                }
            }
        }
    }
}
