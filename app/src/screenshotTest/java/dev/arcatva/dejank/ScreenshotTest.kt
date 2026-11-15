package dev.arcatva.dejank

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real screens with made-up module state and saves them as PNG
 * files in build/screenshots. Only compiled with -Pscreenshots. This is a
 * render on the JVM, not a phone: fonts and the wallpaper palette differ.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val noActions = Actions(
        set = { _, _ -> },
        apply = {},
        resume = {},
        copyReport = {},
        openSource = {},
        setTheme = {},
    )

    private fun status(vararg change: Pair<String, String>): Status {
        val map = linkedMapOf(
            "api" to "2", "version" to "v0.1.0", "build" to "1", "paused" to "0",
            "gpu" to "1", "gpu_state" to "on", "gpu_why" to "", "gpu_was" to "2",
            "reserve" to "1", "reserve_state" to "on", "reserve_now" to "300", "reserve_stock" to "73",
            "pin" to "1", "pin_state" to "on", "pin_apps" to "2", "pin_in" to "2",
            "pin_kb" to "641292", "pin_swap_kb" to "0",
            "sys" to "0", "sys_state" to "off", "sys_in" to "0", "sys_kb" to "",
        )
        change.forEach { (k, v) -> map[k] = v }
        return parseStatus(map.entries.joinToString("\n") { "${it.key}=${it.value}" })!!
    }

    private val fresh = arrayOf(
        "gpu_state" to "off", "gpu_was" to "", "reserve_state" to "off", "reserve_now" to "73",
        "pin_state" to "off", "pin_apps" to "0", "pin_in" to "0", "pin_kb" to "", "pin_swap_kb" to "",
    )

    private fun shoot(
        name: String,
        state: UiState,
        theme: ThemeChoice = ThemeChoice.DARK,
        fontScale: Float = 1f,
        tap: String? = null,
        tapIndex: Int = 0,
    ) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AppTheme(theme) {
                    AppScaffold(state = state, actions = noActions, onRetry = {}, theme = theme, appVersion = "v0.1.0")
                }
            }
        }
        rule.waitForIdle()
        if (tap != null) {
            rule.onAllNodesWithText(tap)[tapIndex].performClick()
            rule.waitForIdle()
        }
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun ready(vararg change: Pair<String, String>) = UiState(screen = Screen.Ready(status(*change)))

    @Test
    @Config(qualifiers = "w412dp-h1500dp-night-xhdpi")
    fun allOnDark() = shoot("all_on_dark", ready())

    @Test
    @Config(qualifiers = "w412dp-h1500dp-night-xhdpi")
    fun systemKept() = shoot(
        "system_kept",
        ready("sys" to "1", "sys_state" to "on", "sys_in" to "1", "sys_kb" to "421888", "pin_kb" to "1685504"),
    )

    @Test
    @Config(qualifiers = "w412dp-h1500dp-xhdpi")
    fun allOnLight() = shoot("all_on_light", ready(), theme = ThemeChoice.LIGHT)

    @Test
    @Config(qualifiers = "w880dp-h800dp-night-xhdpi")
    fun unfolded() = shoot("unfolded", ready())

    /** Right after installing the zip: everything wanted, nothing in effect. */
    @Test
    @Config(qualifiers = "w412dp-h1600dp-night-xhdpi")
    fun freshInstall() = shoot("fresh_install", ready(*fresh))

    /** Another phone: no kernel module for it, and one fix switched off. */
    @Test
    @Config(qualifiers = "w412dp-h1500dp-night-xhdpi")
    fun otherPhone() = shoot(
        "other_phone",
        ready(
            "gpu_state" to "na", "gpu_why" to "kernel", "gpu_series" to "android15-6.6", "gpu_was" to "",
            "pin" to "0", "pin_state" to "off",
        ),
    )

    @Test
    @Config(qualifiers = "w412dp-h1600dp-night-xhdpi")
    fun paused() = shoot("paused", ready("paused" to "1", *fresh))

    /** A narrow cover screen with large text: nothing may overlap or be cut off. */
    @Test
    @Config(qualifiers = "w340dp-h2300dp-night-xhdpi")
    fun narrowLarge() = shoot("narrow_large", ready("gpu_state" to "err", "gpu_why" to "insmod", "gpu_was" to ""), fontScale = 1.3f)

    /** Dialogs are windows of their own and are not in the picture, so these two only check that they open. */
    @Test
    @Config(qualifiers = "w412dp-h1500dp-night-xhdpi")
    fun helpOpens() {
        shoot("help_tapped", ready(), tap = "?", tapIndex = 2)
        rule.onNodeWithText("OK").assertExists()
    }

    @Test
    @Config(qualifiers = "w412dp-h1500dp-night-xhdpi")
    fun applyAsksBeforeRestarting() {
        shoot("apply_tapped", ready(*fresh), tap = "Apply")
        rule.onNodeWithText("Restart System UI and Home?").assertExists()
    }

    @Test
    @Config(qualifiers = "w412dp-h700dp-night-xhdpi")
    fun noRoot() = shoot("no_root", UiState(screen = Screen.NoRoot))

    @Test
    @Config(qualifiers = "w412dp-h700dp-night-xhdpi")
    fun noModule() = shoot("no_module", UiState(screen = Screen.NoModule))
}
