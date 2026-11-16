package dev.arcatva.dejank

import java.util.Locale

/** The version of dejank.sh's status output this app understands. */
const val EXPECTED_API = 4

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

/** How a state is coloured. */
enum class Tone { GOOD, IDLE, WAITING, BAD }

/** Text that lives in the resources: [id] filled in with [args]. With a [quantity], [id] is a plural. */
data class Line(val id: Int, val args: List<Any> = emptyList(), val quantity: Int? = null)

fun lineOf(id: Int, vararg args: Any): Line = Line(id, args.toList())

/** A plural: [quantity] picks the form and is its first argument. */
fun countOf(id: Int, quantity: Int, vararg args: Any): Line = Line(id, listOf<Any>(quantity) + args, quantity)

/** The fixes that are one switch each. [key] is the name dejank.sh knows each by. */
enum class Fix(val key: String, val switch: Int) {
    GPU("gpu", R.string.gpu_switch),
    KVMALLOC("kvmalloc", R.string.kvmalloc_switch),
    RESERVE("reserve", R.string.reserve_switch),
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
) {
    val active: Boolean get() = state == KeepState.ON || state == KeepState.WAIT

    /** What is kept right now. */
    val keptKb: Long? get() = minKb ?: kb
}

/** The tiles of the first screen. A tile of [fixes] has a switch for each and one sentence [about] them. */
enum class Tile(val title: Int, val icon: Int, val fixes: List<Fix> = emptyList(), val about: Int = 0) {
    GPU(R.string.tile_gpu, R.drawable.ic_gpu, listOf(Fix.GPU, Fix.KVMALLOC), R.string.gpu_about),
    RESERVE(R.string.tile_reserve, R.drawable.ic_reserve, listOf(Fix.RESERVE), R.string.reserve_about),
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
    /** Why the hook into kvmalloc is not on: "builtin", "kernel", "hook", "insmod", "reboot", or empty. */
    val kvmallocWhy: String = "",
    /** Requests the hook has sent to vmalloc since it was loaded. */
    val kvmallocSent: Long? = null,
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

    fun on(tile: Tile): Boolean =
        if (tile == Tile.KEEP) keptCount > 0 else tile.fixes.any { state(it) == FixState.ON }

    /** A fix of this tile was switched on and the kernel would not have it. */
    private fun failed(tile: Tile): Boolean = tile.fixes.any { wanted(it) && state(it) == FixState.ERR }

    val working: Int get() = Tile.entries.count { on(it) }

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

    /** What is wrong with a fix or what it waits for. Null when it is simply on or off. */
    fun note(fix: Fix): Line? = when (state(fix)) {
        FixState.ON -> null
        FixState.OFF -> if (wanted(fix)) lineOf(R.string.not_applied) else null
        FixState.ERR -> lineOf(
            when {
                fix == Fix.RESERVE -> R.string.failed
                fix == Fix.KVMALLOC && kvmallocWhy == "reboot" -> R.string.kvmalloc_err_reboot
                else -> R.string.gpu_err
            },
        )
        FixState.NA -> when (fix) {
            Fix.RESERVE -> lineOf(R.string.reserve_na)
            Fix.GPU -> when (gpuWhy) {
                "driver" -> lineOf(R.string.gpu_na_driver)
                "setting" -> lineOf(R.string.gpu_na_setting)
                else -> noBuild()
            }
            Fix.KVMALLOC -> when (kvmallocWhy) {
                "builtin" -> lineOf(R.string.kvmalloc_na_builtin)
                "hook" -> lineOf(R.string.reserve_na)
                else -> noBuild()
            }
        }
    }

    /** There is no kernel module for this phone's kernel. */
    private fun noBuild(): Line =
        if (gpuSeries.isEmpty()) lineOf(R.string.gpu_na_kernel) else lineOf(R.string.gpu_na_series, gpuSeries)

    /** Under a fix's switch while it is on: what it has done so far. Null when there is nothing to count. */
    fun value(fix: Fix): Line? {
        if (fix != Fix.KVMALLOC || state(fix) != FixState.ON) return null
        return kvmallocSent?.let { countOf(R.plurals.kvmalloc_sent, it.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()) }
    }

    /** Next to a process's switch: how much of it is kept, or what it waits for. Null when there is nothing to say. */
    fun value(keep: Keep): Line? {
        val k = kept(keep)
        return when (k.state) {
            KeepState.ON -> k.keptKb?.let { lineOf(R.string.plain, size(it)) }
            KeepState.WAIT -> lineOf(R.string.kept_wait)
            KeepState.NONE -> lineOf(R.string.kept_none)
            // why is said once, above the switches
            KeepState.NA -> null
            KeepState.OFF -> if (k.wanted) lineOf(R.string.not_applied) else null
        }
    }

    /** Why nothing can be kept in RAM on this phone. */
    fun keepReason(): Line =
        lineOf(if (keepWhy == "official") R.string.keep_na_official else R.string.keep_na_controller)

    private fun short(fix: Fix): Line = when (state(fix)) {
        FixState.ON -> lineOf(R.string.on)
        FixState.OFF -> lineOf(if (wanted(fix)) R.string.not_applied else R.string.off)
        FixState.ERR -> lineOf(R.string.failed)
        FixState.NA -> lineOf(if (fix == Fix.GPU && gpuWhy == "setting") R.string.not_needed else R.string.not_supported)
    }

    /** What a tile says under its name: a word, or for what is kept, how many and how much. */
    fun short(tile: Tile): Line = when (tile) {
        Tile.GPU, Tile.RESERVE -> when {
            failed(tile) -> lineOf(R.string.failed)
            on(tile) -> lineOf(R.string.on)
            else -> short(tile.fixes.first())
        }
        Tile.KEEP -> when {
            keepWhy == "official" -> lineOf(R.string.built_in)
            keepWhy.isNotEmpty() -> lineOf(R.string.not_supported)
            on(tile) -> countOf(R.plurals.keep_short, keptCount, size(keptKb))
            keepPending -> lineOf(R.string.not_applied)
            else -> lineOf(R.string.off)
        }
    }

    fun tone(tile: Tile): Tone = when (tile) {
        Tile.GPU, Tile.RESERVE -> when {
            failed(tile) -> Tone.BAD
            on(tile) -> Tone.GOOD
            else -> tone(tile.fixes.first())
        }
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
        kvmallocWhy = map["kvmalloc_why"].orEmpty(),
        kvmallocSent = map["kvmalloc_sent"]?.toLongOrNull(),
        keepWhy = map["keep_why"].orEmpty(),
        memTotalKb = map["mem_total_kb"]?.toLongOrNull(),
        kept = Keep.entries.associateWith { keep ->
            Kept(
                wanted = map[keep.key] == "1",
                state = KeepState.parse(map[keep.key + "_state"]),
                kb = map[keep.key + "_kb"]?.toLongOrNull(),
                // "max" while the process settles: no floor yet, everything is kept
                minKb = map[keep.key + "_min_kb"]?.toLongOrNull(),
            )
        },
    )
}
