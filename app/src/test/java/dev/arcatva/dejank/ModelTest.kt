package dev.arcatva.dejank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    private fun status(vararg change: Pair<String, String>): Status {
        val map = linkedMapOf(
            "api" to "6", "version" to "v0.2.0", "build" to "4", "paused" to "0",
            "gpu" to "1", "gpu_state" to "on", "gpu_why" to "", "gpu_was" to "2",
            "kvmalloc" to "0", "kvmalloc_state" to "off", "kvmalloc_why" to "", "kvmalloc_seen" to "", "kvmalloc_changed" to "",
            "reserve" to "1", "reserve_state" to "on", "reserve_now" to "300", "reserve_stock" to "73",
            "shaderc" to "0", "shaderc_state" to "off", "shaderc_why" to "", "shaderc_kb" to "",
            "keep_why" to "", "mem_total_kb" to "11351040", "keep_cap_kb" to "1135100",
            "ui" to "1", "ui_state" to "on", "ui_kb" to "861184", "ui_min_kb" to "798720",
            "home" to "1", "home_state" to "on", "home_kb" to "824320", "home_min_kb" to "559104",
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
    fun `the shader compiler is a switch among what is kept`() {
        val off = status()
        assertNull(off.shadercValue())
        assertEquals(Tone.IDLE, off.tone(Fix.SHADERC))
        assertFalse(off.anyPending)

        val on = status("shaderc" to "1", "shaderc_state" to "on", "shaderc_kb" to "23120")
        assertEquals(lineOf(R.string.plain, "23 MB"), on.shadercValue())
        assertEquals(Tone.GOOD, on.tone(Fix.SHADERC))
        // the tile goes on counting the processes
        assertEquals(countOf(R.plurals.keep_short, 2, "1.3 GB"), on.short(Tile.KEEP))

        // held while no process is kept: the tile is on all the same
        val alone = status("shaderc" to "1", "shaderc_state" to "on", "shaderc_kb" to "23120", *fresh)
        assertTrue(alone.on(Tile.KEEP))
        assertEquals(lineOf(R.string.on), alone.short(Tile.KEEP))
        assertEquals(Tone.GOOD, alone.tone(Tile.KEEP))
        assertEquals(0L, alone.keptKb)

        val wanted = status("shaderc" to "1")
        assertEquals(lineOf(R.string.not_applied), wanted.shadercValue())
        assertEquals(Tone.WAITING, wanted.tone(Fix.SHADERC))
        assertTrue(wanted.anyPending)

        val other = status("shaderc_state" to "na", "shaderc_why" to "file")
        assertEquals(lineOf(R.string.kept_none), other.shadercValue())
        assertEquals(Tone.IDLE, other.tone(Fix.SHADERC))
        assertEquals(lineOf(R.string.not_supported), status("shaderc_state" to "na", "shaderc_why" to "build").shadercValue())

        val failed = status("shaderc" to "1", "shaderc_state" to "err", "shaderc_why" to "start")
        assertEquals(lineOf(R.string.failed), failed.shadercValue())
        assertEquals(Tone.BAD, failed.tone(Fix.SHADERC))
    }

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
        assertFalse(s.anyPending)
        // on and working: nothing to add to the switch
        Fix.entries.forEach { assertNull(s.note(it)) }
        assertEquals(lineOf(R.string.on), s.short(Tile.ALLOC))
        assertEquals(lineOf(R.string.on), s.short(Tile.RESERVE))
        // the floors of System UI and the home app: 780 MB and 546 MB
        assertEquals(1357824L, s.keptKb)
        assertEquals(2, s.keptCount)
        assertEquals(countOf(R.plurals.keep_short, 2, "1.3 GB"), s.short(Tile.KEEP))
        assertEquals(lineOf(R.string.plain, "780 MB"), s.value(Keep.UI))
        assertEquals(Tone.GOOD, s.tone(Keep.UI))
        assertNull(s.value(Keep.SERVER))
        assertEquals(Tone.IDLE, s.tone(Keep.SERVER))
    }

    @Test
    fun `a plural carries its count as the first argument`() {
        val line = countOf(R.plurals.keep_short, 1, "780 MB")
        assertEquals(1, line.quantity)
        assertEquals(listOf<Any>(1, "780 MB"), line.args)
    }

    @Test
    fun `right after installing nothing is in effect`() {
        val s = status(*fresh)
        assertEquals(0, s.working)
        assertTrue(s.anyPending)
        assertTrue(s.applyRestarts)
        listOf(Fix.GPU, Fix.RESERVE).forEach {
            assertEquals(lineOf(R.string.not_applied), s.note(it))
            assertEquals(Tone.WAITING, s.tone(it))
        }
        // off unless someone switches it on
        assertNull(s.note(Fix.KVMALLOC))
        assertEquals(Tone.IDLE, s.tone(Fix.KVMALLOC))
        assertEquals(lineOf(R.string.not_applied), s.short(Tile.ALLOC))
        assertEquals(lineOf(R.string.not_applied), s.value(Keep.HOME))
        assertEquals(lineOf(R.string.not_applied), s.short(Tile.KEEP))
        assertEquals(Tone.WAITING, s.tone(Tile.KEEP))
        // what is switched off is not waiting for anything
        assertFalse(s.pending(Keep.SERVER))
        assertNull(s.value(Keep.SERVER))
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
        assertEquals(lineOf(R.string.plain, "841 MB"), s.value(Keep.UI))
        assertEquals(861184L + 559104L, s.keptKb)
        val waiting = status("home_state" to "wait", "home_kb" to "", "home_min_kb" to "max")
        assertEquals(lineOf(R.string.kept_wait), waiting.value(Keep.HOME))
        assertEquals(Tone.WAITING, waiting.tone(Keep.HOME))
        assertEquals(2, waiting.keptCount)
    }

    @Test
    fun `a phone without one of the processes`() {
        val s = status("server_state" to "none")
        assertEquals(lineOf(R.string.kept_none), s.value(Keep.SERVER))
        assertFalse(s.pending(Keep.SERVER))
    }

    @Test
    fun `Android that keeps them in RAM itself`() {
        val s = status(
            "keep_why" to "official",
            "ui_state" to "na", "home_state" to "na", "server_state" to "na",
            "ui_kb" to "", "ui_min_kb" to "", "home_kb" to "", "home_min_kb" to "",
        )
        assertEquals(lineOf(R.string.built_in), s.short(Tile.KEEP))
        assertEquals(lineOf(R.string.keep_na_official), s.keepReason())
        // why is said once above the switches, not six times beside them
        assertNull(s.value(Keep.UI))
        assertFalse(s.anyPending)
        assertEquals(2, s.working)
        val old = status("keep_why" to "controller", "ui_state" to "na")
        assertEquals(lineOf(R.string.not_supported), old.short(Tile.KEEP))
        assertEquals(lineOf(R.string.keep_na_controller), old.keepReason())
    }

    @Test
    fun `why the GPU fix is not there`() {
        val never = status("gpu_state" to "na", "gpu_why" to "setting")
        assertEquals(lineOf(R.string.gpu_na_setting), never.note(Fix.GPU))
        assertEquals(lineOf(R.string.not_needed), never.short(Tile.ALLOC))
        val other = status("gpu_state" to "na", "gpu_why" to "driver")
        assertEquals(lineOf(R.string.gpu_na_driver), other.note(Fix.GPU))
        assertEquals(lineOf(R.string.not_supported), other.short(Tile.ALLOC))
        assertEquals(
            lineOf(R.string.gpu_na_series, "android15-6.6"),
            status("gpu_state" to "na", "gpu_why" to "kernel", "gpu_series" to "android15-6.6").note(Fix.GPU),
        )
        assertEquals(lineOf(R.string.gpu_na_kernel), status("gpu_state" to "na", "gpu_why" to "kernel").note(Fix.GPU))
        val refused = status("gpu_state" to "err", "gpu_why" to "insmod")
        assertEquals(lineOf(R.string.gpu_err), refused.note(Fix.GPU))
        assertEquals(lineOf(R.string.failed), refused.short(Tile.ALLOC))
        assertEquals(Tone.BAD, refused.tone(Tile.ALLOC))
        assertEquals(2, refused.working)
    }

    @Test
    fun `the hook into kvmalloc is a second switch of the allocation tile`() {
        assertEquals(listOf(Fix.GPU, Fix.KVMALLOC), Tile.ALLOC.fixes)
        val on = status("kvmalloc" to "1", "kvmalloc_state" to "on", "kvmalloc_seen" to "1300", "kvmalloc_changed" to "1234")
        assertNull(on.note(Fix.KVMALLOC))
        assertEquals(Tone.GOOD, on.tone(Fix.KVMALLOC))
        // a tile is one of three however many of its switches are on
        assertEquals(3, on.working)
        val alone = status("gpu" to "0", "gpu_state" to "off", "kvmalloc" to "1", "kvmalloc_state" to "on")
        assertEquals(lineOf(R.string.on), alone.short(Tile.ALLOC))
        assertEquals(Tone.GOOD, alone.tone(Tile.ALLOC))
        val wanted = status("kvmalloc" to "1")
        assertEquals(lineOf(R.string.not_applied), wanted.note(Fix.KVMALLOC))
        assertTrue(wanted.anyPending)
    }

    @Test
    fun `why the hook into kvmalloc is not there`() {
        val newer = status("kvmalloc_state" to "na", "kvmalloc_why" to "builtin")
        assertEquals(lineOf(R.string.kvmalloc_na_builtin), newer.note(Fix.KVMALLOC))
        val older = status("kvmalloc_state" to "na", "kvmalloc_why" to "kernel", "gpu_series" to "android15-6.6")
        assertEquals(lineOf(R.string.gpu_na_series, "android15-6.6"), older.note(Fix.KVMALLOC))
        val bare = status("kvmalloc_state" to "na", "kvmalloc_why" to "hook")
        assertEquals(lineOf(R.string.reserve_na), bare.note(Fix.KVMALLOC))
        // none of this is the tile's business while its first fix works
        assertEquals(lineOf(R.string.on), bare.short(Tile.ALLOC))
        val refused = status("kvmalloc" to "1", "kvmalloc_state" to "err", "kvmalloc_why" to "insmod")
        assertEquals(lineOf(R.string.gpu_err), refused.note(Fix.KVMALLOC))
        assertEquals(lineOf(R.string.failed), refused.short(Tile.ALLOC))
        assertEquals(Tone.BAD, refused.tone(Tile.ALLOC))
        // the phone went down after the switch: it is off again and says why, the tile carries on
        val tripped = status("kvmalloc" to "0", "kvmalloc_state" to "err", "kvmalloc_why" to "reboot")
        assertEquals(lineOf(R.string.kvmalloc_err_reboot), tripped.note(Fix.KVMALLOC))
        assertEquals(Tone.BAD, tripped.tone(Fix.KVMALLOC))
        assertEquals(lineOf(R.string.on), tripped.short(Tile.ALLOC))
        assertFalse(tripped.anyPending)
    }

    @Test
    fun `a kernel without the reserve setting`() {
        val s = status("reserve_state" to "na")
        assertEquals(lineOf(R.string.reserve_na), s.note(Fix.RESERVE))
        assertEquals(lineOf(R.string.not_supported), s.short(Tile.RESERVE))
    }

    @Test
    fun `switched off is off, not pending`() {
        val s = status("gpu" to "0", "gpu_state" to "off", "gpu_was" to "")
        assertNull(s.note(Fix.GPU))
        assertEquals(lineOf(R.string.off), s.short(Tile.ALLOC))
        assertEquals(Tone.IDLE, s.tone(Fix.GPU))
        assertFalse(s.anyPending)
        assertEquals(2, s.working)
        // nothing kept in RAM because every switch is off
        val none = status("ui" to "0", "ui_state" to "off", "home" to "0", "home_state" to "off")
        assertEquals(lineOf(R.string.off), none.short(Tile.KEEP))
        assertEquals(2, none.working)
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
