package com.stratbot.app.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.stratbot.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object Ui {
    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun col(ctx: Context, res: Int): Int = ContextCompat.getColor(ctx, res)

    fun txt(
        ctx: Context, text: String, size: Float = 14f, color: Int? = null,
        bold: Boolean = false, single: Boolean = false,
    ): TextView = TextView(ctx).apply {
        textSize = size
        this.text = text
        setTextColor(color ?: col(ctx, R.color.textPrimary))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (single) {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
    }

    fun button(ctx: Context, text: String, onClick: (View) -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 13f
            setTextColor(col(ctx, R.color.primary))
            setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.btn_pill)
            isClickable = true
            isFocusable = true
            setOnClickListener(onClick)
        }

    fun vline(ctx: Context): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(dp(ctx, 1), ViewGroup.LayoutParams.MATCH_PARENT)
    }

    fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun fmtTs(ms: Long): String =
        SimpleDateFormat("MMM dd, HH:mm", Locale.US).format(Date(ms))

    fun fmtDateTime(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

    fun money(v: Double): String {
        val a = abs(v)
        val body = when {
            a >= 1_000_000 -> String.format("%.0f", v)
            a >= 100 -> String.format("%.2f", v)
            a >= 1 -> String.format("%.4f", v)
            else -> String.format("%.6f", v)
        }
        return body
    }

    fun pct(v: Double): String = String.format("%+.2f%%", v)

    fun qty(v: Double): String = String.format("%.6f", v)

    fun pnlColor(ctx: Context, v: Double): Int =
        col(ctx, if (v > 0) R.color.up else if (v < 0) R.color.down else R.color.textSecondary)

    /** small section heading */
    fun section(ctx: Context, title: String): TextView =
        txt(ctx, title.uppercase(Locale.US), 11f, col(ctx, R.color.textSecondary), bold = true).apply {
            setPadding(0, dp(ctx, 16), 0, dp(ctx, 6))
        }
}