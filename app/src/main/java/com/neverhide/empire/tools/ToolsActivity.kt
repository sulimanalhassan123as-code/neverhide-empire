package com.neverhide.empire.tools

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.provider.Telephony
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.neverhide.empire.tools.eyecare.EyeCareService
import com.neverhide.empire.tools.siren.SirenService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Empire Toolkit — every power tool in one place.
 * Grid → tap a tool → full screen for that tool.
 */
class ToolsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ToolkitRoot() }
    }

    // ===================== NAVIGATION =====================

    private data class ToolDef(val key: String, val label: String, val emoji: String, val color: Color)

    private val tools = listOf(
        ToolDef("flashlight", "Flashlight", "🔦", Color(0xFFFFC400)),
        ToolDef("compass", "Compass", "🧭", Color(0xFF00E5FF)),
        ToolDef("battery", "Battery Guard", "🔋", Color(0xFF69F0AE)),
        ToolDef("ram", "RAM Cleaner", "🧹", Color(0xFFFF6D00)),
        ToolDef("scanner", "App Scanner", "🛡️", Color(0xFFFF4081)),
        ToolDef("weather", "Weather", "🌤️", Color(0xFF40C4FF)),
        ToolDef("steps", "Step Counter", "👣", Color(0xFFB388FF)),
        ToolDef("eyecare", "Eye Care", "👀", Color(0xFF64DD17)),
        ToolDef("siren", "Find My Phone", "📢", Color(0xFFFF1744)),
        ToolDef("voicefx", "Voice FX", "🎙️", Color(0xFF7C4DFF)),
        ToolDef("soundmeter", "Sound Meter", "📈", Color(0xFFFFD600)),
        ToolDef("dialer", "Quick Dial", "📞", Color(0xFF00BFA5)),
        ToolDef("alarm", "Alarms & Timer", "⏰", Color(0xFFFF9100)),
        ToolDef("calendar", "Next Events", "📅", Color(0xFF448AFF)),
        ToolDef("location", "My Location", "📍", Color(0xFFFF5252)),
        ToolDef("animscale", "Animation Scale", "🎚️", Color(0xFFB0BEC5))
    )

    @Composable
    private fun ToolkitRoot() {
        var current by remember { mutableStateOf<String?>(null) }
        val darkBg = Color(0xFF0A0A1A)

        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(darkBg, Color(0xFF0D1B2A))))
        ) {
            if (current == null) {
                ToolGrid(onOpen = { current = it })
            } else {
                ToolScreen(current!!) { current = null }
            }
        }
    }

    @Composable
    private fun ToolGrid(onOpen: (String) -> Unit) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("🧰 Empire Toolkit", color = Color(0xFF00E5FF), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("16 power tools — all offline unless marked", color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(tools) { tool ->
                    Card(
                        Modifier
                            .height(100.dp)
                            .clickable { onOpen(tool.key) },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(
                            Modifier.fillMaxSize().padding(10.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(tool.emoji, fontSize = 28.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                tool.label, color = tool.color,
                                fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }

    // ===================== SCREEN SHELL =====================

    @Composable
    private fun ToolScreen(key: String, onBack: () -> Unit) {
        val def = tools.first { it.key == key }
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back", color = Color.Gray) }
                Spacer(Modifier.width(8.dp))
                Text("${def.emoji} ${def.label}", color = def.color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color(0xFF1E2A3A))
            Spacer(Modifier.height(14.dp))
            when (key) {
                "flashlight" -> FlashlightScreen()
                "compass" -> CompassScreen()
                "battery" -> BatteryScreen()
                "ram" -> RamScreen()
                "scanner" -> ScannerScreen()
                "weather" -> WeatherScreen()
                "steps" -> StepsScreen()
                "eyecare" -> EyeCareScreen()
                "siren" -> SirenScreen()
                "voicefx" -> VoiceFxScreen()
                "soundmeter" -> SoundMeterScreen()
                "dialer" -> DialerScreen()
                "alarm" -> AlarmScreen()
                "calendar" -> CalendarScreen()
                "location" -> LocationScreen()
                "animscale" -> AnimScaleScreen()
            }
        }
    }

    @Composable
    private fun InfoCard(text: String, color: Color = Color(0xFF00E5FF)) {
        Text(
            text, color = color, fontSize = 13.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(color.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                .padding(12.dp)
        )
    }

    // ===================== FLASHLIGHT =====================

    @Composable
    private fun FlashlightScreen() {
        val context = LocalContext.current
        var on by remember { mutableStateOf(false) }
        var strobe by remember { mutableStateOf(false) }
        val cm = remember { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
        val torchId = remember {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }
        val strobeHandler = remember { Handler(Looper.getMainLooper()) }

        fun setTorch(state: Boolean) {
            torchId?.let { runCatching { cm.setTorchMode(it, state) } }
            on = state
        }

        DisposableEffect(strobe) {
            val r = object : Runnable {
                override fun run() {
                    if (strobe) {
                        setTorch(!on)
                        strobeHandler.postDelayed(this, 150)
                    }
                }
            }
            if (strobe) strobeHandler.post(r)
            onDispose {
                strobeHandler.removeCallbacksAndMessages(null)
                setTorch(false)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("Torch + strobe modes. Works on the lock screen tile too.")
            BigButton(if (on) "⭕ Turn Off" else "💡 Turn On", Color(0xFFFFC400)) { setTorch(!on) }
            BigButton(
                if (strobe) "⏹ Stop Strobe" else "⚡ Strobe Mode",
                Color(0xFFFF6D00)
            ) { strobe = !strobe }
            if (torchId == null) InfoCard("No camera flash found on this device", Color(0xFFFF5252))
        }
    }

    // ===================== COMPASS =====================

    @SuppressLint("MissingPermission")
    @Composable
    private fun CompassScreen() {
        val context = LocalContext.current
        val sm = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
        var azimuth by remember { mutableStateOf(0f) }

        DisposableEffect(Unit) {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    if (e.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                        val r = FloatArray(9); val o = FloatArray(3)
                        SensorManager.getRotationMatrixFromVector(r, e.values)
                        SensorManager.getOrientation(r, o)
                        azimuth = Math.toDegrees(o[0].toDouble()).toFloat()
                    }
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
                sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
            }
            onDispose { sm.unregisterListener(listener) }
        }

        val dir = when (((azimuth + 360) % 360).toInt() / 45) {
            0 -> "N"; 1 -> "NE"; 2 -> "E"; 3 -> "SE"; 4 -> "S"; 5 -> "SW"; 6 -> "W"; else -> "NW"
        }
        val angle = ((azimuthFix(azimuth)) % 360).toInt()

        Column(
            Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            InfoCard("Live heading from the rotation-vector sensor.")
            Text(
                "$angle°",
                color = Color(0xFF00E5FF), fontSize = 72.sp, fontWeight = FontWeight.Bold
            )
            Text(dir, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text("Azimuth: ${"%.1f".format(azimuth)}°", color = Color.Gray, fontSize = 13.sp)
        }
    }

    private fun azimuthFix(a: Float): Float = ((a % 360) + 360) % 360

    // ===================== BATTERY =====================

    @Composable
    private fun BatteryScreen() {
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences("empire_prefs", Context.MODE_PRIVATE) }
        val sticky = context.registerReceiver(null, IntentFilterBattery())
        var alarmOn by remember {
            mutableStateOf(prefs.getBoolean("low_battery_alarm", false))
        }

        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val voltage = (sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000f
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0) level * 100 / scale else 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("Battery health + low-battery alarm that wails even in Doze.")
            Text(
                if (charging) "⚡ $pct%" else "🔋 $pct%",
                color = if (pct > 30) Color(0xFF69F0AE) else Color(0xFFFF5252),
                fontSize = 52.sp, fontWeight = FontWeight.Bold
            )
            Text("Temp: $temp°C • Voltage: ${"%.2f".format(voltage)}V • ${if (charging) "Charging" else "Discharging"}",
                color = Color.Gray, fontSize = 13.sp)
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF111827), RoundedCornerShape(12.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Low Battery Alarm", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Siren + vibration when battery goes low", color = Color.Gray, fontSize = 11.sp)
                }
                Switch(
                    checked = alarmOn,
                    onCheckedChange = {
                        alarmOn = it
                        prefs.edit().putBoolean("low_battery_alarm", it).apply()
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF00E5FF))
                )
            }
        }
    }

    private fun IntentFilterBattery(): android.content.IntentFilter =
        android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)

    // ===================== RAM CLEANER =====================

    @Composable
    private fun RamScreen() {
        val context = LocalContext.current
        val am = remember { context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager }
        var status by remember { mutableStateOf("") }

        fun refresh(): Pair<Long, Long> {
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            return mi.availMem to mi.totalMem
        }

        var mem by remember { mutableStateOf(refresh()) }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("Kills background apps to free RAM instantly. (Some system apps are protected — that's Android, not us.)")
            Text(
                "Available RAM",
                color = Color.Gray, fontSize = 13.sp
            )
            Text(
                "%.0f MB free".format(mem.first / (1024.0 * 1024.0)),
                color = Color(0xFFFF6D00), fontSize = 34.sp, fontWeight = FontWeight.Bold
            )
            Text(
                "of ${mem.second / (1024 * 1024)} MB total",
                color = Color.Gray, fontSize = 12.sp
            )
            if (status.isNotEmpty()) Text(status, color = Color(0xFF69F0AE), fontSize = 13.sp)
            BigButton("🚀 Boost Now", Color(0xFFFF6D00)) {
                val before = mem.first
                am.runningAppProcesses?.forEach { proc ->
                    if (proc.processName != context.packageName &&
                        proc.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                    ) {
                        runCatching { am.killBackgroundProcesses(proc.processName) }
                    }
                }
                Thread.sleep(400)
                mem = refresh()
                val freed = (mem.first - before) / (1024 * 1024)
                status = if (freed > 0) "✅ Freed ~$freed MB" else "✅ Already clean — nothing to kill"
            }
        }
    }

    // ===================== APP SCANNER (antivirus-lite) =====================

    @Composable
    private fun ScannerScreen() {
        val context = LocalContext.current
        var results by remember { mutableStateOf(listOf<Pair<String, Int>>()) }
        var scanned by remember { mutableStateOf(false) }

        val dangerous = setOf(
            Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS, Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.SYSTEM_ALERT_WINDOW
        )

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("Audits every installed app by its dangerous permissions and ranks the riskiest. Honest risk scoring — not fake 'antivirus'.")
            if (!scanned) {
                BigButton("🔍 Scan All Apps", Color(0xFFFF4081)) {
                    results = context.packageManager.getInstalledPackages(
                        PackageManager.GET_PERMISSIONS
                    ).map { pi ->
                        val perms = pi.requestedPermissions ?: emptyArray()
                        val score = perms.count { it in dangerous }
                        (pi.applicationInfo.loadLabel(context.packageManager).toString() to score)
                    }.sortedByDescending { it.second }
                    scanned = true
                }
            } else {
                Text("Top risky apps (score = dangerous permissions)", color = Color.Gray, fontSize = 12.sp)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(results.take(30)) { (name, score) ->
                        val color = when {
                            score >= 6 -> Color(0xFFFF1744)
                            score >= 3 -> Color(0xFFFFD600)
                            else -> Color(0xFF69F0AE)
                        }
                        Row(
                            Modifier.fillMaxWidth().background(Color(0xFF111827), RoundedCornerShape(10.dp)).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(name, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(
                                if (score >= 6) "⚠️ $score" else if (score >= 3) "⚡ $score" else "✅ $score",
                                color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    // ===================== WEATHER =====================

    @SuppressLint("MissingPermission")
    @Composable
    private fun WeatherScreen() {
        val context = LocalContext.current
        var weather by remember { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(false) }
        var err by remember { mutableStateOf<String?>(null) }

        fun loadWeather() {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                err = "Grant location permission first (Main Hub → Permissions)"
                return
            }
            loading = true; err = null
            Thread {
                try {
                    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    val loc: Location? = runCatching {
                        lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                            ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    }.getOrNull()
                    if (loc == null) {
                        err = "No location fix yet — open Maps once, then retry"
                    } else {
                        val url = "https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}" +
                                "&longitude=${loc.longitude}" +
                                "&current=temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code"
                        val conn = URL(url).openConnection() as HttpURLConnection
                        conn.connectTimeout = 10_000; conn.readTimeout = 10_000
                        val json = JSONObject(conn.inputStream.bufferedReader().readText())
                        val cur = json.getJSONObject("current")
                        val temp = cur.getDouble("temperature_2m")
                        val hum = cur.getInt("relative_humidity_2m")
                        val wind = cur.getDouble("wind_speed_10m")
                        val code = cur.getInt("weather_code")
                        val desc = when {
                            code == 0 -> "Clear sky ☀️"
                            code in 1..3 -> "Partly cloudy ⛅"
                            code in 45..48 -> "Foggy 🌫️"
                            code in 51..67 -> "Rainy 🌧️"
                            code in 71..77 -> "Snowy ❄️"
                            code >= 80 -> "Stormy ⛈️"
                            else -> "Cloudy ☁️"
                        }
                        weather = "$desc\n$temp°C • 💧$hum% • 💨${wind}km/h"
                    }
                } catch (e: Exception) {
                    err = "Weather fetch failed: ${e.message}"
                }
                loading = false
            }.start()
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("Live local weather via Open-Meteo (free, no API key).")
            if (loading) CircularProgressIndicator(color = Color(0xFF40C4FF))
            weather?.let {
                Text(it, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(Color(0xFF111827), RoundedCornerShape(14.dp)).padding(18.dp))
            }
            err?.let { Text(it, color = Color(0xFFFF5252), fontSize = 13.sp) }
            BigButton("🔄 Refresh Weather", Color(0xFF40C4FF)) { loadWeather() }
        }
    }

    // ===================== STEP COUNTER =====================

    @Composable
    private fun StepsScreen() {
        val context = LocalContext.current
        val sm = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
        val prefs = remember { context.getSharedPreferences("empire_prefs", Context.MODE_PRIVATE) }
        var steps by remember { mutableStateOf(0) }

        DisposableEffect(Unit) {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    val total = e.values.first().toInt()
                    val baseline = prefs.getInt("step_baseline", -1)
                    if (baseline < 0) prefs.edit().putInt("step_baseline", total).apply()
                    steps = total - prefs.getInt("step_baseline", total)
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
                sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
            }
            onDispose { sm.unregisterListener(listener) }
        }

        Column(
            Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InfoCard("Hardware step sensor — counts since today's first open.")
            Text("👣", fontSize = 56.sp)
            Text("$steps", color = Color(0xFFB388FF), fontSize = 64.sp, fontWeight = FontWeight.Bold)
            Text("steps today", color = Color.Gray)
            BigButton("🔄 Reset Daily Count", Color(0xFFB388FF)) {
                prefs.edit().putInt("step_baseline", -1).apply()
                steps = 0
            }
        }
    }

    // ===================== EYE CARE =====================

    @Composable
    private fun EyeCareScreen() {
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences("empire_prefs", Context.MODE_PRIVATE) }
        var on by remember { mutableStateOf(EyeCareService.isRunning(context)) }
        var intensity by remember { mutableStateOf(prefs.getInt("eye_care_intensity", 40)) }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("Blue-light filter overlay + 20-20-20 break reminders. Needs 'Display over other apps'.")
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF111827), RoundedCornerShape(12.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Eye Care Filter", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Warm overlay + break reminders", color = Color.Gray, fontSize = 11.sp)
                }
                Switch(
                    checked = on,
                    onCheckedChange = {
                        on = it
                        prefs.edit().putBoolean("eye_care_enabled", it).apply()
                        if (it) EyeCareService.start(context) else EyeCareService.stop(context)
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF64DD17))
                )
            }
            Text("Filter intensity: $intensity%", color = Color.Gray, fontSize = 13.sp)
            Slider(
                value = intensity.toFloat(),
                onValueChange = {
                    intensity = it.toInt()
                    prefs.edit().putInt("eye_care_intensity", intensity).apply()
                },
                valueRange = 10f..80f,
                colors = SliderDefaults.colors(thumbColor = Color(0xFF64DD17), activeTrackColor = Color(0xFF64DD17))
            )
            if (on) Text("✅ Active — you should see the warm tint now", color = Color(0xFF64DD17), fontSize = 13.sp)
        }
    }

    // ===================== SIREN / FIND MY PHONE =====================

    @Composable
    private fun SirenScreen() {
        val context = LocalContext.current
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("Ear-piercing siren + flashing strobe. Same alarm fires on low battery, SIM change and wrong password (Guardian).")
            BigButton("📢 TEST THE SIREN NOW", Color(0xFFFF1744)) {
                SirenService.start(context, mode = SirenService.MODE_FIND_PHONE, autoStopSeconds = 15)
            }
            BigButton("⏹ Stop", Color(0xFF37474F)) { SirenService.stop(context) }
        }
    }

    // ===================== VOICE FX =====================

    @Composable
    private fun VoiceFxScreen() {
        val context = LocalContext.current
        var recording by remember { mutableStateOf(false) }
        var recorded by remember { mutableStateOf(false) }
        var effect by remember { mutableStateOf(0) }
        var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
        val file = remember { File(context.cacheDir, "voicefx.m4a") }
        var player by remember { mutableStateOf<MediaPlayer?>(null) }

        val effects = listOf("🎤 Normal", "🤖 Robot", "🐿️ Chipmunk", "🐻 Deep Bear", "🐢 Slow Motion", "🏃 Fast Talker")

        DisposableEffect(Unit) {
            onDispose {
                runCatching { if (recording) recorder?.stop() }
                runCatching { recorder?.release() }
                player?.release()
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("Record your voice and play it back with effects. (Note: Android does not allow modifying your voice DURING a phone call — this is the honest alternative.)")
            if (recording) {
                Text("🔴 Recording… speak now!", color = Color(0xFFFF1744), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                BigButton("⏹ Stop Recording", Color(0xFFFF1744)) {
                    runCatching { recorder?.stop(); recorder?.release() }
                    recorder = null
                    recording = false; recorded = true
                }
            } else {
                BigButton("🎙️ Record Voice", Color(0xFF7C4DFF)) {
                    runCatching {
                        file.delete()
                        val r = MediaRecorder()
                        r.setAudioSource(MediaRecorder.AudioSource.MIC)
                        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        r.setOutputFile(file.absolutePath)
                        r.prepare(); r.start()
                        recorder = r
                        recording = true
                    }
                }
            }
            if (recorded) {
                Text("Effect:", color = Color.Gray, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    effects.forEachIndexed { i, label ->
                        Box(
                            Modifier
                                .weight(1f)
                                .border(
                                    if (effect == i) 2.dp else 1.dp,
                                    if (effect == i) Color(0xFF7C4DFF) else Color(0xFF2A3A4A),
                                    RoundedCornerShape(8.dp)
                                )
                                .background(
                                    if (effect == i) Color(0xFF231455) else Color(0xFF111827),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { effect = i }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, color = if (effect == i) Color(0xFF7C4DFF) else Color.Gray, fontSize = 9.sp)
                        }
                    }
                }
                BigButton("▶️ Play with Effect", Color(0xFF00E5FF)) {
                    player?.release()
                    player = MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        prepare()
                        playbackParams = playbackParams.setSpeed(
                            when (effect) {
                                4 -> 0.6f; 5 -> 1.6f; else -> 1f
                            }
                        ).setPitch(
                            when (effect) {
                                1 -> 0.4f; 2 -> 2.5f; 3 -> 0.5f; else -> 1f
                            }
                        )
                        start()
                    }
                }
            }
        }
    }

    // ===================== SOUND METER =====================

    @Composable
    private fun SoundMeterScreen() {
        val context = LocalContext.current
        var db by remember { mutableStateOf(0f) }
        var running by remember { mutableStateOf(false) }
        val handler = remember { Handler(Looper.getMainLooper()) }
        var recorder by remember { mutableStateOf<MediaRecorder?>(null) }

        DisposableEffect(Unit) {
            onDispose {
                runCatching { recorder?.stop(); recorder?.release() }
                handler.removeCallbacksAndMessages(null)
            }
        }

        val poll = object : Runnable {
            override fun run() {
                recorder?.let { r ->
                    val amp = try { r.maxAmplitude } catch (e: Exception) { 0 }
                    db = if (amp > 0) (20 * kotlin.math.log10(amp.toDouble())).toFloat() else 0f
                }
                if (running) handler.postDelayed(this, 100)
            }
        }

        Column(
            Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InfoCard("Live microphone dB meter. ~30dB quiet room, ~70dB street, ~100dB very loud.")
            Text(
                "%.0f dB".format(db),
                color = when {
                    db > 85 -> Color(0xFFFF1744)
                    db > 65 -> Color(0xFFFFD600)
                    else -> Color(0xFF69F0AE)
                },
                fontSize = 60.sp, fontWeight = FontWeight.Bold
            )
            Box(Modifier.fillMaxWidth().height(14.dp).background(Color(0xFF111827), RoundedCornerShape(7.dp))) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(db.coerceIn(0f, 120f) / 120f)
                        .background(
                            Brush.horizontalGradient(listOf(Color(0xFF69F0AE), Color(0xFFFFD600), Color(0xFFFF1744))),
                            RoundedCornerShape(7.dp)
                        )
                )
            }
            BigButton(if (running) "⏹ Stop" else "🎤 Start Meter", Color(0xFFFFD600)) {
                running = !running
                if (running) {
                    runCatching {
                        val r = MediaRecorder()
                        r.setAudioSource(MediaRecorder.AudioSource.MIC)
                        r.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                        r.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                        r.setOutputFile(File(context.cacheDir, "meter.tmp").absolutePath)
                        r.prepare(); r.start()
                        recorder = r
                    }
                    handler.post(poll)
                } else {
                    runCatching { recorder?.stop(); recorder?.release() }
                    recorder = null
                }
            }
        }
    }

    // ===================== DIALER =====================

    @SuppressLint("MissingPermission")
    @Composable
    private fun DialerScreen() {
        val context = LocalContext.current
        var number by remember { mutableStateOf("") }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("Quick dial with call-log access.")
            OutlinedTextField(
                value = number, onValueChange = { number = it },
                label = { Text("Phone number") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("📞 Call", Color(0xFF00BFA5), modifier = Modifier.weight(1f)) {
                    if (number.isNotBlank()) {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_CALL, android.net.Uri.parse("tel:$number"))
                            )
                        }
                    }
                }
                BigButton("🙂 Dial", Color(0xFF448AFF), modifier = Modifier.weight(1f)) {
                    if (number.isNotBlank()) {
                        context.startActivity(
                            Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$number"))
                        )
                    }
                }
            }
        }
    }

    // ===================== ALARM =====================

    @Composable
    private fun AlarmScreen() {
        val context = LocalContext.current
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("Hands off to the stock Clock app — the most reliable alarms on Android.")
            var hour by remember { mutableStateOf("7") }
            var minute by remember { mutableStateOf("0") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = hour, onValueChange = { hour = it },
                    label = { Text("Hour") }, modifier = Modifier.weight(1f), singleLine = true
                )
                OutlinedTextField(
                    value = minute, onValueChange = { minute = it },
                    label = { Text("Minute") }, modifier = Modifier.weight(1f), singleLine = true
                )
            }
            BigButton("⏰ Set Alarm", Color(0xFFFF9100)) {
                val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour.toIntOrNull() ?: 7)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute.toIntOrNull() ?: 0)
                    putExtra(AlarmClock.EXTRA_MESSAGE, "Neverhide Empire Alarm")
                }
                runCatching { context.startActivity(i) }
            }
            BigButton("⏳ Set Timer (5 min)", Color(0xFFFFD600)) {
                val i = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, 300)
                    putExtra(AlarmClock.EXTRA_MESSAGE, "Empire Timer")
                }
                runCatching { context.startActivity(i) }
            }
        }
    }

    // ===================== CALENDAR =====================

    @SuppressLint("MissingPermission")
    @Composable
    private fun CalendarScreen() {
        val context = LocalContext.current
        var events by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        var loaded by remember { mutableStateOf(false) }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("Your next calendar events at a glance.")
            if (!loaded) {
                BigButton("📅 Load Next Events", Color(0xFF448AFF)) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        Thread {
                            val projection = arrayOf(
                                CalendarContract.Events.TITLE,
                                CalendarContract.Events.DTSTART
                            )
                            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                                .appendPath(System.currentTimeMillis().toString())
                                .appendPath((System.currentTimeMillis() + 7L * 24 * 3600 * 1000).toString())
                                .build()
                            val list = mutableListOf<Pair<String, String>>()
                            runCatching {
                                context.contentResolver.query(
                                    uri, projection, null, null,
                                    CalendarContract.Instances.BEGIN + " ASC"
                                )?.use { c ->
                                    while (c.moveToNext() && list.size < 10) {
                                        val title = c.getString(0) ?: "(untitled)"
                                        val start = c.getLong(1)
                                        list.add(title to SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(start)))
                                    }
                                }
                            }
                            events = list
                            loaded = true
                        }.start()
                    }
                }
            } else if (events.isEmpty()) {
                Text("No events in the next 7 days 🎉", color = Color.Gray)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(events) { (title, when_) ->
                        Row(
                            Modifier.fillMaxWidth().background(Color(0xFF111827), RoundedCornerShape(10.dp)).padding(12.dp)
                        ) {
                            Column {
                                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(when_, color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // ===================== LOCATION =====================

    @SuppressLint("MissingPermission")
    @Composable
    private fun LocationScreen() {
        val context = LocalContext.current
        var locText by remember { mutableStateOf<String?>(null) }
        var loc by remember { mutableStateOf<Location?>(null) }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("GPS position — copy or share it. This is your phone tracker's location source.")
            BigButton("📍 Get My Location", Color(0xFFFF5252)) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    locText = "Grant location permission first (Main Hub → Permissions)"
                    return@BigButton
                }
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val l = runCatching {
                    lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                }.getOrNull()
                loc = l
                locText = if (l != null) {
                    "%.5f, %.5f\n±${"%.0f".format(l.accuracy)}m • ${l.provider}".format(l.latitude, l.longitude)
                } else "No fix yet — try again outdoors"
            }
            locText?.let {
                Text(it, color = Color.White, fontSize = 17.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.background(Color(0xFF111827), RoundedCornerShape(12.dp)).fillMaxWidth().padding(14.dp))
            }
            if (loc != null) {
                BigButton("📤 Share Location", Color(0xFF00E5FF)) {
                    val uri = android.net.Uri.parse("geo:${loc!!.latitude},${loc!!.longitude}")
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
            }
        }
    }

    // ===================== ANIMATION SCALE =====================

    @Composable
    private fun AnimScaleScreen() {
        val context = LocalContext.current
        var current by remember { mutableStateOf("1.0") }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("Global animation speed (0x = instant). Changing it needs WRITE_SECURE_SETTINGS — via Shizuku or ADB. Reading always works.")
            fun read() {
                current = runCatching {
                    Settings.Global.getFloat(context.contentResolver,
                        Settings.Global.ANIMATOR_DURATION_SCALE, 1f).toString()
                }.getOrDefault("1.0")
            }
            LaunchedEffect(Unit) { read() }
            Text("Current scale: $current", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            listOf("0x — Instant" to "0", "0.5x — Snappy" to "0.5", "1x — Default" to "1").forEach { (label, value) ->
                BigButton(label, Color(0xFFB0BEC5)) {
                    try {
                        Runtime.getRuntime().exec(
                            arrayOf("sh", "-c",
                                "settings put global animator_duration_scale $value; " +
                                "settings put global window_animation_scale $value; " +
                                "settings put global transition_animation_scale $value")
                        ).waitFor()
                    } catch (_: Exception) {}
                    read()
                }
            }
            Text("If the value didn't change, run Shizuku once, then tap again — Shizuku gives the shell WRITE_SECURE_SETTINGS.", color = Color.Gray, fontSize = 11.sp)
        }
    }

    // ===================== SHARED BUTTON =====================

    @Composable
    private fun BigButton(
        label: String,
        color: Color,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        Button(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = color.copy(alpha = 0.15f)),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.6f))
        ) {
            Text(label, color = color, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}
