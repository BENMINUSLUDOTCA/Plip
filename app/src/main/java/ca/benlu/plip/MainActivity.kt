package ca.benlu.plip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ca.benlu.plip.ui.HomeScreen
import ca.benlu.plip.ui.PlipTheme
import ca.benlu.plip.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Plip.ensureScheduled(this)
        // Also runs after a language switch, which recreates the activity
        Plip.createChannel(this)
        Updater.createChannel(this)
        Plip.showStatus(this, Store.current)
        Plip.showStreak(this, Store.current)
        setContent { PlipRoot() }
    }

    override fun onResume() {
        super.onResume()
        // Picks up a new day and anything the notification buttons changed.
        Store.refresh()
        lifecycleScope.launch { runCatching { Updater.check(this@MainActivity) } }
    }
}

@Composable
private fun PlipRoot() {
    val state by Store.state.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(settingsOpen) { settingsOpen = false }

    PlipTheme(dynamicColor = state.settings.dynamicColor) {
        AnimatedContent(
            targetState = settingsOpen,
            transitionSpec = {
                val dir = if (targetState) 1 else -1
                (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { dir * it / 6 }) togetherWith
                    fadeOut(tween(120))
            },
            label = "screen",
        ) { open ->
            if (open) {
                SettingsScreen(state, onBack = { settingsOpen = false })
            } else {
                HomeScreen(state, onOpenSettings = { settingsOpen = true })
            }
        }
    }
}
