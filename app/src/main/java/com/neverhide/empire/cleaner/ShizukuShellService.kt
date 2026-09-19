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
            // CAP output at 64KB before it ever crosses the binder IPC.
            // Android's binder reply limit is ~1MB TOTAL for the process;
            // a raw `dumpsys package` produces 1-2MB and the oversized reply
            // kills this shell process and crashes the caller. Never again.
            val cap = 64 * 1024
            val buf = StringBuilder()
            val br = proc.inputStream.bufferedReader()
            val cbuf = CharArray(8192)
            while (buf.length < cap) {
                val n = br.read(cbuf)
                if (n < 0) break
                buf.append(cbuf, 0, minOf(n, cap - buf.length))
            }
            val out = buf.toString()
            val err = proc.errorStream.bufferedReader().use { it.readText().take(2048) }
            val code = proc.waitFor()
            "exit:$code\n$out$err"
        } catch (e: Exception) {
            "exit:-1\n${e.message ?: e.javaClass.simpleName}"
        }
    }
}
