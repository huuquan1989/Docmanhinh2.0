package com.example.screenwatcher

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var list: LinearLayout
    private lateinit var profileList: LinearLayout
    private lateinit var profileDropdown: Button
    private var selectedProfileId: String? = null
    private var dropdownOpen = false
    private lateinit var monitorSwitch: Switch
    private lateinit var bubbleSwitch: Switch
    private lateinit var seqSwitch: Switch
    private lateinit var feedbackSwitch: Switch
    private lateinit var cyclesEt: EditText
    private lateinit var intervalEt: EditText
    private var updating = false
    private val h = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            h.postDelayed(this, 1000)
        }
    }

    private val projLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) {
            AppState.projCode = r.resultCode
            AppState.projData = data
            ContextCompat.startForegroundService(
                this, Intent(this, CaptureService::class.java).setAction(CaptureService.A_START),
            )
        }
    }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { f() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply { text = "ScreenWatcher"; textSize = 24f; setTextColor(Color.BLACK) })
        root.addView(TextView(this).apply {
            text = "Quét chữ / hình ảnh trong các vùng đã chọn, khớp thì tự động chạm hoặc chạy macro."
        })
        status = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        root.addView(status)

        root.addView(btn("1. Cấp quyền hiển thị trên ứng dụng khác") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(btn("2. Bật Dịch vụ trợ năng (ScreenWatcher)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(btn("3. Cấp quyền chụp màn hình") {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projLauncher.launch(mpm.createScreenCaptureIntent())
        })

        monitorSwitch = Switch(this).apply {
            text = "Bật theo dõi"; setPadding(0, dp(12), 0, dp(4))
            setOnCheckedChangeListener { _, on ->
                if (updating) return@setOnCheckedChangeListener
                if (on && !AppState.projectionReady) {
                    Toast.makeText(this@MainActivity, "Hãy làm bước 3 trước", Toast.LENGTH_SHORT).show()
                    updating = true; isChecked = false; updating = false
                    return@setOnCheckedChangeListener
                }
                saveInterval()
                startService(
                    Intent(this@MainActivity, CaptureService::class.java)
                        .setAction(CaptureService.A_MONITOR).putExtra("on", on),
                )
            }
        }
        root.addView(monitorSwitch)

        bubbleSwitch = Switch(this).apply {
            text = "Hiện nút nổi (bật/tắt luật, thêm luật nhanh)"; setPadding(0, dp(4), 0, dp(4))
            isChecked = RuleStore.bubbleOn(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                if (updating) return@setOnCheckedChangeListener
                RuleStore.setBubbleOn(this@MainActivity, on)
                if (AppState.projectionReady) {
                    startService(
                        Intent(this@MainActivity, CaptureService::class.java)
                            .setAction(CaptureService.A_BUBBLE).putExtra("on", on),
                    )
                }
            }
        }
        root.addView(bubbleSwitch)

        feedbackSwitch = Switch(this).apply {
            text = "Hiện hiệu ứng chạm / vuốt trên màn hình khi chạy"; setPadding(0, dp(4), 0, dp(4))
            isChecked = RuleStore.feedbackOn(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                if (updating) return@setOnCheckedChangeListener
                RuleStore.setFeedbackOn(this@MainActivity, on)
            }
        }
        root.addView(feedbackSwitch)

        seqSwitch = Switch(this).apply {
            text = "Chạy tuần tự: luật 1 → 2 → 3… xong hết thì lặp lại"; setPadding(0, dp(4), 0, dp(4))
            isChecked = RuleStore.seqOn(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                if (updating) return@setOnCheckedChangeListener
                RuleStore.setSeqOn(this@MainActivity, on)
            }
        }
        root.addView(seqSwitch)
        val cy = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cy.addView(TextView(this).apply { text = "Số vòng (1 = chỉ 1 lần, 0 = lặp mãi): " })
        cyclesEt = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(RuleStore.seqCycles(this@MainActivity).toString())
            minEms = 4
        }
        cy.addView(cyclesEt)
        root.addView(cy)

        val iv = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        iv.addView(TextView(this).apply { text = "Quét mỗi (ms): " })
        intervalEt = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(RuleStore.interval(this@MainActivity).toString())
            minEms = 5
        }
        iv.addView(intervalEt)
        root.addView(iv)

        root.addView(btn("💾 Lưu luật hiện tại thành bộ luật mới") { saveProfile() })
        profileDropdown = btn("") { dropdownOpen = !dropdownOpen; rebuildProfiles() }
        root.addView(profileDropdown)
        profileList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(profileList)

        root.addView(TextView(this).apply {
            text = "Danh sách luật"; textSize = 18f; setTextColor(Color.BLACK); setPadding(0, dp(16), 0, dp(4))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        root.addView(btn("+ Thêm luật") {
            startActivity(Intent(this, RuleEditActivity::class.java))
        })

        root.addView(TextView(this).apply {
            text = "Nhật ký"; textSize = 18f; setTextColor(Color.BLACK); setPadding(0, dp(16), 0, dp(4))
        })
        logView = TextView(this).apply { textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun saveInterval() {
        intervalEt.text.toString().toLongOrNull()?.let { RuleStore.setInterval(this, it) }
        cyclesEt.text.toString().toIntOrNull()?.let { RuleStore.setSeqCycles(this, it) }
    }

    override fun onResume() {
        super.onResume()
        rebuildList()
        rebuildProfiles()
        h.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        saveInterval()
        h.removeCallbacks(ticker)
    }

    /* ---------------- bộ luật đã lưu ---------------- */

    /** Bấm "Lưu luật hiện tại thành bộ luật mới": hỏi tên rồi lưu. */
    private fun saveProfile() {
        if (RuleStore.load(this).isEmpty()) {
            Toast.makeText(this, "Chưa có luật nào để lưu", Toast.LENGTH_SHORT).show(); return
        }
        val et = EditText(this).apply {
            hint = "Tên bộ luật (vd: Điểm danh, Farm game A…)"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        AlertDialog.Builder(this)
            .setTitle("Lưu thành bộ luật mới").setView(box)
            .setPositiveButton("Lưu") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Hãy đặt tên cho bộ luật", Toast.LENGTH_SHORT).show()
                } else if (ProfileStore.findByName(this, name) != null) {
                    AlertDialog.Builder(this)
                        .setMessage("Đã có bộ luật '$name'. Ghi đè bằng các luật hiện tại?")
                        .setPositiveButton("Ghi đè") { _, _ -> doSaveProfile(name) }
                        .setNegativeButton("Huỷ", null).show()
                } else doSaveProfile(name)
            }
            .setNegativeButton("Huỷ", null).show()
    }

    private fun doSaveProfile(name: String) {
        saveInterval()
        val p = ProfileStore.saveCurrent(this, name)
        selectedProfileId = p.id
        dropdownOpen = false
        rebuildProfiles()
        Toast.makeText(this, "Đã lưu bộ luật '${p.name}' (${p.ruleCount} luật)", Toast.LENGTH_SHORT).show()
    }

    private fun runProfile(p: Profile, run: Boolean) {
        if (AppState.projectionReady) {
            // dịch vụ đang chạy: để dịch vụ nạp rồi (tuỳ chọn) bắt đầu theo dõi luôn
            startService(
                Intent(this, CaptureService::class.java)
                    .setAction(CaptureService.A_PROFILE).putExtra("id", p.id).putExtra("run", run),
            )
        } else {
            if (!ProfileStore.load(this, p.id)) {
                Toast.makeText(this, "Không nạp được bộ luật", Toast.LENGTH_SHORT).show(); return
            }
            Toast.makeText(
                this,
                if (run) "Đã nạp '${p.name}'. Hãy làm bước 3 (cấp quyền chụp màn hình) rồi bấm ▶ Chạy lại."
                else "Đã nạp '${p.name}'",
                Toast.LENGTH_LONG,
            ).show()
        }
        // cập nhật ô cài đặt trên màn hình
        h.postDelayed({
            cyclesEt.setText(RuleStore.seqCycles(this).toString())
            intervalEt.setText(RuleStore.interval(this).toString())
            updating = true; seqSwitch.isChecked = RuleStore.seqOn(this); updating = false
            rebuildList()
        }, 300)
    }

    private fun renameProfile(p: Profile) {
        val et = EditText(this).apply { setText(p.name); setSingleLine(true); setSelection(p.name.length) }
        AlertDialog.Builder(this)
            .setTitle("Đổi tên bộ luật").setView(et)
            .setPositiveButton("Lưu") { _, _ ->
                if (ProfileStore.rename(this, p.id, et.text.toString())) rebuildProfiles()
                else Toast.makeText(this, "Tên trống hoặc đã tồn tại", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Huỷ", null).show()
    }

    private fun rebuildProfiles() {
        profileList.removeAllViews()
        val profiles = ProfileStore.list(this)
        if (selectedProfileId != null && profiles.none { it.id == selectedProfileId }) selectedProfileId = null
        val selected = profiles.firstOrNull { it.id == selectedProfileId }

        profileDropdown.text = "Danh sách đã lưu (${profiles.size})   " + if (dropdownOpen) "▲" else "▼"

        // danh sách thả xuống: chọn tên để mở bộ luật đó
        if (dropdownOpen) {
            if (profiles.isEmpty()) profileList.addView(TextView(this).apply {
                text = "(chưa lưu bộ luật nào)"; setPadding(dp(8), dp(8), 0, dp(8))
            })
            for (p in profiles) {
                profileList.addView(TextView(this).apply {
                    text = "${p.name}  •  ${p.ruleCount} luật"
                    textSize = 16f
                    setTextColor(Color.BLACK)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    if (p.id == selectedProfileId) setBackgroundColor(Color.parseColor("#E0E0E0"))
                    setOnClickListener {
                        selectedProfileId = p.id
                        dropdownOpen = false
                        runProfile(p, false)   // nạp luật của bộ này vào danh sách luật bên dưới
                        rebuildProfiles()
                    }
                })
            }
        }

        // bộ luật đang mở: Chạy / Sửa / Lưu / Xoá / Hủy
        if (selected != null) {
            profileList.addView(TextView(this).apply {
                text = "Đang mở: ${selected.name}  •  ${selected.ruleCount} luật"
                textSize = 16f; setTextColor(Color.BLACK); setPadding(0, dp(10), 0, dp(2))
            })
            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            btns.addView(smallBtn("▶ Chạy") { runProfile(selected, true) })
            btns.addView(smallBtn("Sửa") { renameProfile(selected) })
            btns.addView(smallBtn("Lưu") {
                saveInterval()
                ProfileStore.saveCurrent(this, selected.name)
                rebuildProfiles()
                Toast.makeText(this, "Đã lưu '${selected.name}'", Toast.LENGTH_SHORT).show()
            })
            btns.addView(smallBtn("Xoá") {
                AlertDialog.Builder(this)
                    .setMessage("Xoá bộ luật '${selected.name}'? (các luật đang dùng không bị ảnh hưởng)")
                    .setPositiveButton("Xoá") { _, _ ->
                        ProfileStore.delete(this, selected.id)
                        selectedProfileId = null
                        rebuildProfiles()
                    }
                    .setNegativeButton("Huỷ", null).show()
            })
            btns.addView(smallBtn("Hủy") { selectedProfileId = null; dropdownOpen = false; rebuildProfiles() })
            profileList.addView(HorizontalScrollView(this).apply { addView(btns) })
        }
    }

    private fun refreshStatus() {
        fun m(ok: Boolean) = if (ok) "✔" else "✘"
        status.text = "Hiển thị trên app khác: ${m(Settings.canDrawOverlays(this))}   " +
            "Trợ năng: ${m(AutoClickService.instance != null)}   " +
            "Chụp màn hình: ${m(AppState.projectionReady)}" +
            ProfileStore.activeNames(this).let { if (it.isEmpty()) "" else "\nĐang dùng: " + it.joinToString(" + ") } +
            (if (AppState.monitoring && AppState.seqStatus.isNotEmpty()) "\n${AppState.seqStatus}" else "")
        updating = true
        monitorSwitch.isChecked = AppState.monitoring
        bubbleSwitch.isChecked = RuleStore.bubbleOn(this)
        updating = false
        logView.text = AppState.logText()
    }

    private fun smallBtn(t: String, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; minWidth = 0; minimumWidth = 0
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { f() }
    }

    /** Đổi thứ tự luật (thứ tự này cũng là thứ tự chạy ở chế độ tuần tự). */
    private fun move(i: Int, d: Int) {
        val l = RuleStore.load(this)
        val j = i + d
        if (j < 0 || j >= l.size) return
        val t = l[i]; l[i] = l[j]; l[j] = t
        RuleStore.saveAll(this, l)
        rebuildList()
    }

    private fun rebuildList() {
        list.removeAllViews()
        val rules = RuleStore.load(this)
        if (rules.isEmpty()) list.addView(TextView(this).apply { text = "(chưa có luật nào)" })
        for ((idx, r) in rules.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, RuleEditActivity::class.java).putExtra("id", r.id))
                }
            }
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(
                CheckBox(this).apply {
                    text = "${idx + 1}. ${r.name}"
                    isChecked = r.enabled
                    setOnCheckedChangeListener { _, on -> r.enabled = on; RuleStore.upsert(this@MainActivity, r) }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            head.addView(smallBtn("▲") { move(idx, -1) })
            head.addView(smallBtn("▼") { move(idx, 1) })
            row.addView(head)
            val what = when (r.type) {
                Rule.TEXT -> "Chữ: \"${r.text}\""
                Rule.CHANGE -> "Thay đổi " + (if (r.changeMode == Rule.CH_TEXT) "chữ" else "hình") +
                    " ≥ ${"%.0f".format(r.diffThreshold * 100)}%"
                else -> "Hình ảnh mẫu"
            }
            val where = if (r.regions.isEmpty()) "toàn màn hình" else "${r.regions.size} vùng"
            row.addView(TextView(this).apply {
                text = "$what • $where • ${r.macro.lines().count { it.isNotBlank() }} bước • chạm để sửa"
                textSize = 12f
            })
            list.addView(row)
        }
    }
}
