package uk.co.steadydots

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/**
 * Invisible trampoline launched by the Quick Settings tile.
 * Starting the foreground service from a visible activity is always allowed,
 * which avoids Android 14/15's background-start restrictions on tiles.
 */
class ToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when {
            !Settings.canDrawOverlays(this) ->
                startActivity(Intent(this, MainActivity::class.java))
            OverlayService.isRunning -> OverlayService.stop(this)
            else -> OverlayService.start(this)
        }
        finish()
    }
}
