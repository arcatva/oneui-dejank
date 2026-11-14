package dev.arcatva.dejank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    private fun status(vararg change: Pair<String, String>): Status {
        val map = linkedMapOf(
            "api" to "1", "version" to "v0.1.0", "build" to "1", "paused" to "0",
            "gpu" to "1", "gpu_state" to "on", "gpu_why" to "", "gpu_was" to "2",
            "reserve" to "1", "reserve_state" to "on", "reserve_now" to "300", "reserve_stock" to "73",
            "pin" to "1", "pin_state" to "on", "pin_apps" to "2", "pin_in" to "2",
            "pin_kb" to "641292", "pin_swap_kb" to "0",
        )
        change.forEach { (k, v) -> map[k] = v }
        return parseStatus(map.entries.joinToString("\n") { "${it.key}=${it.value}" })!!
    }

    @Test
    fun `text that is not a status is rejected`() {
        assertNull(parseStatus(""))
        assertNull(parseStatus("sh: dejank.sh: not found"))
    }

    @Test
    fun `everything on`() {
        val s = status()
        assertEquals("3 of 3 fixes active", s.headline())
        assertFalse(s.anyPending)
        assertEquals("Active · driver threshold 2 → 0", s.line(Fix.GPU))
        assertEquals("Active · scale 73 → 300", s.line(Fix.RESERVE))
        assertEquals("2 of 2 kept · 626 MB in RAM", s.line(Fix.PIN))
        assertEquals(Tone.GOOD, s.tone(Fix.PIN))
    }

    @Test
    fun `right after installing nothing is applied`() {
        val s = status("gpu_state" to "off", "gpu_was" to "", "reserve_state" to "off", "reserve_now" to "73", "pin_state" to "off")
        assertEquals("0 of 3 fixes active", s.headline())
        assertTrue(s.anyPending)
        Fix.entries.forEach {
            assertEquals("Not applied", s.line(it))
            assertEquals(Tone.WAITING, s.tone(it))
        }
    }

    @Test
    fun `a fix that is switched off is off, not pending`() {
        val s = status("gpu" to "0", "gpu_state" to "off")
        assertEquals("Off", s.line(Fix.GPU))
        assertEquals(Tone.IDLE, s.tone(Fix.GPU))
        assertFalse(s.anyPending)
        assertEquals("2 of 3 fixes active", s.headline())
    }

    @Test
    fun `why the gpu fix cannot work`() {
        assertEquals("No build for this kernel", status("gpu_state" to "na", "gpu_why" to "kernel").line(Fix.GPU))
        assertEquals(
            "No build for android15-6.6 yet",
            status("gpu_state" to "na", "gpu_why" to "kernel", "gpu_series" to "android15-6.6").line(Fix.GPU),
        )
        assertEquals("No Adreno driver on this device", status("gpu_state" to "na", "gpu_why" to "driver").line(Fix.GPU))
        assertEquals("Not needed: this driver never waits", status("gpu_state" to "na", "gpu_why" to "setting").line(Fix.GPU))
        val refused = status("gpu_state" to "err", "gpu_why" to "insmod")
        assertEquals("The kernel refused the module", refused.line(Fix.GPU))
        assertEquals(Tone.BAD, refused.tone(Fix.GPU))
        assertFalse(refused.anyPending)
    }

    @Test
    fun `a driver that never waited`() {
        assertEquals("Active · this driver never waited", status("gpu_was" to "0").line(Fix.GPU))
    }

    @Test
    fun `a phone that already keeps the larger reserve`() {
        assertEquals("Active · scale 400", status("reserve_now" to "400", "reserve_stock" to "400").line(Fix.RESERVE))
        assertEquals("Not available on this kernel", status("reserve_state" to "na").line(Fix.RESERVE))
    }

    @Test
    fun `pin shows strays and anything compressed`() {
        assertEquals("1 of 2 kept · 626 MB in RAM", status("pin_in" to "1").line(Fix.PIN))
        assertEquals(
            "2 of 2 kept · 626 MB in RAM, 20 MB compressed",
            status("pin_swap_kb" to "20480").line(Fix.PIN),
        )
        assertEquals("No memory controller on this kernel", status("pin_state" to "na").line(Fix.PIN))
    }

    @Test
    fun `paused comes before everything else`() {
        val s = status("paused" to "1", "gpu_state" to "off", "reserve_state" to "off", "pin_state" to "off")
        assertEquals("Paused", s.headline())
        assertFalse(s.anyPending)
    }

    @Test
    fun `megabytes are rounded`() {
        assertEquals("1 MB", megabytes(1024))
        assertEquals("626 MB", megabytes(641292))
    }

    @Test
    fun `the saved theme, dark when nothing is saved`() {
        assertEquals(ThemeChoice.DARK, ThemeChoice.parse(null))
        assertEquals(ThemeChoice.LIGHT, ThemeChoice.parse("LIGHT"))
        assertEquals(ThemeChoice.DARK, ThemeChoice.parse("nonsense"))
    }
}
