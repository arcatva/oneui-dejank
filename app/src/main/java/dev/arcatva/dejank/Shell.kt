package dev.arcatva.dejank

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ShellResult(val code: Int, val out: String, val err: String)

/** Runs one command through `su -c`. code -1: su could not be started, -2: timed out. */
object RootShell {
    suspend fun run(command: String, timeoutSec: Long = 15): ShellResult =
        withContext(Dispatchers.IO) {
            val process = try {
                ProcessBuilder("su", "-c", command).start()
            } catch (e: IOException) {
                return@withContext ShellResult(-1, "", e.message ?: "su not available")
            }
            coroutineScope {
                runCatching { process.outputStream.close() }
                val out = async { process.inputStream.bufferedReader().use { it.readText() } }
                val err = async { process.errorStream.bufferedReader().use { it.readText() } }
                val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
                if (!finished) process.destroyForcibly()
                ShellResult(if (finished) process.exitValue() else -2, out.await(), err.await())
            }
        }
}

/** Talks to the module's control script; the module does all the real work. */
object Ctl {
    private const val ID = "oneui-dejank"

    /**
     * Sets d to the module directory. Root managers keep a freshly installed
     * version in modules_update until the next boot; that one is used when it
     * is there, so an install works without a reboot.
     */
    private const val PICK =
        "d=/data/adb/modules_update/$ID; [ -f \"\$d/dejank.sh\" ] || d=/data/adb/modules/$ID; "
    private const val RUN = PICK + "sh \"\$d/dejank.sh\""

    sealed interface Probe {
        data class Ok(val status: Status) : Probe
        data object NoRoot : Probe
        data object NoModule : Probe
        data class Broken(val detail: String) : Probe
    }

    suspend fun status(): Probe {
        val result = RootShell.run("$RUN status")
        parseStatus(result.out)?.let { return Probe.Ok(it) }

        // No usable answer: find out which link of the chain is missing.
        val id = RootShell.run("id -u")
        if (id.code != 0 || id.out.trim() != "0") return Probe.NoRoot
        val present = RootShell.run("${PICK}[ -f \"\$d/dejank.sh\" ] && echo yes")
        if (present.out.trim() != "yes") return Probe.NoModule
        return Probe.Broken(result.err.ifBlank { result.out }.trim().take(400))
    }

    /** True when the script answered ok=1. Restarting System UI and Home can take half a minute. */
    private suspend fun command(args: String): Boolean {
        val result = RootShell.run("$RUN $args", timeoutSec = 60)
        return result.code == 0 && result.out.contains("ok=1")
    }

    suspend fun set(fix: Fix, on: Boolean): Boolean = command("set ${fix.key} ${if (on) 1 else 0}")

    suspend fun apply(): Boolean = command("apply")

    suspend fun resume(): Boolean = command("resume")

    /** What to attach to a bug report: device, kernel, state and the module's log. No serial numbers. */
    suspend fun report(): String {
        val result = RootShell.run(
            PICK + "echo \"One UI Dejank report\"; " +
                "echo \"model=\$(getprop ro.product.model)\"; " +
                "echo \"android=\$(getprop ro.build.version.release)\"; " +
                "echo \"oneui=\$(getprop ro.build.version.oneui)\"; " +
                "echo \"kernel=\$(uname -r)\"; " +
                "sh \"\$d/dejank.sh\" status; echo; sh \"\$d/dejank.sh\" log 100",
            timeoutSec = 30,
        )
        return if (result.out.contains("api=")) result.out.trim() else ""
    }
}
