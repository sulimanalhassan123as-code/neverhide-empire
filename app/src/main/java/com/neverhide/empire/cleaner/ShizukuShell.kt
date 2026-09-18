package com.neverhide.empire.cleaner

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

/**
 * CYBER CLEANER — app-side broker for the Shizuku shell service.
 *
 * Binds [ShizukuShellService] inside the Shizuku shell process using the
 * same bind pattern as the v2.4.0 call capture. All pm commands the App
 * Freezer needs (suspend / unsuspend / list suspended) run through here.
 */
object ShizukuShell {

    private const val REQUEST_CODE = 21

    private var bound: IShizukuShell? = null

    fun available(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun permissionGranted(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    @Synchronized
    private fun binder(context: Context): IShizukuShell? {
        if (!available() || !permissionGranted()) return null
        if (bound != null) return bound
        return runCatching {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, "com.neverhide.empire.cleaner.ShizukuShellService")
            )
                .daemon(false)
                .processNameSuffix("empire-shell")
                .debuggable(false)
                .version(1)
            val latch = java.util.concurrent.CountDownLatch(1)
            var result: IBinder? = null
            Shizuku.bindUserService(args, object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    result = service
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    latch.countDown()
                }
            })
            latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
            result?.let { IShizukuShell.Stub.asInterface(it) }?.also { bound = it }
        }.getOrNull()
    }

    /**
     * Run a shell command and get BOTH exit code and output.
     * Returns null if the shell is unavailable.
     */
    fun run(context: Context, cmd: String): Pair<Int, String>? {
        val svc = binder(context) ?: return null
        return runCatching {
            val out = svc.exec(cmd)
            val code = out.lineSequence().firstOrNull()?.removePrefix("exit:")?.trim()?.toIntOrNull() ?: -1
            Pair(code, out.lineSequence().drop(1).joinToString("\n"))
        }.getOrNull()
    }

    /**
     * Run a shell command in the Shizuku shell process.
     * Returns the exit code, or null if the shell is unavailable.
     */
    fun exec(context: Context, cmd: String): Int? {
        val svc = binder(context) ?: return null
        return runCatching {
            val out = svc.exec(cmd)
            out.lineSequence().firstOrNull()?.removePrefix("exit:")?.trim()?.toIntOrNull() ?: -1
        }.getOrNull()
    }

    /**
     * Run a shell command and return its stdout (exit-code line stripped).
     * Returns null if the shell is unavailable.
     */
    fun query(context: Context, cmd: String): String? {
        val svc = binder(context) ?: return null
        return runCatching {
            svc.exec(cmd).lineSequence().drop(1).joinToString("\n")
        }.getOrNull()
    }
}
