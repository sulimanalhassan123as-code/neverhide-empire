package com.neverhide.empire.wallpaper

import android.content.SharedPreferences
import android.opengl.GLSurfaceView
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

/**
 * WallpaperService backed by an OpenGL surface. Each visible engine hosts a
 * GLSurfaceView-like renderer. Rendering pauses when the wallpaper is hidden,
 * which is the key battery optimisation.
 *
 * The chosen effect is read from SharedPreferences ("wallpaper_effect"), so the app UI can switch between all 20 catalog effects.
 */
class LiveWallpaperEngine : WallpaperService() {

    override fun onCreateEngine(): Engine = GLEngine()

    private inner class GLEngine : Engine() {

        private lateinit var glSurface: WallpaperGLSurfaceView
        private var rendererStarted = false

        private inner class WallpaperGLSurfaceView : GLSurfaceView(this@LiveWallpaperEngine) {
            // WallpaperService gives us its own SurfaceHolder; expose it to GLSurfaceView.
            override fun getHolder(): SurfaceHolder = surfaceHolder
            fun destroy() { super.onDetachedFromWindow() }
        }

        override fun onCreate(holder: SurfaceHolder?) {
            super.onCreate(holder)
            val prefs: SharedPreferences =
                getSharedPreferences("empire_prefs", MODE_PRIVATE)
            val effectId = prefs.getInt("wallpaper_effect", 0)

            glSurface = WallpaperGLSurfaceView().apply {
                setEGLContextClientVersion(2)
                setRenderer(WallpaperRenderer(effectId))
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
            rendererStarted = true
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (!rendererStarted) return
            // Pause GL when not visible -> no battery drain in the background.
            if (visible) glSurface.onResume() else glSurface.onPause()
        }

        override fun onDestroy() {
            super.onDestroy()
            if (rendererStarted) glSurface.destroy()
        }
    }
}
