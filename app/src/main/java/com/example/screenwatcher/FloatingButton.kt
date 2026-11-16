package com.example.screenwatcher

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
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
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max

/**
 * Nút nổi kéo thả được. Chạm vào nút để hiện nút Run / Stop bên cạnh (chạy hoặc dừng theo dõi).
 * Giữ nút 0,5 giây để mở/đóng bảng cài đặt: bật/tắt từng luật, chọn & chạy bộ luật đã lưu, thêm luật mới, mở app, ẩn nút.
 * Màu nút: xanh = đang theo dõi, xám = đang tắt.
 */
class FloatingButton(
    private val ctx: Context,
    private val onMonitor: (Boolean) -> Unit,
    private val onAddRule: () -> Unit,
    private val onOpenApp: () -> Unit,
    private val onHideButton: () -> Unit,
    private val onTestRule: (String) -> Unit,
    private val onRunProfile: (String) -> Unit,
) {
    private companion object {
        const val BUBBLE = 48   // dp
        const val ROW_W = 120   // dp, nút + nút Run/Stop
        const val PANEL_W = 250 // dp
        const val PANEL_H = 380 // dp, ước lượng chiều cao tối đa của bảng
        const val HOLD_MS = 500L // thời gian giữ nút SW để mở bảng
    }

    private val c = ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault)
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val root = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private val bubble = TextView(c)
    private val runBtn = TextView(c)
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
    private var showProfiles = false
    private val hideRun = Runnable { showRun(false) }

    init {
        bubble.apply {
            text = "SW"; textSize = 14f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(BUBBLE), dp(BUBBLE))
            setOnTouchListener(DragListener())
        }
        runBtn.apply {
            textSize = 14f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
                .apply { leftMargin = dp(4) }
            visibility = View.GONE
            setOnClickListener {
                val start = !AppState.monitoring
                if (start && !AppState.projectionReady) {
                    Toast.makeText(ctx, "Chưa cấp quyền chụp màn hình", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                onMonitor(start)
                showRun(false)
            }
        }
        topRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bubble)
            addView(runBtn)
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
        if (runBtn.visibility == View.VISIBLE) paintRun()
    }

    private fun paintRun() {
        val running = AppState.monitoring
        runBtn.text = if (running) "■ Stop" else "▶ Run"
        runBtn.background = bg(if (running) 0xE0C62828.toInt() else 0xE02E7D32.toInt(), dp(20).toFloat())
    }

    /** Hiện / ẩn nút Run (đang dừng) hoặc Stop (đang chạy) cạnh nút nổi; tự ẩn sau 4 giây. */
    private fun showRun(show: Boolean) {
        main.removeCallbacks(hideRun)
        runBtn.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            paintRun()
            main.postDelayed(hideRun, 4000)
        }
        clamp()
        update()
    }

    /* ---------------- hiện / ẩn ---------------- */

    fun show() {
        if (added) return
        val (x, y) = RuleStore.bubblePos(ctx)
        lp.x = x; lp.y = y
        expanded = false
        panel.visibility = View.GONE
        runBtn.visibility = View.GONE
        clamp()
        paintBubble()
        added = runCatching { wm.addView(root, lp) }.isSuccess
    }

    fun hide() {
        if (!added) return
        main.removeCallbacks(hideRun)
        runCatching { wm.removeView(root) }
        added = false
        expanded = false
        panel.visibility = View.GONE
    }

    fun collapse() {
        if (!expanded) return
        expanded = false
        showProfiles = false
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
        showProfiles = false
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

    /** Danh sách bộ luật đã lưu: chạm một tên để nạp (không chạy luôn, bấm Run để chạy). */
    private fun buildProfilePanel() {
        panel.addView(TextView(c).apply {
            text = "Bộ luật đã lưu — chạm để nạp (chưa chạy)"; setTextColor(0xFFBBBBBB.toInt()); textSize = 12f
            setPadding(0, 0, 0, dp(4))
        })
        val profiles = ProfileStore.list(ctx)
        val list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        if (profiles.isEmpty()) {
            list.addView(TextView(c).apply {
                text = "(chưa lưu bộ luật nào — lưu trong app)"; setTextColor(0xFFBBBBBB.toInt())
                setPadding(0, dp(8), 0, dp(8))
            })
        }
        for (p in profiles) {
            list.addView(
                action("📂 ${p.name} (${p.ruleCount})") { onRunProfile(p.id) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(4) },
            )
        }
        panel.addView(
            ScrollView(c).apply { addView(list) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (profiles.size > 5) dp(220) else LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        row.addView(
            action("← Quay lại") { showProfiles = false; buildPanel(); clamp(); update() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) },
        )
        panel.addView(row)
    }

    private fun buildPanel() {
        panel.removeAllViews()
        if (showProfiles) { buildProfilePanel(); return }

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
            val line = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            line.addView(
                CheckBox(c).apply {
                    text = r.name; setTextColor(Color.WHITE); isChecked = r.enabled
                    isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
                    setOnCheckedChangeListener { _, on ->
                        RuleStore.get(ctx, r.id)?.let { it.enabled = on; RuleStore.upsert(ctx, it) }
                    }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            // Thử luật nhanh: thu bảng + nút nổi lại, quét ngay rồi báo kết quả
            line.addView(
                action("Thử") { onTestRule(r.id) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { leftMargin = dp(6) },
            )
            list.addView(line)
        }
        panel.addView(
            ScrollView(c).apply { addView(list) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (rules.size > 5) dp(220) else LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        fun newRow() = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        fun LinearLayout.add(t: String, f: () -> Unit) = addView(
            action(t, f),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) },
        )
        val row1 = newRow()
        row1.add("📁 Bộ luật") { showProfiles = true; buildPanel(); clamp(); update() }
        row1.add("+ Luật") { collapse(); onAddRule() }
        panel.addView(row1)
        val row2 = newRow().apply { setPadding(0, dp(2), 0, 0) }
        row2.add("Mở app") { collapse(); onOpenApp() }
        row2.add("Ẩn nút") { onHideButton() }
        row2.add("✕") { collapse() }
        panel.addView(row2)
    }

    /* ---------------- kéo thả / chạm ---------------- */

    private inner class DragListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        private var fired = false

        // giữ 0,5 giây (không kéo) -> mở / đóng bảng cài đặt
        private val longRun = Runnable {
            if (!moved) {
                fired = true
                bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showRun(false)
                toggle()
            }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; fired = false
                    main.postDelayed(longRun, HOLD_MS)
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
                    else if (!fired && e.action == MotionEvent.ACTION_UP) showRun(runBtn.visibility != View.VISIBLE)
                }
            }
            return true
        }
    }
}
