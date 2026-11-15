package dev.arcatva.dejank

import java.util.Locale

/** The version of dejank.sh's status output this app understands. */
const val EXPECTED_API = 3

enum class ThemeChoice(val label: Int) {
    DARK(R.string.theme_dark),
    SYSTEM(R.string.theme_system),
    LIGHT(R.string.theme_light),
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

/** Text that lives in the resources: [id] filled in with [args]. */
data class Line(val id: Int, val args: List<Any> = emptyList())

fun lineOf(id: Int, vararg args: Any): Line = Line(id, args.toList())

/** The two fixes that are one switch each. [key] is the name dejank.sh knows each by. */
enum class Fix(val key: String, val switch: Int, val about: Int, val cost: Int) {
    GPU("gpu", R.string.gpu_switch, R.string.gpu_about, R.string.gpu_cost),
    RESERVE("reserve", R.string.reserve_switch, R.string.reserve_about, R.string.reserve_cost),
}

/**
 * A process that can be kept in RAM, each with a switch of its own. An app
 * [restarts] once into its memory group; a system process is moved as it is.
 */
enum class Keep(val key: String, val restarts: Boolean, val title: Int, val role: Int) {
    UI("ui", true, R.string.keep_ui, R.string.keep_ui_role),
    HOME("home", true, R.string.keep_home, R.string.keep_home_role),
    SF("sf", false, R.string.keep_sf, R.string.keep_sf_role),
    COMPOSER("composer", false, R.string.keep_composer, R.string.keep_composer_role),
    ALLOC("alloc", false, R.string.keep_alloc, R.string.keep_alloc_role),
    SERVER("server", false, R.string.keep_server, R.string.keep_server_role),
}

enum class KeepState {
    ON,

    /** The memory group is there, the process is not in it yet. */
    WAIT,
    OFF,

    /** Nothing can be kept on this phone, see [Status.keepWhy]. */
    NA,

    /** This phone has no such process. */
    NONE,
    ;

