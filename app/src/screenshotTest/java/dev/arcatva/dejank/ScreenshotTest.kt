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
            "api" to "3", "version" to "v0.2.0", "build" to "4", "paused" to "0",
            "gpu" to "1", "gpu_state" to "on", "gpu_why" to "", "gpu_was" to "2",
            "reserve" to "1", "reserve_state" to "on", "reserve_now" to "300", "reserve_stock" to "73",
            "keep_why" to "", "mem_total_kb" to "11351040",
            "ui" to "1", "ui_state" to "on", "ui_kb" to "861184", "ui_min_kb" to "798720",
            "home" to "1", "home_state" to "on", "home_kb" to "824320", "home_min_kb" to "559104",
            "sf" to "0", "sf_state" to "off", "sf_kb" to "", "sf_min_kb" to "",
            "composer" to "0", "composer_state" to "off", "composer_kb" to "", "composer_min_kb" to "",
            "alloc" to "0", "alloc_state" to "off", "alloc_kb" to "", "alloc_min_kb" to "",
            "server" to "0", "server_state" to "off", "server_kb" to "", "server_min_kb" to "",
        )
        change.forEach { (k, v) -> map[k] = v }
        return parseStatus(map.entries.joinToString("\n") { "${it.key}=${it.value}" })!!
    }

    private val fresh = arrayOf(
        "gpu_state" to "off", "gpu_was" to "", "reserve_state" to "off", "reserve_now" to "73",
        "ui_state" to "off", "ui_kb" to "", "ui_min_kb" to "",
        "home_state" to "off", "home_kb" to "", "home_min_kb" to "",
    )

    /** Everything in the chain switched on, the way a phone looks a while after that. */
    private val all = arrayOf(
        "sf" to "1", "sf_state" to "on", "sf_kb" to "56320", "sf_min_kb" to "40960",
        "composer" to "1", "composer_state" to "on", "composer_kb" to "9216", "composer_min_kb" to "max",
        "alloc" to "1", "alloc_state" to "wait", "alloc_kb" to "", "alloc_min_kb" to "max",
        "server" to "1", "server_state" to "on", "server_kb" to "512000", "server_min_kb" to "430080",
    )

    private fun shoot(
        name: String,
        state: UiState,
        theme: ThemeChoice = ThemeChoice.DARK,
        fontScale: Float = 1f,
        open: Tile? = null,
        tap: String? = null,
        tapIndex: Int = 0,
    ) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AppTheme(theme) {
                    AppScaffold(
                        state = state,
                        actions = noActions,
                        onRetry = {},
                        theme = theme,
                        appVersion = "v0.2.0",
                        initialTile = open,
                    )
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
    @Config(qualifiers = "w412dp-h900dp-night-xhdpi")
    fun homeDark() = shoot("home_dark", ready())

    @Test
    @Config(qualifiers = "w412dp-h900dp-xhdpi")
    fun homeLight() = shoot("home_light", ready(), theme = ThemeChoice.LIGHT)

    @Test
    @Config(qualifiers = "zh-rCN-w412dp-h900dp-night-xhdpi")
    fun homeChinese() = shoot("home_zh", ready())

    @Test
    @Config(qualifiers = "w412dp-h1300dp-night-xhdpi")
    fun keepDetail() = shoot("keep_detail", ready(), open = Tile.KEEP)

    @Test
    @Config(qualifiers = "w412dp-h1300dp-night-xhdpi")
    fun keepDetailAllOn() = shoot("keep_detail_all_on", ready(*all), open = Tile.KEEP)

    @Test
    @Config(qualifiers = "zh-rCN-w412dp-h1300dp-night-xhdpi")
    fun keepDetailChinese() = shoot("keep_detail_zh", ready(*all), open = Tile.KEEP)

    @Test
    @Config(qualifiers = "w412dp-h800dp-night-xhdpi")
    fun gpuDetail() = shoot("gpu_detail", ready(), open = Tile.GPU)

    @Test
    @Config(qualifiers = "w412dp-h800dp-night-xhdpi")
    fun reserveDetail() = shoot("reserve_detail", ready(), open = Tile.RESERVE)

    /** An unfolded Fold: the tiles and one tile's details side by side. */
    @Test
    @Config(qualifiers = "w880dp-h1100dp-night-xhdpi")
    fun unfolded() = shoot("unfolded", ready(*all))

    /** Right after installing the zip: everything wanted, nothing in effect. */
    @Test
    @Config(qualifiers = "w412dp-h1000dp-night-xhdpi")
    fun freshInstall() = shoot("fresh_install", ready(*fresh))

    /** Another phone: no kernel module for it, and Android 17 keeps the processes in RAM itself. */
    @Test
    @Config(qualifiers = "w412dp-h900dp-night-xhdpi")
    fun otherPhone() = shoot("other_phone", ready(*official))

    @Test
    @Config(qualifiers = "w412dp-h1000dp-night-xhdpi")
    fun otherPhoneKeep() = shoot("other_phone_keep", ready(*official), open = Tile.KEEP)

    private val official = arrayOf(
        "gpu_state" to "na", "gpu_why" to "kernel", "gpu_series" to "android15-6.6", "gpu_was" to "",
        "keep_why" to "official",
        "ui_state" to "na", "home_state" to "na", "sf_state" to "na",
        "composer_state" to "na", "alloc_state" to "na", "server_state" to "na",
        "ui_kb" to "", "ui_min_kb" to "", "home_kb" to "", "home_min_kb" to "",
    )

    @Test
    @Config(qualifiers = "w412dp-h1000dp-night-xhdpi")
    fun paused() = shoot("paused", ready("paused" to "1", *fresh))

    /** A narrow cover screen with large text: nothing may overlap or be cut off. */
    @Test
    @Config(qualifiers = "w340dp-h1200dp-night-xhdpi")
    fun narrowLarge() = shoot("narrow_large", ready("gpu_state" to "err", "gpu_why" to "insmod", "gpu_was" to ""), fontScale = 1.3f)

    @Test
    @Config(qualifiers = "w340dp-h1900dp-night-xhdpi")
    fun narrowLargeKeep() = shoot("narrow_large_keep", ready(*all), fontScale = 1.3f, open = Tile.KEEP)

    @Test
    @Config(qualifiers = "w412dp-h1300dp-night-xhdpi")
    fun aTileOpensItsDetails() {
        shoot("tile_tapped", ready(), tap = "Kept in RAM")
        rule.onNodeWithText("System processes").assertExists()
        rule.onNodeWithText("system_server").assertExists()
    }

    /** Dialogs are windows of their own and are not in the picture, so this only checks that it opens. */
    @Test
    @Config(qualifiers = "w412dp-h1000dp-night-xhdpi")
    fun applyAsksBeforeRestarting() {
        shoot("apply_tapped", ready(*fresh), tap = "Apply")
        rule.onNodeWithText("Restart it now?").assertExists()
    }

    @Test
    @Config(qualifiers = "w412dp-h700dp-night-xhdpi")
    fun noRoot() = shoot("no_root", UiState(screen = Screen.NoRoot))

    @Test
    @Config(qualifiers = "w412dp-h700dp-night-xhdpi")
    fun noModule() = shoot("no_module", UiState(screen = Screen.NoModule))
}
