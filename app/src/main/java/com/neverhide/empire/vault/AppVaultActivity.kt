package com.neverhide.empire.vault

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Build
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neverhide.empire.cleaner.FreezerEngine
import com.neverhide.empire.dashboard.GlassCard
import com.neverhide.empire.dashboard.GlowButton
import com.neverhide.empire.dashboard.Palette
import com.neverhide.empire.dashboard.StatusChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * 🔐 APP VAULT — device-level app locks for the owner's own phone.
 *
 * Locking uses the v2.6.x battle-tested FreezerEngine (pm disable-user via
 * Shizuku or Device Owner). A locked app CANNOT start — not from the
 * launcher, not from another app. The lock is a device-level OS state:
 * it survives reboots and even the Empire being uninstalled. Unlocking
 * requires opening the Vault, entering the PIN, and firing the release
 * artillery.
 *
 * Auto re-lock: every time the Vault opens, any vault app found unlocked
 * is frozen again automatically — so a borrowed phone re-locks itself
 * the moment the owner's session ends.
 *
 * Honest limits (documented, not hidden): a factory reset clears every
 * device-level state including these locks — that wall belongs to Google
 * (FRP), not to any app. Everything short of a reset is defended.
 */
class AppVaultActivity : ComponentActivity() {
    private var onCredentialSuccess: (() -> Unit)? = null
    private val credentialResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = onCredentialSuccess
        onCredentialSuccess = null
        if (result.resultCode == RESULT_OK) callback?.invoke()
    }

    fun verifyScreenLock(onSuccess: () -> Unit, onUnavailable: () -> Unit) {
        val manager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!manager.isDeviceSecure) { onUnavailable(); return }
        @Suppress("DEPRECATION")
        val intent = manager.createConfirmDeviceCredentialIntent(
            "Verify phone owner", "Use the phone's screen lock to reset the Vault PIN"
        )
        if (intent == null) { onUnavailable(); return }
        onCredentialSuccess = onSuccess
        credentialResult.launch(intent)
    }

    fun verifyBiometric(onSuccess: () -> Unit, onUnavailable: () -> Unit) {
        if (Build.VERSION.SDK_INT < 28) { onUnavailable(); return }
        val builder = BiometricPrompt.Builder(this)
            .setTitle("Unlock Neverhide Vault")
            .setSubtitle("Use your phone's enrolled fingerprint or supported face")
            .setNegativeButton("Use Vault PIN", mainExecutor) { _, _ -> }
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG)
        }
        val prompt = builder.build()
        try {
            prompt.authenticate(CancellationSignal(), mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onSuccess()
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                            errorCode != 13 /* negative button */) onUnavailable()
                    }
                })
        } catch (_: Exception) { onUnavailable() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VaultScreen() }
    }
}

/** Shared vault prefs keys — used by the Vault, the Freezer, and the Empire patrol. */
object VaultStore {
    const val PREFS = "app_vault"
    const val KEY_APPS = "vault_apps"
    const val KEY_PIN = "pin_hash"

    fun vaultApps(context: android.content.Context): Set<String> =
        context.getSharedPreferences(VaultStore.PREFS, android.content.Context.MODE_PRIVATE)
            .getStringSet(VaultStore.KEY_APPS, emptySet()) ?: emptySet()
}

private const val SALT = "neverhide-empire-vault-v1"