    companion object {
        fun parse(text: String?): KeepState = when (text) {
            "on" -> ON
            "wait" -> WAIT
            "na" -> NA
            "none" -> NONE
            else -> OFF
        }
    }
}

data class Kept(
    val wanted: Boolean = false,
    val state: KeepState = KeepState.OFF,
    /** What the memory group holds. */
    val kb: Long? = null,
    /** Its floor: what is never reclaimed. Null while the process settles and everything is kept. */
    val minKb: Long? = null,
    val settling: Boolean = false,
) {
    val active: Boolean get() = state == KeepState.ON || state == KeepState.WAIT

    /** What is kept right now. */
    val keptKb: Long? get() = minKb ?: kb
}

/** The tiles of the first screen. */
enum class Tile(val title: Int, val icon: Int) {
    GPU(R.string.tile_gpu, R.drawable.ic_gpu),
    RESERVE(R.string.tile_reserve, R.drawable.ic_reserve),
    KEEP(R.string.tile_keep, R.drawable.ic_keep),
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
    /** Why nothing can be kept in RAM here: "official" (Android does it itself), "controller", or empty. */
    val keepWhy: String = "",
    val memTotalKb: Long? = null,
    val kept: Map<Keep, Kept> = emptyMap(),
) {
    fun wanted(fix: Fix): Boolean = wanted[fix] == true

    fun state(fix: Fix): FixState = states[fix] ?: FixState.OFF

    fun kept(keep: Keep): Kept = kept[keep] ?: Kept()

    /** Switched on but not in effect, as after installing or after a reboot the module did not see. */
    fun pending(fix: Fix): Boolean = wanted(fix) && state(fix) == FixState.OFF

    fun pending(keep: Keep): Boolean = kept(keep).let { it.wanted && it.state == KeepState.OFF }

    val keepPending: Boolean get() = Keep.entries.any { pending(it) }

    val anyPending: Boolean get() = !paused && (Fix.entries.any { pending(it) } || keepPending)

    /** Applying would restart System UI or the home app. */
    val applyRestarts: Boolean get() = Keep.entries.any { it.restarts && pending(it) }

    /** Everything that is kept in RAM right now. */
    val keptKb: Long get() = Keep.entries.sumOf { k -> kept(k).let { if (it.active) it.keptKb ?: 0L else 0L } }

    val keptCount: Int get() = Keep.entries.count { kept(it).active }

    fun on(tile: Tile): Boolean = when (tile) {
        Tile.GPU -> state(Fix.GPU) == FixState.ON
        Tile.RESERVE -> state(Fix.RESERVE) == FixState.ON
        Tile.KEEP -> keptCount > 0
    }

    /** Nothing to switch on here: counted neither as working nor as off. */
    fun unavailable(tile: Tile): Boolean = when (tile) {
        Tile.GPU -> state(Fix.GPU) == FixState.NA
        Tile.RESERVE -> state(Fix.RESERVE) == FixState.NA
        Tile.KEEP -> keepWhy.isNotEmpty()
    }

    /** Switched on, but nothing of it is in effect yet. */
    fun pending(tile: Tile): Boolean = when (tile) {
        Tile.GPU -> pending(Fix.GPU)
        Tile.RESERVE -> pending(Fix.RESERVE)
        Tile.KEEP -> keepWhy.isEmpty() && keptCount == 0 && keepPending
    }

    /** Off because that is how it was set: nothing wrong, nothing waiting. */
    fun switchedOff(tile: Tile): Boolean = when (tile) {
        Tile.GPU -> !wanted(Fix.GPU) && state(Fix.GPU) != FixState.NA
        Tile.RESERVE -> !wanted(Fix.RESERVE) && state(Fix.RESERVE) != FixState.NA
        Tile.KEEP -> keepWhy.isEmpty() && Keep.entries.none { kept(it).wanted }
    }

    val working: Int get() = Tile.entries.count { on(it) }

    val switchedOff: Int get() = Tile.entries.count { switchedOff(it) }

    /** As right after installing: things are switched on and none of them is in effect. */
    val nothingApplied: Boolean get() = !paused && working == 0 && Tile.entries.any { pending(it) }

    fun tone(fix: Fix): Tone = when (state(fix)) {
        FixState.ON -> Tone.GOOD
        FixState.ERR -> Tone.BAD
        FixState.NA -> Tone.IDLE
        FixState.OFF -> if (wanted(fix)) Tone.WAITING else Tone.IDLE
    }

    fun tone(keep: Keep): Tone = kept(keep).let {
        when (it.state) {
            KeepState.ON -> Tone.GOOD
            KeepState.WAIT -> Tone.WAITING
            KeepState.OFF -> if (it.wanted) Tone.WAITING else Tone.IDLE
            KeepState.NA, KeepState.NONE -> Tone.IDLE
        }
    }

    /** The state of one fix in a few words, with the numbers that show it. */
    fun line(fix: Fix): Line {
        val state = state(fix)
        if (state == FixState.OFF) return lineOf(if (wanted(fix)) R.string.not_applied else R.string.off)
        return when (fix) {
            Fix.GPU -> when (state) {
                FixState.ON -> {
                    val was = gpuWas
                    when {
                        was == null -> lineOf(R.string.in_effect)
                        was == 0 -> lineOf(R.string.gpu_on_never)
                        else -> lineOf(R.string.gpu_on, was)
                    }
                }
                FixState.NA -> when (gpuWhy) {
                    "driver" -> lineOf(R.string.gpu_na_driver)
                    "setting" -> lineOf(R.string.gpu_na_setting)
                    else -> if (gpuSeries.isEmpty()) lineOf(R.string.gpu_na_kernel) else lineOf(R.string.gpu_na_series, gpuSeries)
                }
                else -> lineOf(R.string.gpu_err)
            }

            Fix.RESERVE -> when (state) {
                FixState.ON -> {
                    val now = reserveNow
                    val stock = reserveStock
                    when {
                        now == null -> lineOf(R.string.in_effect)
                        stock != null && stock < now -> lineOf(R.string.reserve_on, stock, now)
                        else -> lineOf(R.string.reserve_on_plain, now)
                    }
                }
                else -> lineOf(R.string.reserve_na)
            }
        }
    }

    fun line(keep: Keep): Line {
        val k = kept(keep)
        return when (k.state) {
            KeepState.ON -> {
                val kb = k.keptKb
                when {
                    kb == null -> lineOf(R.string.kept_plain)
                    k.settling -> lineOf(R.string.kept_settling, size(kb))
                    else -> lineOf(R.string.kept_on, size(kb))
                }
            }
            KeepState.WAIT -> lineOf(R.string.kept_wait)
            KeepState.NONE -> lineOf(R.string.kept_none)
            // why is said once, above the switches
            KeepState.NA -> lineOf(R.string.not_available)
            KeepState.OFF -> lineOf(if (k.wanted) R.string.not_applied else R.string.off)
        }
    }

    /** Why nothing can be kept in RAM on this phone. */
    fun keepReason(): Line =
        lineOf(if (keepWhy == "official") R.string.keep_na_official else R.string.keep_na_controller)

    /** What a tile says under its name. */
    fun short(tile: Tile): Line = when (tile) {
        Tile.GPU -> if (on(tile)) lineOf(R.string.gpu_short_on) else line(Fix.GPU)
        Tile.RESERVE -> {
            val now = reserveNow
            if (on(tile) && now != null) lineOf(R.string.reserve_short_on, now) else line(Fix.RESERVE)
        }
        Tile.KEEP -> when {
            keepWhy.isNotEmpty() -> keepReason()
            on(tile) -> lineOf(R.string.keep_short_on, size(keptKb))
            keepPending -> lineOf(R.string.not_applied)
            else -> lineOf(R.string.off)
        }
    }

    fun tone(tile: Tile): Tone = when (tile) {
        Tile.GPU -> tone(Fix.GPU)
        Tile.RESERVE -> tone(Fix.RESERVE)
        Tile.KEEP -> when {
            keepWhy.isNotEmpty() -> Tone.IDLE
            on(tile) -> Tone.GOOD
            keepPending -> Tone.WAITING
            else -> Tone.IDLE
        }
    }
}

/** A memory size the way people say it: 780 MB, 1.6 GB. */
fun size(kb: Long): String {
    val mb = (kb + 512) / 1024
    return if (mb < 1000) "$mb MB" else String.format(Locale.US, "%.1f GB", kb / 1048576.0)
}

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
        keepWhy = map["keep_why"].orEmpty(),
        memTotalKb = map["mem_total_kb"]?.toLongOrNull(),
        kept = Keep.entries.associateWith { keep ->
            val min = map[keep.key + "_min_kb"]
            Kept(
                wanted = map[keep.key] == "1",
                state = KeepState.parse(map[keep.key + "_state"]),
                kb = map[keep.key + "_kb"]?.toLongOrNull(),
                minKb = min?.toLongOrNull(),
                settling = min == "max",
            )
        },
    )
}
