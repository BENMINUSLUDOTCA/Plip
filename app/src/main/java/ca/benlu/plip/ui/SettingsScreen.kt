package ca.benlu.plip.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import ca.benlu.plip.Language
import ca.benlu.plip.Plip
import ca.benlu.plip.R
import ca.benlu.plip.Store
import ca.benlu.plip.Updater
import ca.benlu.plip.LiveMode
import ca.benlu.plip.PlipState
import ca.benlu.plip.Settings
import ca.benlu.plip.timeOfDay
import ca.benlu.plip.formatMl
import ca.benlu.plip.intervalLabel
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private enum class Dialog { Interval, From, To, Goal, Glass, Live, Lead, Language, Streak }

private fun liveModeLabel(mode: LiveMode) = when (mode) {
    LiveMode.Off -> R.string.live_off
    LiveMode.Total -> R.string.live_total
    LiveMode.Countdown -> R.string.live_countdown
}

private fun liveModeHint(mode: LiveMode) = when (mode) {
    LiveMode.Off -> R.string.live_off
    LiveMode.Total -> R.string.live_total_hint
    LiveMode.Countdown -> R.string.live_countdown_hint
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: PlipState, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = state.settings
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var exact by remember { mutableStateOf(Plip.canExact(context)) }
    var promote by remember { mutableStateOf(Plip.canPromote(context)) }
    val language = remember { Language.current(context) }
    LifecycleResumeEffect(Unit) {
        exact = Plip.canExact(context)
        promote = Plip.canPromote(context)
        onPauseOrDispose { }
    }

    fun update(settings: Settings) = Plip.updateSettings(context, settings)

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 32.dp)) {
            item { Header(stringResource(R.string.section_reminders)) }
            item { SettingRow(stringResource(R.string.remind_every), context.intervalLabel(s.interval)) { dialog = Dialog.Interval } }
            item { SettingRow(stringResource(R.string.active_from), context.timeOfDay(s.activeFrom)) { dialog = Dialog.From } }
            item { SettingRow(stringResource(R.string.active_to), stringResource(R.string.active_to_hint, context.timeOfDay(s.activeTo))) { dialog = Dialog.To } }
            if (!exact) {
                item {
                    SettingRow(stringResource(R.string.exact_title), stringResource(R.string.exact_hint)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            context.startActivity(
                                Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                    .setData(Uri.parse("package:${context.packageName}")),
                            )
                        }
                    }
                }
            }
            item { SettingRow(stringResource(R.string.live_title), stringResource(liveModeHint(s.liveMode))) { dialog = Dialog.Live } }
            if (!promote && s.liveMode != LiveMode.Off && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                item {
                    SettingRow(stringResource(R.string.promote_title), stringResource(R.string.promote_hint)) {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    }
                }
            }
            if (s.liveMode == LiveMode.Countdown) {
                item {
                    SettingRow(stringResource(R.string.countdown_starts), stringResource(R.string.countdown_lead, s.countdownLead)) {
                        dialog = Dialog.Lead
                    }
                }
            }
            item {
                SettingRow(
                    stringResource(R.string.streak_warning_title),
                    if (s.streakWarning == 0) {
                        stringResource(R.string.live_off)
                    } else {
                        stringResource(R.string.streak_warning_hint, context.intervalLabel(s.streakWarning))
                    },
                ) { dialog = Dialog.Streak }
            }
            item {
                SettingRow(stringResource(R.string.sound_title), stringResource(R.string.sound_hint)) {
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                            .putExtra(AndroidSettings.EXTRA_CHANNEL_ID, Plip.CHANNEL),
                    )
                }
            }

            item { Header(stringResource(R.string.section_drinking)) }
            item { SettingRow(stringResource(R.string.goal_title), "${formatMl(s.goal)} ml") { dialog = Dialog.Goal } }
            item {
                SettingRow(stringResource(R.string.glass_title), stringResource(R.string.glass_hint, formatMl(s.glass))) {
                    dialog = Dialog.Glass
                }
            }

            item { Header(stringResource(R.string.section_display)) }
            if (Language.canChoose) {
                item {
                    SettingRow(
                        stringResource(R.string.language_title),
                        language?.let(Language::nativeName) ?: stringResource(R.string.language_system),
                    ) { dialog = Dialog.Language }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    ListItem(
                        modifier = Modifier.clickable { update(s.copy(dynamicColor = !s.dynamicColor)) },
                        headlineContent = { Text(stringResource(R.string.dynamic_title)) },
                        supportingContent = { Text(stringResource(R.string.dynamic_hint)) },
                        trailingContent = { Switch(checked = s.dynamicColor, onCheckedChange = null) },
                    )
                }
            }

            item { Header(stringResource(R.string.section_about)) }
            item {
                SettingRow(stringResource(R.string.check_updates), "${stringResource(R.string.version_title)} ${appVersion(context)}") {
                    scope.launch {
                        val message = runCatching { Updater.check(context, force = true) }.fold(
                            onSuccess = { if (it == null) R.string.up_to_date else R.string.update_found },
                            onFailure = { R.string.check_failed },
                        )
                        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }

            item { Header(stringResource(R.string.section_developer)) }
            item {
                ListItem(
                    modifier = Modifier.clickable { update(s.copy(devMode = !s.devMode)) },
                    headlineContent = { Text(stringResource(R.string.dev_mode_title)) },
                    supportingContent = { Text(stringResource(R.string.dev_mode_hint)) },
                    trailingContent = { Switch(checked = s.devMode, onCheckedChange = null) },
                )
            }
            if (s.devMode) {
                item { SettingRow(stringResource(R.string.test_title), stringResource(R.string.test_hint)) { Plip.notify(context) } }
                item {
                    SettingRow(stringResource(R.string.test_countdown_title), stringResource(R.string.test_countdown_hint)) {
                        Plip.showStatus(context, Store.current, testCountdown = true)
                    }
                }
                item {
                    SettingRow(stringResource(R.string.test_streak_title), stringResource(R.string.test_streak_hint)) {
                        Plip.showStreak(context, Store.current, test = true)
                    }
                }
            }
        }
    }

    when (dialog) {
        Dialog.Interval -> ChoiceDialog(
            title = stringResource(R.string.remind_every),
            options = listOf(20, 30, 45, 60, 90, 120),
            selected = s.interval,
            label = context::intervalLabel,
            onPick = { update(s.copy(interval = it)) },
            onDismiss = { dialog = null },
        )
        Dialog.Glass -> ChoiceDialog(
            title = stringResource(R.string.glass_title),
            options = listOf(100, 200, 250, 330, 500),
            selected = s.glass,
            label = { "${formatMl(it)} ml" },
            onPick = { update(s.copy(glass = it)) },
            onDismiss = { dialog = null },
        )
        Dialog.Live -> ChoiceDialog(
            title = stringResource(R.string.live_title),
            options = LiveMode.entries.map { it.ordinal },
            selected = s.liveMode.ordinal,
            label = { context.getString(liveModeLabel(LiveMode.entries[it])) },
            onPick = { update(s.copy(liveMode = LiveMode.entries[it])) },
            onDismiss = { dialog = null },
        )
        Dialog.Lead -> ChoiceDialog(
            title = stringResource(R.string.countdown_starts),
            options = listOf(5, 10, 15, 20, 30),
            selected = s.countdownLead,
            label = { context.getString(R.string.minutes_before, it) },
            onPick = { update(s.copy(countdownLead = it)) },
            onDismiss = { dialog = null },
        )
        Dialog.Streak -> ChoiceDialog(
            title = stringResource(R.string.streak_warning_title),
            options = listOf(0, 60, 120, 180),
            selected = s.streakWarning,
            label = {
                if (it == 0) context.getString(R.string.live_off)
                else context.getString(R.string.streak_warning_option, context.intervalLabel(it))
            },
            onPick = { update(s.copy(streakWarning = it)) },
            onDismiss = { dialog = null },
        )
        Dialog.Language -> ChoiceDialog(
            title = stringResource(R.string.language_title),
            // index 0 is "system language", then the supported languages
            options = (0..Language.supported.size).toList(),
            selected = language?.let { Language.supported.indexOf(it) + 1 } ?: 0,
            label = { if (it == 0) context.getString(R.string.language_system) else Language.nativeName(Language.supported[it - 1]) },
            onPick = { Language.set(context, if (it == 0) null else Language.supported[it - 1]) },
            onDismiss = { dialog = null },
        )
        Dialog.From -> TimeDialog(stringResource(R.string.active_from), s.activeFrom, { update(s.copy(activeFrom = it)) }) { dialog = null }
        Dialog.To -> TimeDialog(stringResource(R.string.active_to), s.activeTo, { update(s.copy(activeTo = it)) }) { dialog = null }
        Dialog.Goal -> GoalDialog(s.goal, { update(s.copy(goal = it)) }) { dialog = null }
        null -> Unit
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingRow(title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
    )
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Int>,
    selected: Int,
    label: (Int) -> String,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .selectable(
                                selected = option == selected,
                                role = Role.RadioButton,
                                onClick = {
                                    onPick(option)
                                    onDismiss()
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(label(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(title: String, minutes: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val picker = rememberTimePickerState(minutes / 60, minutes % 60, is24Hour = DateFormat.is24HourFormat(context))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = picker) },
        confirmButton = {
            TextButton(onClick = {
                onPick(picker.hour * 60 + picker.minute)
                onDismiss()
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun GoalDialog(goal: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableFloatStateOf(goal.toFloat()) }
    val ml = (value / 250).roundToInt() * 250
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.goal_title)) },
        text = {
            Column {
                Text("${formatMl(ml)} ml", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Slider(value = value, onValueChange = { value = it }, valueRange = 1000f..5000f, steps = 15)
                Text(
                    stringResource(R.string.goal_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onPick(ml)
                onDismiss()
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun appVersion(context: android.content.Context): String {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return "${info.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)})"
}
