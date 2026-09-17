package com.neverhide.empire.cleaner

import androidx.annotation.Keep

/**
 * CYBER CLEANER — Shizuku shell executor (UserService).
 *
 * This class is loaded by Shizuku and runs INSIDE the shell process, so
 * plain Runtime.exec() executes with shell (ADB) privileges — the only
 * user-accessible identity allowed to run `pm suspend` without root.
 *
 * Used by the App Freezer as power path #2 when the app is not Device
 * Owner: freeze/unfreeze packages and read the real suspended state.
 */
@Keep
class ShizukuShellService : IShizukuShell.Stub() {

    override fun exec(cmd: String?): String {
        val c = cmd ?: return "exit:-1\nnull command"
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", c))
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            val err = proc.errorStream.bufferedReader().use { it.readText() }
            val code = proc.waitFor()
            "exit:$code\n$out$err"
        } catch (e: Exception) {
            "exit:-1\n${e.message ?: e.javaClass.simpleName}"
        }
    }
}
