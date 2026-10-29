package com.example.galaxycamera

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {

    private fun prefs() = getSharedPreferences("galaxy_camera", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.BLACK)
        root.setPadding(40, 100, 40, 40)

        // Title row with back button
        val titleRow = LinearLayout(this)
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL
        val back = TextView(this)
        back.text = "<"
        back.setTextColor(Color.WHITE)
        back.textSize = 22f
        back.setPadding(0, 0, 36, 0)
        back.setOnClickListener { finish() }
        titleRow.addView(back)
        val title = TextView(this)
        title.text = "Settings"
        title.setTextColor(Color.WHITE)
        title.textSize = 22f
        title.typeface = Typeface.DEFAULT_BOLD
        titleRow.addView(title)
        root.addView(titleRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 40 })

        root.addView(header("General"))
        root.addView(switchRow("Volume key shutter", "set_volume_shutter", true))
        root.addView(switchRow("Shutter sound", "set_shutter_sound", true))
        root.addView(switchRow("Debug overlay (errors and steps)", "set_debug", false))

        root.addView(header("Camera features"))
        root.addView(switchRow("Upscale", "feat_upscale", true))
        root.addView(switchRow("Colour boost", "feat_colour", true))
        root.addView(switchRow("Skin tone protect", "feat_skin", true))
        root.addView(switchRow("Sharpen text and lines", "feat_sharpen", true))
        root.addView(switchRow("Noise reduction", "feat_denoise", true))

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.BLACK)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun header(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(Color.parseColor("#9AD0FF"))
        tv.textSize = 14f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.setPadding(0, 40, 0, 16)
        return tv
    }

    private fun switchRow(label: String, key: String, default: Boolean): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, 18, 0, 18)

        val tv = TextView(this)
        tv.text = label
        tv.setTextColor(Color.WHITE)
        tv.textSize = 16f
        row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val sw = Switch(this)
        sw.isChecked = prefs().getBoolean(key, default)
        sw.setOnCheckedChangeListener { _, checked ->
            prefs().edit().putBoolean(key, checked).apply()
        }
        row.addView(sw)
        return row
    }
}
