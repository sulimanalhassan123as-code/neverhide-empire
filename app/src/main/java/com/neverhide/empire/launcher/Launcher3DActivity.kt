package com.neverhide.empire.launcher

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.neverhide.empire.core.AppRepository
import kotlinx.coroutines.launch

/**
 * HOME activity that hosts the [Launcher3D] GL surface plus a small mode
 * switcher (Box / Circle / Sphere). Loads installed apps off the main thread.
 */
class Launcher3DActivity : ComponentActivity() {

    private lateinit var launcher: Launcher3D

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Show the live wallpaper behind the transparent GL surface.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER,
            WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
        )

        val root = FrameLayout(this)
        launcher = Launcher3D(this)
        root.addView(launcher)
        root.addView(buildModeSwitcher())
        setContentView(root)

        // Load apps asynchronously, then push them to the renderer.
        lifecycleScope.launch {
            val apps = AppRepository.loadApps(this@Launcher3DActivity)
            launcher.setTiles(apps)
        }
    }

    private fun buildModeSwitcher(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
        }
        fun modeButton(text: String, mode: Launcher3D.Mode) = Button(this).apply {
            this.text = text
            setOnClickListener { launcher.setMode(mode) }
        }
        bar.addView(modeButton("Box", Launcher3D.Mode.BOX))
        bar.addView(modeButton("Circle", Launcher3D.Mode.CIRCLE))
        bar.addView(modeButton("Sphere", Launcher3D.Mode.SPHERE))
        return bar
    }

    override fun onResume() { super.onResume(); launcher.onResume() }
    override fun onPause() { super.onPause(); launcher.onPause() }

    // A HOME activity should not exit on Back.
    override fun onBackPressed() { /* stay on launcher */ }
}
