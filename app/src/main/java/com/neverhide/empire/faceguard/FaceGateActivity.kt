package com.neverhide.empire.faceguard

import android.app.KeyguardManager
import android.hardware.Camera
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.neverhide.empire.guardian.GuardianAlert
import com.neverhide.empire.guardian.IntruderCamera
import com.neverhide.empire.tools.siren.SirenService

/**
 * FACE GATE — shown when the app opens and Face Guard is enabled.
 *
 * Owner's face: instant unlock.
 * Wrong face (twice in a row): full Guardian response — siren, vibration,
 * intruder selfie, location + SMS/WhatsApp alert to the set number.
 * No face: keeps waiting quietly.
 * Escape hatch: phone PIN/credential — always available, never a lockout.
 */
class FaceGateActivity : ComponentActivity() {

    private var camera: Camera? = null
    private var frame: ByteArray? = null
    private var frameW = 0
    private var frameH = 0
    private var wrongCount = 0
    private var intruderFired = false
    private val main = Handler(Looper.getMainLooper())

    private val pinLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK) unlock() else moveTaskToBack(true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val act = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            var hint by remember { mutableStateOf("Look at the camera…") }
            var intruder by remember { mutableStateOf(false) }

            Column(
                Modifier.fillMaxSize().background(if (intruder) Color(0xFF1A0000) else Color(0xFF000000))
                    .systemBarsPadding().padding(20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(if (intruder) "🚨" else "🔒", fontSize = 54.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    if (intruder) "Wrong face detected.\nAlert + selfie sent." else "Face check…",
                    color = if (intruder) Color(0xFFFF5252) else Color(0xFF00E5FF),
                    fontSize = 16.sp, modifier = Modifier.padding(bottom = 24.dp)
                )
                if (!intruder) {
                    // 1px hidden preview surface — camera needs it to deliver frames
                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).also { sv ->
                                sv.layoutParams = FrameLayout.LayoutParams(1, 1)
                                sv.holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(h: SurfaceHolder) { openCamera(h) }
                                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}
                                    override fun surfaceDestroyed(h: SurfaceHolder) { closeCamera() }
                                })
                            }
                        },
                        modifier = Modifier.size(1.dp)
                    )
                    Text(hint, color = Color(0xFF6B7280), fontSize = 12.sp)
                    Spacer(Modifier.height(28.dp))
                    val km = act.getSystemService(KEYGUARD_SERVICE) as KeyguardManager
                    if (km.isDeviceSecure) {
                        Button(
                            onClick = {
                                val i = km.createConfirmDeviceCredentialIntent("Neverhide Empire", "Use your phone PIN instead")
                                pinLauncher.launch(i)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF111827))
                        ) { Text("Use phone PIN instead", color = Color(0xFF00E5FF)) }
                    } else {
                        Text(
                            "No phone lock set — Face Guard can't gate safely. Set a phone PIN, or open Face Guard in the Toolkit and turn the gate off.",
                            color = Color(0xFF6B7280), fontSize = 11.sp
                        )
                        Button(
                            onClick = { unlock() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF111827))
                        ) { Text("Open app anyway", color = Color.Gray) }
                    }
                }
            }
        }
    }

    // ===================== camera + gate logic =====================

    private fun openCamera(holder: SurfaceHolder) {
        runCatching {
            val cam = Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT) ?: return
            val params = cam.parameters
            val size = params.supportedPreviewSizes
                .filter { it.width <= 480 }
                .minByOrNull { it.width * it.height } ?: params.previewSize
            params.setPreviewSize(size.width, size.height)
            cam.parameters = params
            cam.setDisplayOrientation(90)
            cam.setPreviewDisplay(holder)
            cam.setPreviewCallback { data, cam2 ->
                val s = cam2.parameters.previewSize
                frame = data; frameW = s.width; frameH = s.height
            }
            cam.startPreview()
            camera = cam
            startAnalysisLoop()
        }
    }

    private fun startAnalysisLoop() {
        Thread {
            while (!isFinishing && camera != null && !FaceGuard.unlockedThisProcess) {
                val d = frame; val w = frameW; val h = frameH
                if (d != null && w > 0) {
                    val r270 = FaceGuard.analyze(this, FaceGuard.nv21ToGray(d, w, h, 270))
                    val r = if (r270.present) r270 else FaceGuard.analyze(this, FaceGuard.nv21ToGray(d, w, h, 90))
                    val score = r.score
                    if (score != null) {
                        if (score >= FaceGuard.MATCH_THRESHOLD) {
                            FaceGuard.unlockedThisProcess = true
                            main.post { unlock() }
                            return@Thread
                        } else if (r.present && score < FaceGuard.REJECT_THRESHOLD) {
                            wrongCount++
                            if (wrongCount >= 2 && !intruderFired) {
                                intruderFired = true
                                main.post { intruderResponse() }
                            }
                        } else {
                            wrongCount = 0
                        }
                    }
                }
                Thread.sleep(700)
            }
        }.start()
    }

    private fun intruderResponse() {
        SirenService.start(this, SirenService.MODE_INTRUDER, 30)
        runCatching {
            val vib = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26)
                vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1))
            else @Suppress("DEPRECATION") vib.vibrate(1500)
        }
        IntruderCamera.capture(this) { photo ->
            GuardianAlert.fire(this, photo, reason = "face")
        }
        Toast.makeText(this, "🚨 Wrong face — alert fired", Toast.LENGTH_LONG).show()
    }

    private fun unlock() {
        FaceGuard.unlockedThisProcess = true
        closeCamera()
        setResult(RESULT_OK)
        finish()
    }

    private fun closeCamera() {
        runCatching {
            camera?.setPreviewCallback(null)
            camera?.stopPreview()
            camera?.release()
        }
        camera = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // back never bypasses the gate — it just backgrounds the app
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        closeCamera()
    }
}
