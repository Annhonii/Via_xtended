package com.example.profilebrowser

import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.MaterialColors
import java.util.Calendar
import java.util.Locale

object TimeUtil {

    /** Next time the clock shows [hour]:[minute] (today if still ahead, otherwise tomorrow). */
    fun nextOccurrence(hour: Int, minute: Int): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    fun format(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%dh %02dm %02ds", h, m, s)
        else String.format(Locale.US, "%dm %02ds", m, s)
    }
}

/** Shows "5h 59m 12s left" or "Ready ✓"; hides itself when there is no countdown. */
fun TextView.showCountdown(end: Long?) {
    if (end == null) {
        visibility = View.GONE
        return
    }
    visibility = View.VISIBLE
    val left = end - System.currentTimeMillis()
    if (left <= 0) {
        text = context.getString(R.string.countdown_ready)
        setTextColor(Color.parseColor("#2E7D32"))
    } else {
        text = context.getString(R.string.countdown_left, TimeUtil.format(left))
        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary))
    }
}

/**
 * Keeps this view clear of the status bar, navigation bar / gesture bar, display cutout
 * (notch / punch-hole) and the on-screen keyboard, so the layout fits every display.
 */
fun View.applySystemBarInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val i = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
        )
        v.setPadding(i.left, i.top, i.right, i.bottom)
        WindowInsetsCompat.CONSUMED
    }
}
