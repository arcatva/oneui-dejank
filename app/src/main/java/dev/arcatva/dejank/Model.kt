package dev.arcatva.dejank

/** The version of dejank.sh's status output this app understands. */
const val EXPECTED_API = 2

enum class ThemeChoice(val label: String) {
    DARK("Dark"),
    SYSTEM("System"),
    LIGHT("Light"),
    ;

    companion object {
        /** The saved name, or [DARK] for nothing saved or anything unknown. */
        fun parse(name: String?): ThemeChoice = entries.firstOrNull { it.name == name } ?: DARK
    }
}

enum class FixState {
    ON,
    OFF,

    /** Cannot work on this device. */
    NA,

    /** Was tried and failed. */
    ERR,
    ;

    companion object {
        fun parse(text: String?): FixState = when (text) {
            "on" -> ON
            "na" -> NA
            "err" -> ERR
            else -> OFF
        }
    }
}

/** How a state line is coloured. */
enum class Tone { GOOD, IDLE, WAITING, BAD }

/** The fixes. [key] is the name dejank.sh knows each by. */
enum class Fix(val key: String, val title: String, val summary: String, val help: String) {
    GPU(
        "gpu",
        "No-wait GPU memory",
        "The graphics driver takes a smaller block of memory instead of stalling for a large one.",
        "When large free blocks run out, the Adreno driver makes the kernel free memory on the spot. " +
            "The thread drawing the frame waits, often for tens of milliseconds.\n\n" +
            "This loads a small kernel module that sets the driver's retry threshold to 0, the driver's " +
            "own value for \"don't wait\". Switching it off puts the old value back.\n\n" +
            "Cost: GPU memory is built from smaller blocks more often.",
    ),
    RESERVE(
        "reserve",
        "More free memory",
        "Memory is freed in the background earlier, so apps rarely have to wait for it.",
        "Android keeps a small reserve of free memory. When an app uses it up faster than the " +
            "background reclaimer refills it, the app waits while memory is freed.\n\n" +
            "This raises vm.watermark_scale_factor to 300: reclaiming starts at about 3% of RAM free " +
            "instead of under 1%.\n\n" +
            "Cost: that memory is not used for cache or background apps.",
    ),
    PIN(
        "pin",
        "Keep the UI in RAM",
        "System UI and Home are never compressed or dropped, so the panel and home screen don't reload.",
        "Under memory pressure Android compresses or drops parts of System UI and the launcher like " +
            "any other app. Opening the panel or going home then waits while they are read back.\n\n" +
            "This moves both into a memory group the kernel may not reclaim or swap. They restart once " +
            "so that all their memory starts inside the group.\n\n" +
            "Cost: their memory is never freed for other apps. The line below shows how much that is.",
    ),
    SYS(
        "sys",
        "Keep the system in RAM",
        "Android's system process keeps what it reads in RAM, so opening and switching apps doesn't wait for it.",
        "Every app launch and switch goes through system_server. Under memory pressure parts of it are " +
            "dropped, and it reads them back from storage while the app drawing the frame waits for it.\n\n" +
            "This moves system_server into a second memory group the kernel may not reclaim or swap. It " +
            "cannot be restarted, so only what it reads from then on is protected.\n\n" +
            "Cost: that memory is never freed for other apps. New and still being measured, so it is off " +
            "until you switch it on.",
    ),
}

