package com.neverhide.empire.ghost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 👻 THE GHOST GATE — dial *#*#2001#*#* in the phone dialer and the
 * Empire opens, even while the icon is hidden.
 *
 * Registered in the manifest for android.provider.Telephony.SECRET_CODE
 * (data android_secret_code://2001). Secret-code broadcasts are one of
 * the exempt implicit broadcasts, so this works from any dialer that
 * supports the *#*#...#*#* syntax (stock Samsung dialer does).
 *
 * SAFETY: the receiver ALWAYS reveals the icon — it can never lock the
 * owner out, it can only let him back in. It is also the pre-hide test:
 * dial the code while the icon is visible; if the Empire opens, the
 * gate is proven BEFORE hiding anything.
 */
class GhostGateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GhostMode.SECRET_ACTION) return
        GhostMode.reveal(context)
        GhostMode.openApp(context)
    }
}
