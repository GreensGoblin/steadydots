package uk.co.steadydots

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Settings screen: permission, start/stop, and appearance sliders. Built in code, no XML. */
class MainActivity : Activity() {

    private lateinit var permButton: Button
    private lateinit var toggleButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        col.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "Dots float over whatever you're doing and drift with the vehicle's motion, " +
                "so what your eyes see matches what your inner ear feels."
            textSize = 15f
            setPadding(0, dp(8), 0, dp(16))
        })

        permButton = Button(this).apply {
            text = "Allow \"display over other apps\""
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }
        col.addView(permButton)

        toggleButton = Button(this).apply {
            setOnClickListener {
                if (OverlayService.isRunning) OverlayService.stop(this@MainActivity)
                else OverlayService.start(this@MainActivity)
                postDelayed({ refresh() }, 300)
            }
        }
        col.addView(toggleButton)

        col.addView(header("Appearance"))
        slider(col, "Dot size", Prefs.KEY_SIZE, 4, 16, Prefs.DEF_SIZE) { "$it dp" }
        slider(col, "Spacing", Prefs.KEY_SPACING, 32, 120, Prefs.DEF_SPACING) { "$it dp" }
        slider(col, "Columns per edge", Prefs.KEY_COLUMNS, 1, 4, Prefs.DEF_COLUMNS) { "$it" }
        slider(col, "Opacity", Prefs.KEY_OPACITY, 20, 100, Prefs.DEF_OPACITY) { "$it%" }
        toggle(col, "Keep dots to the screen edges", Prefs.KEY_EDGES, true)
        toggle(col, "Use lines instead of dots", Prefs.KEY_LINES, false)

        col.addView(header("Motion"))
        slider(col, "Sensitivity (how far)", Prefs.KEY_SENSITIVITY, 25, 250, Prefs.DEF_SENSITIVITY) { "$it%" }
        slider(col, "Responsiveness (how fast)", Prefs.KEY_RESPONSE, 20, 100, Prefs.DEF_RESPONSE) { "$it%" }

        col.addView(TextView(this).apply {
            text = "Tips\n" +
                "• Works best with the phone roughly facing you, the way you'd normally hold it.\n" +
                "• Add the \"Motion dots\" tile to Quick Settings to switch on without opening the app.\n" +
                "• Give the phone a gentle push sideways while this screen is open to test it."
            textSize = 14f
            setPadding(0, dp(24), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(col)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val allowed = Settings.canDrawOverlays(this)
        permButton.visibility = if (allowed) View.GONE else View.VISIBLE
        toggleButton.isEnabled = allowed
        toggleButton.text = if (OverlayService.isRunning) "Stop dots" else "Start dots"
    }

    // ---------------------------------------------------------------- small UI helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(8))
    }

    private fun slider(
        parent: LinearLayout, label: String, key: String,
        min: Int, max: Int, def: Int, fmt: (Int) -> String,
    ) {
        val prefs = Prefs.get(this)
        val start = prefs.getInt(key, def)
        val tv = TextView(this).apply { text = "$label: ${fmt(start)}" }
        val sb = SeekBar(this).apply {
            this.max = max - min
            progress = start - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + min
                    tv.text = "$label: ${fmt(v)}"
                    prefs.edit().putInt(key, v).apply()   // service picks this up live
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        parent.addView(tv)
        parent.addView(sb)
    }

    private fun toggle(parent: LinearLayout, label: String, key: String, def: Boolean) {
        val prefs = Prefs.get(this)
        parent.addView(Switch(this).apply {
            text = label
            isChecked = prefs.getBoolean(key, def)
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
        })
    }
}
