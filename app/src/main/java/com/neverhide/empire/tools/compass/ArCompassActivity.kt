package com.neverhide.empire.tools.compass

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Camera
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.neverhide.empire.dashboard.Palette

/**
 * TOOLS SECTION — AR Camera Compass + Spirit Level.
 *
 * Live camera feed with a heads-up overlay: heading degrees + direction,
 * a bubble level (pitch/roll), and a green STRAIGHT verdict when the
 * phone (and whatever it's resting on) is within 1° of level.
 * Point the camera at a shelf, frame, or wall and know instantly
 * if it's straight.
 */
class ArCompassActivity : ComponentActivity(), SensorEventListener {

    private var sm: SensorManager? = null
    private var camera: Camera? = null

    private var azimuth = mutableStateOf(0f)
    private var roll = mutableStateOf(0f)
    private var pitch = mutableStateOf(0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 9)
        }
        setContent { ArScreen() }
    }

    override fun onResume() {
        super.onResume()
        sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sm?.unregisterListener(this)
        releaseCamera()
    }

    override fun onSensorChanged(e: SensorEvent?) {
        e ?: return
        if (e.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val r = FloatArray(9); val o = FloatArray(3)
            SensorManager.getRotationMatrixFromVector(r, e.values)
            SensorManager.getOrientation(r, o)
            azimuth.value = Math.toDegrees(o[0].toDouble()).toFloat()
            pitch.value = Math.toDegrees(o[1].toDouble()).toFloat()
            roll.value = Math.toDegrees(o[2].toDouble()).toFloat()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ============== CAMERA ==============

    private fun openCamera(): Camera? {
        if (camera != null) return camera
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return null
        camera = runCatching { Camera.open() }.getOrNull()
        camera?.setDisplayOrientation(90)
        return camera
    }

    private fun releaseCamera() {
        camera?.stopPreview(); camera?.release(); camera = null
    }

    // ============== UI ==============

    @Composable
    private fun ArScreen() {
        val az = ((azimuth.value % 360) + 360) % 360
        val dir = when ((az.toInt() / 45) % 8) {
            0 -> "N"; 1 -> "NE"; 2 -> "E"; 3 -> "SE"
            4 -> "S"; 5 -> "SW"; 6 -> "W"; else -> "NW"
        }
        val leveled = kotlin.math.abs(roll.value) < 1.0 && kotlin.math.abs(pitch.value) < 1.0
        val rollDeg = roll.value
        val pitchDeg = pitch.value

        Box(Modifier.fillMaxSize().background(Color.Black)) {

            // Camera preview
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        val sv = SurfaceView(ctx)
                        sv.holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) {
                                openCamera()?.let { cam ->
                                    runCatching {
                                        cam.setPreviewDisplay(h)
                                        cam.startPreview()
                                    }
                                }
                            }
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                            override fun surfaceDestroyed(h: SurfaceHolder) { releaseCamera() }
                        })
                        addView(sv, FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                        ))
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // HUD overlay
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top: heading card
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .shadow(10.dp, RoundedCornerShape(14.dp))
                            .background(Color(0xCC0A0A1A), RoundedCornerShape(14.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text("${az.toInt()}°  $dir", color = Palette.CYAN, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        Modifier
                            .shadow(10.dp, RoundedCornerShape(14.dp))
                            .background(Color(0xCC0A0A1A), RoundedCornerShape(14.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text("🧭 AR", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Middle: center crosshair
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("＋", color = Color(0x99FFFFFF), fontSize = 28.sp)
                    }
                }

                // Bottom: spirit level card
                Box(
                    Modifier
                        .fillMaxWidth()
                        .shadow(12.dp, RoundedCornerShape(18.dp), spotColor = if (leveled) Palette.GREEN.copy(alpha = 0.5f) else Color(0x00000000))
                        .background(Color(0xCC0A0A1A), RoundedCornerShape(18.dp))
                        .padding(16.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (leveled) "✅ STRAIGHT" else "🎚️ TILTED",
                            color = if (leveled) Palette.GREEN else Palette.AMBER,
                            fontSize = 18.sp, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        // Bubble level bar: dot slides left/right with roll
                        val bubble = ((rollDeg.coerceIn(-10f, 10f) + 10f) / 20f)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(16.dp)
                                .background(Color(0xFF111827), RoundedCornerShape(99.dp))
                                .border(1.dp, Palette.BORDER, RoundedCornerShape(99.dp)),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Spacer(Modifier.weight(bubble.coerceAtLeast(0.02f)))
                            Box(
                                Modifier
                                    .size(12.dp)
                                    .background(
                                        if (leveled) Palette.GREEN else Palette.AMBER,
                                        androidx.compose.foundation.shape.CircleShape
                                    )
                            )
                            Spacer(Modifier.weight((1f - bubble).coerceAtLeast(0.02f)))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Roll ${"%.1f".format(rollDeg)}°  •  Pitch ${"%.1f".format(pitchDeg)}°",
                            color = Palette.TEXT_DIM, fontSize = 12.sp
                        )
                        Text(
                            if (leveled) "Hold steady — object is level" else "Tilt the phone until it shows STRAIGHT",
                            color = Palette.TEXT_MUTE, fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}
