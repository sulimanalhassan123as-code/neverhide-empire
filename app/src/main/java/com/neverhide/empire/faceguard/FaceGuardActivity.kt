package com.neverhide.empire.faceguard

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

/**
 * FACE GUARD SETUP — enroll the owner's face, test recognition live,
 * and toggle the app-open gate. Everything stays on this device.
 */
class FaceGuardActivity : ComponentActivity() {

    private var camera: Camera? = null
    private var frame: ByteArray? = null
    private var frameW = 0
    private var frameH = 0
    private var capturing = false

    private val requestCam = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) enroll()
        else Toast.makeText(this, "Camera permission needed to enroll", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val act = this
        setContent {
            var enrolled by remember { mutableStateOf(FaceGuard.isEnrolled(act)) }
            var enabled by remember { mutableStateOf(FaceGuard.isEnabled(act)) }
            var busy by remember { mutableStateOf(false) }
            var testResult by remember { mutableStateOf<String?>(null) }
            val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
            DisposableEffect(owner) {
                val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                    if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        enrolled = FaceGuard.isEnrolled(act)
                        enabled = FaceGuard.isEnabled(act)
                    }
                }
                owner.lifecycle.addObserver(obs)
                onDispose { owner.lifecycle.removeObserver(obs) }
            }

            Column(
                Modifier.fillMaxSize().background(Color(0xFF0A0A1A))
                    .verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("👤 Face Guard", color = Color(0xFF00E5FF), fontSize = 22.sp)
                Text(
                    if (enrolled) "✅ Face enrolled ${if (enabled) "— gate is ON" else "— gate is OFF"}"
                    else "No face enrolled yet — look straight at the camera, good light, face in the center.",
                    color = Color.Gray, fontSize = 13.sp
                )

                // live preview
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).also { sv ->
                            sv.holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(h: SurfaceHolder) { openCamera(h) }
                                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}
                                override fun surfaceDestroyed(h: SurfaceHolder) { closeCamera() }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(260.dp)
                )

                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(act, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) enroll()
                        else requestCam.launch(Manifest.permission.CAMERA)
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                ) { Text(if (busy) "Capturing…" else "📷 Enroll my face (3s)", color = Color.Black) }

                Button(
                    onClick = { busy = true; testRecognition { msg -> busy = false; testResult = msg } },
                    enabled = !busy && enrolled,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF111827))
                ) { Text("🧪 Test recognition now", color = Color(0xFF00E5FF)) }

                testResult?.let {
                    Text(
                        it, color = Color(0xFFB388FF), fontSize = 13.sp,
                        modifier = Modifier
                            .background(Color(0xFF111827), RoundedCornerShape(10.dp))
                            .padding(10.dp).fillMaxWidth()
                    )
                }

                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF111827), RoundedCornerShape(12.dp)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Lock Empire on app open", color = Color.White)
                        Text("Face check each time you open the app", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            if (it && !FaceGuard.isEnrolled(act)) {
                                Toast.makeText(act, "Enroll your face first", Toast.LENGTH_SHORT).show()
                                return@Switch
                            }
                            enabled = it
                            FaceGuard.setEnabled(act, it)
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF00E5FF))
                    )
                }

                if (enrolled) {
                    TextButton(onClick = {
                        FaceGuard.clearData(act)
                        enrolled = false
                        enabled = false
                        Toast.makeText(act, "Face data removed", Toast.LENGTH_SHORT).show()
                    }) { Text("🗑️ Remove my face data", color = Color(0xFFFF5252)) }
                }

                Text(
                    "Honest limits: visual deterrent, not certified biometric security. " +
                            "Wrong face triggers the full Guardian response: siren + vibration + selfie + location alert to your set number. " +
                            "A phone-PIN escape hatch is always available on the gate.",
                    color = Color(0xFF6B7280), fontSize = 11.sp
                )
            }
        }
    }

    // ===================== camera =====================

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
        }
    }

    private fun closeCamera() {
        runCatching {
            camera?.setPreviewCallback(null)
            camera?.stopPreview()
            camera?.release()
        }
        camera = null
    }

    // ===================== enroll =====================

    private fun enroll() {
        if (capturing) return
        capturing = true
        val main = Handler(Looper.getMainLooper())
        Toast.makeText(this, "Look straight at the camera…", Toast.LENGTH_SHORT).show()
        Thread {
            val vecs = ArrayList<FloatArray>()
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 3000) {
                val d = frame; val w = frameW; val h = frameH
                if (d != null && w > 0) {
                    val r = FaceGuard.analyze(this, d, w, h)
                    if (r.present) r.vec?.let { synchronized(vecs) { vecs.add(it) } }
                }
                Thread.sleep(120)
            }
            val n = FaceGuard.enroll(this, synchronized(vecs) { ArrayList(vecs) })
            capturing = false
            main.post {
                if (n > 0) Toast.makeText(
                    this, "✅ Enrolled ($n templates). Test recognition, then turn on the gate.", Toast.LENGTH_LONG
                ).show()
                else Toast.makeText(this, "No face captured — better light, face centered, closer", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun testRecognition(onDone: (String) -> Unit) {
        Thread {
            var msg = "No frame from camera yet — try again in a second"
            val d = frame; val w = frameW; val h = frameH
            if (d != null && w > 0) {
                val r = FaceGuard.analyze(this, d, w, h)
                val sc = r.score
                msg = when {
                    !r.present -> "No face detected — center your face, more light"
                    sc == null -> "Face seen but nothing enrolled"
                    sc >= FaceGuard.MATCH_THRESHOLD -> "✅ OWNER — score %.2f (match threshold %.2f)".format(sc, FaceGuard.MATCH_THRESHOLD)
                    sc < FaceGuard.REJECT_THRESHOLD -> "❌ UNKNOWN FACE — score %.2f — this is what triggers the alarm".format(sc)
                    else -> "⚠️ UNCERTAIN — score %.2f. Re-enroll in better light for cleaner scores.".format(sc)
                }
            }
            Handler(Looper.getMainLooper()).post { onDone(msg) }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        closeCamera()
    }
}
