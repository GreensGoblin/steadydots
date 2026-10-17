package uk.co.steadydots

import android.content.Context
import android.content.SharedPreferences

/** All user-tweakable settings, stored in SharedPreferences. */
object Prefs {
    private const val FILE = "steadydots"

    const val KEY_SIZE = "size"               // dot diameter, dp
    const val KEY_SPACING = "spacing"         // gap between dots, dp
    const val KEY_COLUMNS = "columns"         // columns per screen edge
    const val KEY_OPACITY = "opacity"         // percent
    const val KEY_SENSITIVITY = "sensitivity" // percent
    const val KEY_RESPONSE = "response"       // percent: how quickly dots react
    const val KEY_EDGES = "edges_only"
    const val KEY_LINES = "lines"

    const val DEF_SIZE = 8
    const val DEF_SPACING = 64
    const val DEF_COLUMNS = 2
    const val DEF_OPACITY = 70
    const val DEF_SENSITIVITY = 100
    const val DEF_RESPONSE = 60

    data class Config(
        val sizeDp: Float,
        val spacingDp: Float,
        val columns: Int,
        val opacity: Float,      // 0..1
        val sensitivity: Float,  // 1.0 = default
        val response: Float,     // 0.2 (smooth, laggy) .. 1.0 (snappy)
        val edgesOnly: Boolean,
        val lines: Boolean,
    )

    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(ctx: Context): Config {
        val p = get(ctx)
        return Config(
            sizeDp = p.getInt(KEY_SIZE, DEF_SIZE).toFloat(),
            spacingDp = p.getInt(KEY_SPACING, DEF_SPACING).toFloat(),
            columns = p.getInt(KEY_COLUMNS, DEF_COLUMNS),
            opacity = p.getInt(KEY_OPACITY, DEF_OPACITY) / 100f,
            sensitivity = p.getInt(KEY_SENSITIVITY, DEF_SENSITIVITY) / 100f,
            response = p.getInt(KEY_RESPONSE, DEF_RESPONSE) / 100f,
            edgesOnly = p.getBoolean(KEY_EDGES, true),
            lines = p.getBoolean(KEY_LINES, false),
        )
    }
}