private fun pinHash(pin: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest((SALT + pin).toByteArray())
        .joinToString("") { "%02x".format(it) }

@Composable
private fun VaultScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(VaultStore.PREFS, Context.MODE_PRIVATE) }

    var vaultApps by remember { mutableStateOf(prefs.getStringSet(VaultStore.KEY_APPS, emptySet()) ?: emptySet()) }
    var pinSaved by remember { mutableStateOf(prefs.getString(VaultStore.KEY_PIN, null) != null) }
    var pinSetup by remember { mutableStateOf("") }
    var pinPrompt by remember { mutableStateOf<String?>(null) }   // package pending PIN-gated action
    var pinAction by remember { mutableStateOf("unlock") }
    var pinEntry by remember { mutableStateOf("") }
    var resetOpen by remember { mutableStateOf(false) }
    var resetText by remember { mutableStateOf("") }
    val activity = context as AppVaultActivity
    var vaultError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val apps = remember { FreezerEngine.listFreezableApps(context) }
    val labelOf = remember(apps) {
        apps.associateWith { it.loadLabel(context.packageManager).toString() }
    }
    var frozenNow by remember { mutableStateOf<Set<String>>(emptySet()) }
    val mode = remember { FreezerEngine.powerMode(context) }

    fun saveApps() {
        prefs.edit().putStringSet(VaultStore.KEY_APPS, vaultApps).apply()
    }

    fun freeze(pkg: String) {
        busy = true
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching { FreezerEngine.setSuspended(context, pkg, true) }
                    .getOrElse { it.message ?: it.javaClass.simpleName }
            }
            frozenNow = withContext(Dispatchers.IO) { FreezerEngine.realFrozenNow(context) }
            if (err != null) vaultError = "${labelOf[apps.firstOrNull { it.packageName == pkg }] ?: pkg}: $err"
            busy = false
        }
    }

    fun unfreeze(pkg: String, onReleased: (() -> Unit)? = null) {
        busy = true
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching { FreezerEngine.setSuspended(context, pkg, false) }
                    .getOrElse { it.message ?: it.javaClass.simpleName }
            }
            frozenNow = withContext(Dispatchers.IO) { FreezerEngine.realFrozenNow(context) }
            if (err != null) vaultError = "${labelOf[apps.firstOrNull { it.packageName == pkg }] ?: pkg}: $err"
            else onReleased?.invoke()
            busy = false
        }
    }

    // Load real state + AUTO RE-LOCK: any vault app found unlocked freezes
    // again the moment the Vault opens.
    LaunchedEffect(Unit) {
        frozenNow = withContext(Dispatchers.IO) { FreezerEngine.realFrozenNow(context) }
        val strays = vaultApps.filter { it !in frozenNow }
        for (pkg in strays) {
            if (!FreezerEngine.hasPower(context)) break
            withContext(Dispatchers.IO) { FreezerEngine.setSuspended(context, pkg, true) }
        }
        frozenNow = withContext(Dispatchers.IO) { FreezerEngine.realFrozenNow(context) }
    }

    val vaultFrozen = vaultApps.intersect(frozenNow)
    val vaultOpen = vaultApps - frozenNow

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF07090F))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("🔐 APP VAULT", color = Palette.WHITE, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Device-level locks — locked apps cannot start at all",
                color = Palette.TEXT_MUTE, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))

            GlassCard(Modifier.fillMaxWidth(), glow = Palette.CYAN) {
                Text(
                    "Power: $mode  •  ${vaultFrozen.size} locked  •  ${vaultOpen.size} unlocked",
                    color = Palette.TEXT_DIM, fontSize = 11.sp
                )
                if (mode == "OFF") {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Start Shizuku (wireless debugging) or run the Device Owner ADB command once, then come back.",
                        color = Palette.TEXT_MUTE, fontSize = 11.sp
                    )
                }
                if (vaultError != null) {
                    Spacer(Modifier.height(4.dp))
                    Text("⚠ $vaultError", color = Palette.PINK, fontSize = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlowButton("🔒 Lock All", listOf(Color(0xFF37474F), Color(0xFF263238)), Modifier.weight(1f), enabled = !busy) {
                        vaultApps.forEach { freeze(it) }
                    }
                    if (pinPrompt == null) {
                        GlowButton("🔄 Re-check", listOf(Color(0xFF37474F), Color(0xFF263238)), Modifier.weight(1f), enabled = !busy) {
                            scope.launch {
                                frozenNow = withContext(Dispatchers.IO) { FreezerEngine.realFrozenNow(context) }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // PIN setup (once)
            if (!pinSaved) {
                GlassCard(Modifier.fillMaxWidth(), glow = Palette.AMBER) {
                    Text("Set your Vault PIN first (4-6 digits):",
                        color = Palette.AMBER, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    TextField(
                        value = pinSetup,
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pinSetup = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    GlowButton("💾 Save PIN", listOf(Palette.AMBER, Color(0xFFFF8F00)), Modifier.fillMaxWidth(), enabled = pinSetup.length >= 4) {
                        prefs.edit().putString(VaultStore.KEY_PIN, pinHash(pinSetup)).apply()
                        pinSaved = true
                        pinSetup = ""
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // Recovery requires the Android screen lock; knowing a public word
            // alone must never unlock another person's frozen apps.
            if (pinSaved) {
                if (!resetOpen) {
                    Text(
                        "Forgot PIN?",
                        color = Palette.TEXT_MUTE, fontSize = 11.sp,
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .clickable { resetOpen = true; resetText = "" }
                    )
                } else {
                    GlassCard(Modifier.fillMaxWidth(), glow = Palette.PINK) {
                        Text("🔥 EMERGENCY RESET",
                            color = Palette.PINK, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Clears the Vault PIN and list, then unfreezes vault apps. Your app data stays intact. You must verify with your phone screen lock. Type RESET to confirm.",
                            color = Palette.TEXT_MUTE, fontSize = 10.sp)
                        Spacer(Modifier.height(6.dp))
                        TextField(
                            value = resetText,
                            onValueChange = { resetText = it.uppercase().take(5) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlowButton("🔥 Reset", listOf(Palette.PINK, Color(0xFFD50000)), Modifier.weight(1f),
                                enabled = resetText == "RESET") {
                                val old = vaultApps.toSet()
                                activity.verifyScreenLock(onSuccess = {
                                    // Keep the list until each app is confirmed unfrozen.
                                    scope.launch {
                                        busy = true
                                        val failures = withContext(Dispatchers.IO) {
                                            old.mapNotNull { pkg ->
                                                val error = runCatching { FreezerEngine.setSuspended(context, pkg, false) }
                                                    .getOrElse { it.message ?: "unfreeze failed" }
                                                if (error != null) pkg else null
                                            }
                                        }
                                        if (failures.isEmpty()) {
                                            prefs.edit().remove(VaultStore.KEY_PIN).remove(VaultStore.KEY_APPS).apply()
                                            pinSaved = false
                                            vaultApps = emptySet()
                                            resetText = ""
                                            resetOpen = false
                                        } else vaultError = "Could not release: ${failures.joinToString()}. PIN and list kept."
                                        frozenNow = withContext(Dispatchers.IO) { FreezerEngine.realFrozenNow(context) }
                                        busy = false
                                    }
                                }, onUnavailable = { vaultError = "Set a phone screen lock first to recover the Vault PIN." })
                            }
                            GlowButton("Cancel", listOf(Color(0xFF37474F), Color(0xFF263238)), Modifier.weight(1f)) {
                                resetOpen = false; resetText = ""
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // PIN prompt (unlock / remove)
            if (pinPrompt != null) {
                val pkg = pinPrompt!!
                GlassCard(Modifier.fillMaxWidth(), glow = Palette.PINK) {
                    Text(
                        (if (pinAction == "unlock") "🔓 Unlock" else "✖ Remove") +
                            " " + (labelOf[apps.firstOrNull { it.packageName == pkg }] ?: pkg) + " — enter PIN:",
                        color = Palette.WHITE, fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    TextField(
                        value = pinEntry,
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pinEntry = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlowButton("✅ Confirm", listOf(Palette.GREEN, Color(0xFF00E676)), Modifier.weight(1f), enabled = pinEntry.isNotEmpty()) {
                            val stored = prefs.getString(VaultStore.KEY_PIN, null)
                            if (stored != null && pinHash(pinEntry) == stored) {
                                if (pinAction == "unlock") unfreeze(pkg) else {
                                    unfreeze(pkg) {
                                        vaultApps = vaultApps - pkg
                                        saveApps()
                                    }
                                }
                                pinPrompt = null; pinEntry = ""
                            } else {
                                vaultError = "wrong PIN"
                            }
                        }
                        GlowButton("Cancel", listOf(Color(0xFF37474F), Color(0xFF263238)), Modifier.weight(1f)) {
                            pinPrompt = null; pinEntry = ""
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 28) {
                        Spacer(Modifier.height(6.dp))
                        GlowButton("☝ Fingerprint / phone face", listOf(Palette.CYAN, Palette.PINK), Modifier.fillMaxWidth(), enabled = !busy) {
                            // Capture current action, then clear pending operation before callback.
                            val target = pkg
                            val action = pinAction
                            activity.verifyBiometric(onSuccess = {
                                if (pinPrompt == target && pinAction == action && !busy) {
                                    if (action == "unlock") unfreeze(target) else {
                                        unfreeze(target) {
                                            vaultApps = vaultApps - target
                                            saveApps()
                                        }
                                    }
                                    pinPrompt = null; pinEntry = ""
                                }
                            }, onUnavailable = { vaultError = "Phone biometric unavailable. Use your Vault PIN." })
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            val lockedList = apps.filter { vaultApps.contains(it.packageName) }
                .sortedBy { labelOf[it] }
            val freeList = apps.filter { !vaultApps.contains(it.packageName) }
                .sortedBy { labelOf[it] }

            if (lockedList.isNotEmpty()) {
                Text("🔒 LOCKED — ${lockedList.size}", color = Palette.PINK, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                lockedList.forEach { app ->
                    val pkg = app.packageName
                    val reallyFrozen = frozenNow.contains(pkg)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .background(
                                if (reallyFrozen) Palette.PINK.copy(alpha = 0.10f) else Palette.AMBER.copy(alpha = 0.10f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            (labelOf[app] ?: pkg),
                            color = if (reallyFrozen) Palette.TEXT_MUTE else Palette.WHITE,
                            fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1
                        )
                        StatusChip("🔓", Palette.GREEN, filled = false, onClick = {
                            if (!pinSaved) vaultError = "set your PIN first"
                            else { pinPrompt = pkg; pinAction = "unlock" }
                        })
                        Spacer(Modifier.width(4.dp))
                        StatusChip("✖", Palette.PINK, filled = false, onClick = {
                            if (!pinSaved) vaultError = "set your PIN first"
                            else { pinPrompt = pkg; pinAction = "remove" }
                        })
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Text("➕ TAP TO LOCK — ${freeList.size} apps", color = Palette.GREEN, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                items(freeList, key = { it.packageName }) { app ->
                    val pkg = app.packageName
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0x0DFFFFFF), RoundedCornerShape(12.dp))
                            .clickable(enabled = !busy && pinSaved && FreezerEngine.hasPower(context)) {
                                vaultApps = vaultApps + pkg
                                saveApps()
                                freeze(pkg)
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text((labelOf[app] ?: pkg), color = Palette.WHITE, fontSize = 12.sp,
                            modifier = Modifier.weight(1f), maxLines = 1)
                        Text("🔒 lock", color = Palette.TEXT_MUTE, fontSize = 10.sp)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Recovery needs your Android screen lock; typing RESET alone never unlocks apps. Fingerprint / phone face uses Android's enrolled biometrics, never stores biometric images here, and cannot replace the device's lock screen. If no biometrics are available, use your Vault PIN. Frozen apps may remain frozen after Empire uninstall; restore them before removing Empire.",
                color = Palette.TEXT_MUTE, fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
        }
    }
}
