@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.arcatva.dejank

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

const val SOURCE_URL = "https://github.com/arcatva/oneui-dejank"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getPreferences(MODE_PRIVATE)
        setContent {
            var theme by remember { mutableStateOf(ThemeChoice.parse(prefs.getString(THEME_KEY, null))) }
            // The bar icons have to contrast with the app, not with the system setting.
            val dark = theme.isDark()
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            AppTheme(theme) {
                App(
                    theme = theme,
                    onTheme = {
                        theme = it
                        prefs.edit().putString(THEME_KEY, it.name).apply()
                    },
                )
            }
        }
    }

    private companion object {
        const val THEME_KEY = "theme"
    }
}

@Composable
fun ThemeChoice.isDark(): Boolean = when (this) {
    ThemeChoice.DARK -> true
    ThemeChoice.LIGHT -> false
    ThemeChoice.SYSTEM -> isSystemInDarkTheme()
}

/** Material 3 Expressive with the system's wallpaper colours; dark unless chosen otherwise. */
@Composable
fun AppTheme(choice: ThemeChoice = ThemeChoice.DARK, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (choice.isDark()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

/** What the screen can ask for; keeps the composables free of the view model. */
class Actions(
    val set: (Fix, Boolean) -> Unit,
    val apply: () -> Unit,
    val resume: () -> Unit,
    val copyReport: () -> Unit,
    val openSource: () -> Unit,
    val setTheme: (ThemeChoice) -> Unit,
)

@Composable
fun App(theme: ThemeChoice, onTheme: (ThemeChoice) -> Unit, vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val appVersion = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName?.let { "v$it" }
        }.getOrNull().orEmpty()
    }
    val actions = remember(vm, onTheme, context) {
        Actions(
            set = vm::set,
            apply = vm::apply,
            resume = vm::resume,
            copyReport = { vm.report { copyText(context, it) } },
            openSource = { openUrl(context, SOURCE_URL) },
            setTheme = onTheme,
        )
    }

    // Poll only while the screen is in front.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refresh()
                delay(5000)
            }
        }
    }
    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    AppScaffold(
        state = state,
        actions = actions,
        onRetry = vm::retry,
        theme = theme,
        snackbar = snackbar,
        appVersion = appVersion,
    )
}

/** The whole screen for a given state; no view model, so it can be rendered in tests. */
@Composable
fun AppScaffold(
    state: UiState,
    actions: Actions,
    onRetry: () -> Unit,
    theme: ThemeChoice = ThemeChoice.DARK,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    appVersion: String = "",
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val status = (state.screen as? Screen.Ready)?.status
    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_FROM_DP
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("One UI Dejank") },
                subtitle = { Text(status?.headline() ?: "Memory fixes for animation stutter") },
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val screen = state.screen) {
            Screen.Loading -> LoadingScreen(padding)
            Screen.NoRoot -> MessageScreen(
                padding = padding,
                title = "No root access",
                body = "Allow Dejank in your root manager's superuser list, then retry.",
                onRetry = onRetry,
            )
            Screen.NoModule -> MessageScreen(
                padding = padding,
                title = "Module not found",
                body = "Install the One UI Dejank zip in your root manager. This app is its control panel.",
                onRetry = onRetry,
            )
            is Screen.Broken -> MessageScreen(
                padding = padding,
                title = "The module didn't answer",
                body = screen.detail.ifBlank { "The control script returned no status." },
                onRetry = onRetry,
            )
            is Screen.Ready -> ReadyScreen(
                status = screen.status,
                busy = state.busy,
                theme = theme,
                wide = wide,
                actions = actions,
                appVersion = appVersion,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

/** Width from which the screen counts as wide: an unfolded Fold, a tablet. */
const val WIDE_FROM_DP = 600

private fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("One UI Dejank report", text))
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
