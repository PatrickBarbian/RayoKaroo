package com.rayo.karoo

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Native Android Design-Engineering helpers:
 * - Tactile press feedback (scale 0.97, ease-out < 150ms)
 * - Calm segmented control selection state
 * - Sonner-style floating toast banner with optional Undo
 * - Instant 0ms Command Palette modal
 */
object UiMotion {
    private val EASE_OUT = DecelerateInterpolator(1.6f)

    @SuppressLint("ClickableViewAccessibility")
    fun attachPressFeedback(vararg views: View?) {
        for (v in views) {
            if (v == null) continue
            v.setOnTouchListener { target, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        target.animate()
                            .scaleX(0.97f)
                            .scaleY(0.97f)
                            .setDuration(90L)
                            .setInterpolator(EASE_OUT)
                            .start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        target.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(140L)
                            .setInterpolator(EASE_OUT)
                            .start()
                    }
                }
                false
            }
        }
    }

    fun updateSegmentedButtons(buttons: List<TextView>, selectedIndex: Int) {
        if (buttons.isEmpty()) return
        val ctx = buttons.first().context
        val primaryText = ctx.getColor(R.color.text_primary)
        val secondaryText = ctx.getColor(R.color.text_secondary)

        buttons.forEachIndexed { idx, btn ->
            if (idx == selectedIndex) {
                btn.setBackgroundResource(R.drawable.bg_segment_active)
                btn.setTextColor(primaryText)
                btn.setTypeface(null, Typeface.BOLD)
            } else {
                btn.setBackgroundColor(Color.TRANSPARENT)
                btn.setTextColor(secondaryText)
                btn.setTypeface(null, Typeface.NORMAL)
            }
        }
    }

    class FloatingToastController(
        private val container: View,
        private val messageView: TextView,
        private val undoButton: Button,
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private val hideRunnable = Runnable { hide() }

        init {
            attachPressFeedback(undoButton)
        }

        fun show(message: String, undoAction: (() -> Unit)? = null) {
            handler.removeCallbacks(hideRunnable)
            messageView.text = message

            if (undoAction != null) {
                undoButton.visibility = View.VISIBLE
                undoButton.setOnClickListener {
                    handler.removeCallbacks(hideRunnable)
                    hide()
                    undoAction()
                }
            } else {
                undoButton.visibility = View.GONE
                undoButton.setOnClickListener(null)
            }

            if (container.visibility != View.VISIBLE) {
                container.alpha = 0f
                container.translationY = 20f
                container.visibility = View.VISIBLE
                container.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(200L)
                    .setInterpolator(EASE_OUT)
                    .start()
            }
            handler.postDelayed(hideRunnable, 2800L)
        }

        fun hide() {
            if (container.visibility != View.VISIBLE) return
            container.animate()
                .alpha(0f)
                .translationY(16f)
                .setDuration(180L)
                .setInterpolator(EASE_OUT)
                .withEndAction { container.visibility = View.GONE }
                .start()
        }
    }

    data class CommandItem(
        val title: String,
        val subtitle: String,
        val action: () -> Unit,
    )

    fun showCommandPalette(activity: Activity, items: List<CommandItem>) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val content = LayoutInflater.from(activity).inflate(R.layout.dialog_command_palette, null)
        dialog.setContentView(content)

        dialog.window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            win.setGravity(Gravity.BOTTOM)
        }

        val search = content.findViewById<EditText>(R.id.etCommandSearch)
        val listContainer = content.findViewById<LinearLayout>(R.id.layoutCommandList)
        val emptyView = content.findViewById<TextView>(R.id.tvCommandEmpty)

        val primaryColor = activity.getColor(R.color.text_primary)
        val secondaryColor = activity.getColor(R.color.text_secondary)
        val density = activity.resources.displayMetrics.density

        fun rebuild(query: String) {
            listContainer.removeAllViews()
            val q = query.lowercase(Locale.getDefault()).trim()
            var count = 0

            for (item in items) {
                if (q.isNotEmpty() &&
                    !item.title.lowercase(Locale.getDefault()).contains(q) &&
                    !item.subtitle.lowercase(Locale.getDefault()).contains(q)
                ) {
                    continue
                }
                count++
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.bg_btn_secondary)
                    val padH = (12 * density).toInt()
                    val padV = (9 * density).toInt()
                    setPadding(padH, padV, padH, padV)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = (6 * density).toInt()
                    }
                }

                row.addView(
                    TextView(activity).apply {
                        text = item.title
                        setTextColor(primaryColor)
                        textSize = 13f
                        maxLines = 1
                    },
                )
                if (item.subtitle.isNotEmpty()) {
                    row.addView(
                        TextView(activity).apply {
                            text = item.subtitle
                            setTextColor(secondaryColor)
                            textSize = 11f
                            maxLines = 1
                        },
                    )
                }

                // Instant 0ms execution
                row.setOnClickListener {
                    dialog.dismiss()
                    item.action()
                }
                listContainer.addView(row)
            }

            emptyView.visibility = if (count == 0) View.VISIBLE else View.GONE
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                rebuild(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        rebuild("")
        dialog.show()
    }
}
