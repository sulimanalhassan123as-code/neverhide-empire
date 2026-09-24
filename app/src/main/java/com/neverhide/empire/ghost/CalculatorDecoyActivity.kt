package com.neverhide.empire.ghost

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 🧮 CALCULATOR DECOY — this IS the Ghost Mode launcher icon.
 *
 * Looks and behaves like a plain, boring calculator (real arithmetic,
 * no tells). Typing the secret code and pressing "=" opens the Empire
 * instead of showing a result. Nothing on screen hints this exists —
 * a thief or snoop sees a working calculator and nothing else.
 *
 * Why a decoy instead of a vanished icon: dialer secret-code broadcasts
 * (*#*#code#*#*) are NOT honored by every OEM dialer — confirmed this
 * does not fire on the owner's Samsung stock dialer. A decoy app icon
 * has no OS/OEM dependency at all: it is a normal, always-present app.
 *
 * The dialer gate (GhostGateReceiver) stays wired as a bonus path for
 * phones where it does work; it is not the primary door anymore.
 */
class CalculatorDecoyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DecoyCalculatorScreen(onSecretMatch = { GhostMode.reveal(this); GhostMode.openApp(this); finish() }) }
    }
}

@Composable
private fun DecoyCalculatorScreen(onSecretMatch: () -> Unit) {
    var expr by remember { mutableStateOf("") }
    var display by remember { mutableStateOf("0") }

    fun evaluate(): Double? = try {
        // Minimal +,-,*,/ evaluator — enough for a believable calculator, no external deps.
        val tokens = Regex("(\\d+\\.?\\d*|[+\\-*/])").findAll(expr).map { it.value }.toList()
        if (tokens.isEmpty()) null else {
            val vals = ArrayDeque<Double>(); val ops = ArrayDeque<Char>()
            fun applyTop() { val b = vals.removeLast(); val a = vals.removeLast(); val op = ops.removeLast()
                vals.addLast(when (op) { '+' -> a + b; '-' -> a - b; '*' -> a * b; else -> if (b != 0.0) a / b else Double.NaN }) }
            var i = 0
            vals.addLast(tokens[0].toDouble()); i = 1
            while (i < tokens.size) {
                val op = tokens[i][0]
                val num = tokens[i + 1].toDouble()
                if (op == '*' || op == '/') {
                    val b = num; val a = vals.removeLast()
                    vals.addLast(if (op == '*') a * b else if (b != 0.0) a / b else Double.NaN)
                } else { ops.addLast(op); vals.addLast(num) }
                i += 2
            }
            while (ops.isNotEmpty()) applyTop()
            vals.last()
        }
    } catch (_: Exception) { null }

    fun press(key: String) {
        when (key) {
            "C" -> { expr = ""; display = "0" }
            "=" -> {
                if (expr.trim() == GhostMode.SECRET_HOST) { onSecretMatch(); return }
                val r = evaluate()
                display = if (r == null || r.isNaN()) "Error" else {
                    if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
                }
                expr = if (r != null && !r.isNaN()) display else ""
            }
            else -> { expr += key; display = expr }
        }
    }

    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Color(0xFF1B1B1B))) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Spacer(Modifier.weight(1f))
                Text(display, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Light,
                    modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Spacer(Modifier.height(20.dp))
                val rows = listOf(
                    listOf("C", "/", "*", "-"),
                    listOf("7", "8", "9", "+"),
                    listOf("4", "5", "6", "="),
                    listOf("1", "2", "3", "0")
                )
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { key ->
                            Button(
                                onClick = { press(key) },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (key in listOf("/", "*", "-", "+", "=")) Color(0xFF2962FF) else Color(0xFF2E2E2E)
                                ),
                                modifier = Modifier.weight(1f).height(64.dp)
                            ) { Text(key, color = Color.White, fontSize = 20.sp) }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}
