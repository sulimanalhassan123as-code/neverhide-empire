package com.neverhide.empire.calls

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * CALLS SECTION — app-side broker for the Shizuku capture service.
 *
 * Binds [ShizukuCaptureService] inside the shell process via Shizuku's
 * UserService mechanism. When Shizuku is running AND permission is
 * granted, the Call Recorder switches from the speakerphone-mic trick
 * to TRUE both-sides call-stream capture.
 */
object ShizukuCallCapture {

    private var bound: IShizukuCapture? = null

    /** true if Shizuku is running and we can bind. */
    fun available(): Boolean = runCatching {
        Shizuku.pingBinder()
    }.getOrDefault(false)

    /** true if the user already granted Shizuku permission to us. */
    fun permissionGranted(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Request permission (must be called from an Activity so the
     *  Shizuku dialog can show). */
    fun requestPermission() {
        runCatching {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                // fallthrough to direct request — we keep it simple
            }
            Shizuku.requestPermission(11)
        }
    }

    /** Bind the shell-side capture service. Returns the binder or null. */
    @Synchronized
    fun binder(context: Context): IShizukuCapture? {
        if (!available() || !permissionGranted()) return null
        if (bound != null) runCatching { if (bound!!.ping()) return bound }
        bound = null
        return runCatching {
            val args = Shizuku.UserServiceArgs(
                android.content.ComponentName(
                    context.packageName,
                    "com.neverhide.empire.calls.ShizukuCaptureService"
                )
            )
                .daemon(false)
                .processNameSuffix("callcapture")
                .debuggable(false)
                .version(1)
            val latch = java.util.concurrent.CountDownLatch(1)
            var result: android.os.IBinder? = null
            Shizuku.bindUserService(args, object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                    result = service; latch.countDown()
                }
                override fun onServiceDisconnected(name: android.content.ComponentName?) {
                    latch.countDown()
                }
            })
            latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
            val svc = result?.let { IShizukuCapture.Stub.asInterface(it) }
            if (svc != null && runCatching { svc.ping() }.getOrDefault(false)) {
                bound = svc
                svc
            } else null
        }.getOrNull()
    }

    /**
     * One-shot: start true capture to a wav path. Returns true if the
     * shell-side capture actually started.
     */
    fun start(context: Context, wavPath: String): Boolean {
        val svc = binder(context) ?: return false
        return runCatching { svc.start(wavPath) == 0 }.getOrDefault(false)
    }

    fun stop(): Boolean {
        val svc = bound ?: return false
        return runCatching { svc.stop() >= 0 }.getOrDefault(false)
    }
}