/** What `dejank.sh status` reported. */
data class Status(
    val api: Int,
    val version: String,
    val build: Int,
    /** The last start did not finish, so the module applies nothing until resumed. */
    val paused: Boolean,
    val wanted: Map<Fix, Boolean>,
    val states: Map<Fix, FixState>,
    val gpuWhy: String = "",
    /** The kernel series of this phone, e.g. android16-6.12; empty outside the series. */
    val gpuSeries: String = "",
    /** The driver's own retry threshold, known once the module is loaded. */
    val gpuWas: Int? = null,
    val reserveNow: Int? = null,
    val reserveStock: Int? = null,
    /** System UI and Home processes running, and how many of them are in the group. */
    val pinApps: Int = 0,
    val pinIn: Int = 0,
    val pinKb: Long? = null,
    val pinSwapKb: Long? = null,
    /** system_server is in its group. */
    val sysIn: Boolean = false,
    val sysKb: Long? = null,
) {
    fun wanted(fix: Fix): Boolean = wanted[fix] == true

    fun state(fix: Fix): FixState = states[fix] ?: FixState.OFF

    /** Switched on but not in effect, as after installing or after a reboot the module did not see. */
    fun pending(fix: Fix): Boolean = wanted(fix) && state(fix) == FixState.OFF

    val anyPending: Boolean get() = !paused && Fix.entries.any { pending(it) }

    val active: Int get() = Fix.entries.count { state(it) == FixState.ON }

    fun headline(): String = when {
        paused -> "Paused"
        else -> "$active of ${Fix.entries.size} fixes active"
    }

    fun tone(fix: Fix): Tone = when (state(fix)) {
        FixState.ON -> Tone.GOOD
        FixState.ERR -> Tone.BAD
        FixState.NA -> Tone.IDLE
        FixState.OFF -> if (wanted(fix)) Tone.WAITING else Tone.IDLE
    }

    /** The state of one fix in a few words, with the numbers that show it. */
    fun line(fix: Fix): String {
        val state = state(fix)
        if (state == FixState.OFF) return if (wanted(fix)) "Not applied" else "Off"
        return when (fix) {
            Fix.GPU -> when (state) {
                FixState.ON -> when {
                    gpuWas == 0 -> "Active · this driver never waited"
                    gpuWas != null -> "Active · driver threshold $gpuWas → 0"
                    else -> "Active"
                }
                FixState.NA -> when (gpuWhy) {
                    "driver" -> "No Adreno driver on this device"
                    "setting" -> "Not needed: this driver never waits"
                    else -> if (gpuSeries.isEmpty()) "No build for this kernel" else "No build for $gpuSeries yet"
                }
                else -> "The kernel refused the module"
            }

            Fix.RESERVE -> when (state) {
                FixState.ON -> when {
                    reserveNow == null -> "Active"
                    reserveStock != null && reserveStock < reserveNow -> "Active · scale $reserveStock → $reserveNow"
                    else -> "Active · scale $reserveNow"
                }
                else -> "Not available on this kernel"
            }

            Fix.PIN -> when (state) {
                FixState.ON -> buildString {
                    append(if (pinApps > 0) "$pinIn of $pinApps kept" else "Active")
                    pinKb?.let { append(" · ${megabytes(it)} in RAM") }
                    pinSwapKb?.takeIf { it >= 1024 }?.let { append(", ${megabytes(it)} compressed") }
                }
                else -> "No memory controller on this kernel"
            }

            Fix.SYS -> when (state) {
                FixState.ON -> buildString {
                    append(if (sysIn) "Kept" else "Waiting for system_server")
                    sysKb?.let { append(" · ${megabytes(it)} in RAM") }
                }
                else -> "No memory controller on this kernel"
            }
        }
    }
}

fun megabytes(kb: Long): String = "${(kb + 512) / 1024} MB"

/** Parses the key=value lines of `dejank.sh status`. Null when the text is not that. */
fun parseStatus(text: String): Status? {
    val map = HashMap<String, String>()
    for (line in text.lineSequence()) {
        val i = line.indexOf('=')
        if (i > 0) map[line.substring(0, i)] = line.substring(i + 1).trim()
    }
    val api = map["api"]?.toIntOrNull() ?: return null
    return Status(
        api = api,
        version = map["version"].orEmpty(),
        build = map["build"]?.toIntOrNull() ?: 0,
        paused = map["paused"] == "1",
        wanted = Fix.entries.associateWith { map[it.key] == "1" },
        states = Fix.entries.associateWith { FixState.parse(map[it.key + "_state"]) },
        gpuWhy = map["gpu_why"].orEmpty(),
        gpuSeries = map["gpu_series"].orEmpty(),
        gpuWas = map["gpu_was"]?.toIntOrNull(),
        reserveNow = map["reserve_now"]?.toIntOrNull(),
        reserveStock = map["reserve_stock"]?.toIntOrNull(),
        pinApps = map["pin_apps"]?.toIntOrNull() ?: 0,
        pinIn = map["pin_in"]?.toIntOrNull() ?: 0,
        pinKb = map["pin_kb"]?.toLongOrNull(),
        pinSwapKb = map["pin_swap_kb"]?.toLongOrNull(),
        sysIn = map["sys_in"] == "1",
        sysKb = map["sys_kb"]?.toLongOrNull(),
    )
}
