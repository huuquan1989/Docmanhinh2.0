package com.example.screenwatcher

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max

/**
 * Nút nổi kéo thả được, có công tắc bật/tắt theo dõi nhanh ngay bên cạnh.
 * Giữ nút 1 giây để mở/đóng bảng cài đặt: bật/tắt từng luật, thêm luật mới, mở app, ẩn nút.
 * Màu nút: xanh = đang theo dõi, xám = đang tắt.
 */
class FloatingButton(
    private val ctx: Context,
    private val onMonitor: (Boolean) -> Unit,
    private val onAddRule: () -> Unit,
    private val onOpenApp: () -> Unit,
    private val onHideButton: () -> Unit,
) {
    private companion object {
        const val BUBBLE = 48   // dp
        const val ROW_W = 120   // dp, nút + công tắc nhanh
        const val PANEL_W = 250 // dp
        const val PANEL_H = 340 // dp, ước lượng chiều cao tối đa của bảng
    }

    private val c = ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault)
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val root = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val bubble = TextView(c)
    private val quick = Switch(c)
    private val topRow = LinearLayout(c)
    private val panel = LinearLayout(c)

    private val lp = LayoutParams(
        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
        LayoutParams.TYPE_APPLICATION_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private var added = false
    private var expanded = false
    private var syncing = false

    init {
        bubble.apply {
            text = "SW"; textSize = 14f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(BUBBLE), dp(BUBBLE))
            setOnTouchListener(DragListener())
        }
        quick.apply {
            scaleX = 0.85f; scaleY = 0.85f
            setOnCheckedChangeListener { v, on ->
                if (syncing) return@setOnCheckedChangeListener
                if (on && !AppState.projectionReady) {
                    Toast.makeText(ctx, "Chưa cấp quyền chụp màn hình", Toast.LENGTH_SHORT).show()
                    syncing = true; (v as Switch).isChecked = false; syncing = false
                    return@setOnCheckedChangeListener
                }
                onMonitor(on)
            }
        }
        val pill = LinearLayout(c).apply {
            gravity = Gravity.CENTER
            background = bg(0xB0000000.toInt(), dp(20).toFloat())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(quick)
        }
        topRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bubble)
            addView(pill, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(4) })
        }
        panel.apply {
            orientation = LinearLayout.VERTICAL
            background = bg(0xF2202020.toInt(), dp(12).toFloat())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(PANEL_W), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) }
            visibility = View.GONE
        }
        root.addView(topRow)
        root.addView(panel)
        paintBubble()
    }

    private fun bg(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
    }

    @Suppress("DEPRECATION")
    private fun screen(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun clamp() {
        val (w, h) = screen()
        val bw = if (expanded) dp(max(PANEL_W, ROW_W)) else dp(ROW_W)
        val bh = if (expanded) dp(BUBBLE + 6 + PANEL_H) else dp(BUBBLE)
        lp.x = lp.x.coerceIn(0, max(0, w - bw))
        lp.y = lp.y.coerceIn(0, max(0, h - bh))
    }

    private fun update() {
        if (added) runCatching { wm.updateViewLayout(root, lp) }
    }

    private fun paintBubble() {
        val col = if (AppState.monitoring) 0xE02E7D32.toInt() else 0xE0616161.toInt()
        bubble.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col) }
        if (quick.isChecked != AppState.monitoring) {
            syncing = true
            quick.isChecked = AppState.monitoring
            syncing = false
        }
    }

    /* ---------------- hiện / ẩn ---------------- */

    fun show() {
        if (added) return
        val (x, y) = RuleStore.bubblePos(ctx)
        lp.x = x; lp.y = y
        expanded = false
        panel.visibility = View.GONE
        clamp()
        paintBubble()
        added = runCatching { wm.addView(root, lp) }.isSuccess
    }

    fun hide() {
        if (!added) return
        runCatching { wm.removeView(root) }
        added = false
        expanded = false
        panel.visibility = View.GONE
    }

    fun collapse() {
        if (!expanded) return
        expanded = false
        panel.visibility = View.GONE
        update()
    }

    /** Cập nhật màu nút và bảng (gọi khi trạng thái theo dõi thay đổi). */
    fun refresh() {
        main.post {
            if (!added) return@post
            paintBubble()
            if (expanded) buildPanel()
        }
    }

    private fun toggle() {
        expanded = !expanded
        if (expanded) {
            buildPanel()
            panel.visibility = View.VISIBLE
        } else panel.visibility = View.GONE
        clamp()
        update()
        RuleStore.setBubblePos(ctx, lp.x, lp.y)
    }

    /* ---------------- bảng nhanh ---------------- */

    private fun action(t: String, f: () -> Unit) = TextView(c).apply {
        text = t; setTextColor(Color.WHITE); gravity = Gravity.CENTER; textSize = 13f
        setPadding(dp(6), dp(10), dp(6), dp(10))
        background = bg(0x33FFFFFF, dp(8).toFloat())
        setOnClickListener { f() }
    }

    private fun buildPanel() {
        panel.removeAllViews()

        if (AppState.monitoring && AppState.seqStatus.isNotEmpty()) {
            panel.addView(TextView(c).apply {
                text = AppState.seqStatus; setTextColor(0xFFBBBBBB.toInt()); textSize = 12f
            })
        }

        val rules = RuleStore.load(ctx)
        val list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        if (rules.isEmpty()) {
            list.addView(TextView(c).apply {
                text = "(chưa có luật nào)"; setTextColor(0xFFBBBBBB.toInt()); setPadding(0, dp(8), 0, dp(8))
            })
        }
        for (r in rules) {
            list.addView(CheckBox(c).apply {
                text = r.name; setTextColor(Color.WHITE); isChecked = r.enabled
                setOnCheckedChangeListener { _, on ->
                    RuleStore.get(ctx, r.id)?.let { it.enabled = on; RuleStore.upsert(ctx, it) }
                }
            })
        }
        panel.addView(
            ScrollView(c).apply { addView(list) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (rules.size > 5) dp(220) else LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        fun add(t: String, f: () -> Unit) = row.addView(
            action(t, f),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) },
        )
        add("+ Luật") { collapse(); onAddRule() }
        add("Mở app") { collapse(); onOpenApp() }
        add("Ẩn nút") { onHideButton() }
        add("✕") { collapse() }
        panel.addView(row)
    }

    /* ---------------- kéo thả / chạm ---------------- */

    private inner class DragListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        // giữ 1 giây (không kéo) -> mở / đóng bảng cài đặt
        private val longRun = Runnable {
            if (!moved) {
                bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                toggle()
            }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                    main.postDelayed(longRun, 1000)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > dp(6) || abs(dy) > dp(6))) {
                        moved = true
                        main.removeCallbacks(longRun)
                    }
                    if (moved) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        clamp(); update()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longRun)
                    if (moved) RuleStore.setBubblePos(ctx, lp.x, lp.y)
                }
            }
            return true
        }
    }
}
