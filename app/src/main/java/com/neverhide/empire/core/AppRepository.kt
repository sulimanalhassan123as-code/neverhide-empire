package com.neverhide.empire.core

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import com.neverhide.empire.launcher.AppTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads every launchable app on the device and converts its icon into a
 * power-of-two [Bitmap] suitable for uploading as a GL texture.
 *
 * Runs on Dispatchers.IO so the launcher UI thread never blocks.
 */
object AppRepository {

    private const val ICON_SIZE = 128 // px, power-of-two friendly for GL

    suspend fun loadApps(context: Context): List<AppTile> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .distinctBy { it.activityInfo.packageName }
            .mapNotNull { info ->
                val pkg = info.activityInfo.packageName
                if (pkg == context.packageName) return@mapNotNull null // hide ourselves
                val label = info.loadLabel(pm).toString()
                val bitmap = info.loadIcon(pm).toBitmap(ICON_SIZE)
                AppTile(label = label, packageName = pkg, icon = bitmap)
            }
            .sortedBy { it.label.lowercase() }
    }

    /** Rasterise any Drawable (adaptive, vector, bitmap) into a square bitmap. */
    private fun Drawable.toBitmap(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        setBounds(0, 0, size, size)
        draw(canvas)
        return bmp
    }
}
