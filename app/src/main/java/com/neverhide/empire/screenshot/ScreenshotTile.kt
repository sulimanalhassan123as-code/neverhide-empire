package com.neverhide.empire.screenshot

import android.content.Intent
import android.service.quicksettings.TileService
import com.neverhide.empire.MainActivity

/**
 * Quick Settings tile. Tapping it opens MainActivity, which requests the
 * MediaProjection consent dialog (required every capture session by Android).
 */
class ScreenshotTile : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("auto_capture", true)
        startActivityAndCollapse(intent)
    }
}
