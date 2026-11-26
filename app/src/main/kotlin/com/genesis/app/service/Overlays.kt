package com.genesis.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Windows drawn by the accessibility service above other apps (TYPE_ACCESSIBILITY_OVERLAY needs
 * no extra permission). Plain views keep them independent of the Compose activity.
 */
internal class Overlays(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var stopBar: View? = null
    private var approvalPanel: View? = null

    val approvalVisible: Boolean get() = approvalPanel != null

    /** Shows "GENESIS is in control" with a stop button. [onOutsideTouch] fires when the user touches anywhere else. */
    @SuppressLint("ClickableViewAccessibility")
    fun showStopBar(onStop: () -> Unit, onOutsideTouch: (() -> Unit)?, label: String = "GENESIS يتحكم الآن") {
        if (stopBar != null) return
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(6), dp(6))
            background = rounded(Color.parseColor("#E6231942"), dp(22).toFloat())
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        row.addView(TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
        })
        row.addView(Button(context).apply {
            text = "إيقاف"
            setTextColor(Color.WHITE)
            background = rounded(Color.parseColor("#D9363E"), dp(18).toFloat())
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(16), dp(4), dp(16), dp(4))
            setOnClickListener { onStop() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(10) })
        row.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) onOutsideTouch?.invoke()
            false
        }
        val lp = baseParams(WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(28)
            if (onOutsideTouch != null) flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
        wm.addView(row, lp)
        stopBar = row
    }

    fun hideStopBar() {
        stopBar?.let { runCatching { wm.removeView(it) } }
        stopBar = null
    }

    /** Shows exactly what will be sent and two buttons. */
    fun showApproval(title: String, app: String, content: String, onDecision: (Boolean) -> Unit) {
        hideApproval()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = rounded(Color.parseColor("#F2141420"), dp(20).toFloat())
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        card.addView(TextView(context).apply {
            text = "موافقة مطلوبة: $title"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(TextView(context).apply {
            text = "التطبيق: $app · لن يُرسل شيء قبل ضغطك على «أرسل»"
            setTextColor(Color.parseColor("#B8B5CC"))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(10))
        })
        val body = TextView(context).apply {
            text = content
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.parseColor("#2A2A3A"), dp(12).toFloat())
            textDirection = View.TEXT_DIRECTION_ANY_RTL
        }
        card.addView(ScrollView(context).apply { addView(body) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        buttons.addView(Button(context).apply {
            text = "أرسل"
            setTextColor(Color.WHITE)
            background = rounded(Color.parseColor("#7C5CFF"), dp(14).toFloat())
            setOnClickListener { hideApproval(); onDecision(true) }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        buttons.addView(View(context), LinearLayout.LayoutParams(dp(12), 1))
        buttons.addView(Button(context).apply {
            text = "إلغاء"
            setTextColor(Color.WHITE)
            background = rounded(Color.parseColor("#3A3A4A"), dp(14).toFloat())
            setOnClickListener { hideApproval(); onDecision(false) }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        card.addView(buttons)
        val lp = baseParams(WindowManager.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.BOTTOM
            horizontalMargin = 0f
            y = dp(24)
        }
        wm.addView(card, lp)
        approvalPanel = card
    }

    fun hideApproval() {
        approvalPanel?.let { runCatching { wm.removeView(it) } }
        approvalPanel = null
    }

    fun hideAll() {
        hideApproval()
        hideStopBar()
    }

    private fun baseParams(width: Int) = WindowManager.LayoutParams(
        width,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
