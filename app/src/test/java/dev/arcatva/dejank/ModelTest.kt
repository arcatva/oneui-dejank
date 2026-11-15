package dev.arcatva.dejank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
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

    @Test
    fun `text that is not a status is rejected`() {
        assertNull(parseStatus(""))
        assertNull(parseStatus("sh: dejank.sh: not found"))
    }

    @Test
    fun `sizes read the way people say them`() {
        assertEquals("780 MB", size(798720))
        assertEquals("1.3 GB", size(1357824))
        assertEquals("10.8 GB", size(11351040))
        assertEquals("0 MB", size(0))
    }

    @Test
    fun `everything that is on by default is working`() {
        val s = status()
        assertEquals(3, s.working)
        assertEquals(0, s.switchedOff)
        assertFalse(s.anyPending)
        assertEquals(lineOf(R.string.gpu_on, 2), s.line(Fix.GPU))
        assertEquals(lineOf(R.string.reserve_on, 73, 300), s.line(Fix.RESERVE))
        assertEquals(lineOf(R.string.gpu_short_on), s.short(Tile.GPU))
        assertEquals(lineOf(R.string.reserve_short_on, 300), s.short(Tile.RESERVE))
        // the floors of System UI and the home app: 780 MB and 546 MB
        assertEquals(1357824L, s.keptKb)
        assertEquals(2, s.keptCount)
        assertEquals(lineOf(R.string.keep_short_on, "1.3 GB"), s.short(Tile.KEEP))
        assertEquals(lineOf(R.string.kept_on, "780 MB"), s.line(Keep.UI))
        assertEquals(Tone.GOOD, s.tone(Keep.UI))
        assertEquals(lineOf(R.string.off), s.line(Keep.SERVER))
        assertEquals(Tone.IDLE, s.tone(Keep.SERVER))
    }

    @Test
    fun `right after installing nothing is in effect`() {
        val s = status(*fresh)
        assertEquals(0, s.working)
        // switched on and waiting is not the same as switched off
        assertEquals(0, s.switchedOff)
        assertTrue(s.nothingApplied)
        assertTrue(s.anyPending)
        assertTrue(s.applyRestarts)
        Fix.entries.forEach {
            assertEquals(lineOf(R.string.not_applied), s.line(it))
            assertEquals(Tone.WAITING, s.tone(it))
        }
        assertEquals(lineOf(R.string.not_applied), s.line(Keep.HOME))
        assertEquals(lineOf(R.string.not_applied), s.short(Tile.KEEP))
        assertEquals(Tone.WAITING, s.tone(Tile.KEEP))
        // what is switched off is not waiting for anything
        assertFalse(s.pending(Keep.SF))
    }

    @Test
    fun `a system process is moved, so applying it restarts nothing`() {
        val s = status(*fresh, "ui" to "0", "home" to "0", "server" to "1")
        assertTrue(s.anyPending)
        assertFalse(s.applyRestarts)
    }

    @Test
    fun `a process that has only just arrived is kept whole`() {
        val s = status("ui_min_kb" to "max")
        assertEquals(lineOf(R.string.kept_settling, "841 MB"), s.line(Keep.UI))
        assertEquals(861184L + 559104L, s.keptKb)
        val waiting = status("home_state" to "wait", "home_kb" to "", "home_min_kb" to "max")
        assertEquals(lineOf(R.string.kept_wait), waiting.line(Keep.HOME))
        assertEquals(Tone.WAITING, waiting.tone(Keep.HOME))
        assertEquals(2, waiting.keptCount)
    }

    @Test
    fun `a phone without one of the processes`() {
        val s = status("composer_state" to "none")
        assertEquals(lineOf(R.string.kept_none), s.line(Keep.COMPOSER))
        assertFalse(s.pending(Keep.COMPOSER))
    }

    @Test
    fun `Android that keeps them in RAM itself`() {
        val s = status(
            "keep_why" to "official",
            "ui_state" to "na", "home_state" to "na", "sf_state" to "na",
            "composer_state" to "na", "alloc_state" to "na", "server_state" to "na",
            "ui_kb" to "", "ui_min_kb" to "", "home_kb" to "", "home_min_kb" to "",
        )
        assertEquals(lineOf(R.string.keep_na_official), s.short(Tile.KEEP))
        assertEquals(lineOf(R.string.not_available), s.line(Keep.UI))
        assertTrue(s.unavailable(Tile.KEEP))
        assertFalse(s.anyPending)
        // neither working nor off: the other two fixes are all there is
        assertEquals(2, s.working)
        assertEquals(0, s.switchedOff)
        assertEquals(
            lineOf(R.string.keep_na_controller),
            status("keep_why" to "controller", "ui_state" to "na").short(Tile.KEEP),
        )
    }

    @Test
    fun `why the GPU fix is not there`() {
        assertEquals(lineOf(R.string.gpu_na_driver), status("gpu_state" to "na", "gpu_why" to "driver").line(Fix.GPU))
        assertEquals(lineOf(R.string.gpu_na_setting), status("gpu_state" to "na", "gpu_why" to "setting").line(Fix.GPU))
        assertEquals(
            lineOf(R.string.gpu_na_series, "android15-6.6"),
            status("gpu_state" to "na", "gpu_why" to "kernel", "gpu_series" to "android15-6.6").line(Fix.GPU),
        )
        assertEquals(lineOf(R.string.gpu_na_kernel), status("gpu_state" to "na", "gpu_why" to "kernel").line(Fix.GPU))
        val refused = status("gpu_state" to "err", "gpu_why" to "insmod")
        assertEquals(lineOf(R.string.gpu_err), refused.line(Fix.GPU))
        assertEquals(Tone.BAD, refused.tone(Tile.GPU))
        assertEquals(lineOf(R.string.gpu_on_never), status("gpu_was" to "0").line(Fix.GPU))
        assertEquals(lineOf(R.string.in_effect), status("gpu_was" to "").line(Fix.GPU))
    }

    @Test
    fun `a reserve the phone already had`() {
        assertEquals(
            lineOf(R.string.reserve_on_plain, 400),
            status("reserve_now" to "400", "reserve_stock" to "400").line(Fix.RESERVE),
        )
        assertEquals(lineOf(R.string.reserve_na), status("reserve_state" to "na").line(Fix.RESERVE))
    }

    @Test
    fun `switched off is off, not pending`() {
        val s = status("gpu" to "0", "gpu_state" to "off", "gpu_was" to "")
        assertEquals(lineOf(R.string.off), s.line(Fix.GPU))
        assertEquals(Tone.IDLE, s.tone(Fix.GPU))
        assertFalse(s.anyPending)
        assertFalse(s.nothingApplied)
        assertEquals(2, s.working)
        assertEquals(1, s.switchedOff)
        // a fix the kernel refused is neither working nor switched off
        assertEquals(0, status("gpu_state" to "err", "gpu_why" to "insmod").switchedOff)
        // nothing kept in RAM because every switch is off
        val none = status("ui" to "0", "ui_state" to "off", "home" to "0", "home_state" to "off")
        assertTrue(none.switchedOff(Tile.KEEP))
        assertEquals(lineOf(R.string.off), none.short(Tile.KEEP))
    }

    @Test
    fun `paused comes before everything else`() {
        val s = status("paused" to "1", *fresh)
        assertTrue(s.paused)
        assertFalse(s.anyPending)
    }

    @Test
    fun `an unknown theme falls back to dark`() {
        assertEquals(ThemeChoice.DARK, ThemeChoice.parse(null))
        assertEquals(ThemeChoice.DARK, ThemeChoice.parse("PURPLE"))
        assertEquals(ThemeChoice.LIGHT, ThemeChoice.parse("LIGHT"))
    }
}
