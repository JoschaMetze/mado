package jp.co.xenocode.mado

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Puts Mado on the glass when the frame powers up, without replacing the launcher.
 * Works on the old Android versions frames ship with; Android 10 and later block activities
 * started from the background, and there Mado has to be opened by hand or made the home app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
