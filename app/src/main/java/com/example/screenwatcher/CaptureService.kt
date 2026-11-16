package com.example.screenwatcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileOutputStream

/**
 * Giữ MediaProjection (chụp màn hình), vòng lặp quét luật, và lớp phủ chọn vùng.
 */
class CaptureService : Service() {

    companion object {
        const val A_START = "sw.start"
        const val A_MONITOR = "sw.monitor"
        const val A_STOP = "sw.stop"
        const val A_PICK = "sw.pick"
        const val A_TEST = "sw.test"
        const val A_BUBBLE = "sw.bubble"
        const val A_PROFILE = "sw.profile"
        const val A_BASELINE = "sw.baseline"
        private const val CH = "sw_capture"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var curW = 0
    private var curH = 0
    @Volatile private var last: Bitmap? = null
    private var overlay: SelectorOverlay? = null
    private var fab: FloatingButton? = null
    private var loop: Job? = null
    private val lastFire = HashMap<String, Long>()
    /** Mốc gốc CỐ ĐỊNH của các luật kiểu "thay đổi" (chụp một lần khi bắt đầu theo dõi, không chụp lại sau macro). */
    private val baselines = java.util.concurrent.ConcurrentHashMap<String, ChangeMatcher.Base>()
    private var editor: ActionEditorOverlay? = null
    private var seqIndex = 0
    private var seqCycle = 0
    private var seqLogged = -1
    /** Đã chụp mốc gốc cố định cho tất cả luật "thay đổi" kể từ lúc bắt đầu theo dõi chưa. */
    private var baselined = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            A_START -> start()
            A_MONITOR -> setMonitoring(i.getBooleanExtra("on", false))
            A_BUBBLE -> if (i.getBooleanExtra("on", true)) showBubble() else { fab?.hide() }
            A_STOP -> { stopAll(); return START_NOT_STICKY }
            A_PICK -> pick(
                i.getStringExtra("mode") ?: "REGIONS", i.getStringExtra("id") ?: "",
                i.getStringExtra("kind") ?: "tap", i.getLongExtra("ms", 60L),
                i.getIntExtra("times", 1), i.getLongExtra("gap", 150L),
            )
            A_BASELINE -> recaptureBaseline(i.getStringExtra("id") ?: "")
            A_TEST -> test(i.getStringExtra("id") ?: "")
            A_PROFILE -> loadProfile(i.getStringExtra("id") ?: "", i.getBooleanExtra("run", true))
        }
        return START_NOT_STICKY
    }

    private fun setMonitoring(on: Boolean) {
        val was = AppState.monitoring
        AppState.monitoring = on && projection != null
        if (AppState.monitoring && !was) { seqIndex = 0; seqCycle = 0; seqLogged = -1; baselined = false; ChangeMatcher.resetLatches() }
        if (!AppState.monitoring) AppState.seqStatus = ""
        getSystemService(NotificationManager::class.java).notify(1, notif())
        AppState.log(if (AppState.monitoring) "▶ Bắt đầu theo dõi" else "⏸ Dừng theo dõi")
        fab?.refresh()
    }

    /** Nạp một bộ luật đã lưu; [run] = true thì chạy theo dõi luôn (bắt đầu lại từ luật đầu). */
    private fun loadProfile(id: String, run: Boolean) {
        val name = ProfileStore.list(this).firstOrNull { it.id == id }?.name
        if (name == null || !ProfileStore.load(this, id)) {
            Toast.makeText(this, "Không nạp được bộ luật", Toast.LENGTH_LONG).show()
            return
        }
        lastFire.clear()
        baselines.clear() // chỉ bộ nhớ đệm; mốc gốc đã lưu trong file của bộ luật
        baselined = false
        ChangeMatcher.resetLatches()
        AppState.log("📂 Đã nạp bộ luật '$name'")
        if (AppState.monitoring) setMonitoring(false) // dừng vòng cũ để chạy lại từ đầu
        if (run) {
            if (projection == null) {
                Toast.makeText(this, "Đã nạp '$name'. Cần cấp quyền chụp màn hình để chạy.", Toast.LENGTH_LONG).show()
            } else {
                setMonitoring(true)
                Toast.makeText(this, "Đang chạy bộ luật '$name'", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Đã nạp bộ luật '$name'", Toast.LENGTH_SHORT).show()
        }
        fab?.refresh()
    }

    /* ---------------- nút nổi ---------------- */

    private fun showBubble() {
        if (projection == null || !RuleStore.bubbleOn(this) || !Settings.canDrawOverlays(this)) return
        val f = fab ?: FloatingButton(
            this,
            onMonitor = { setMonitoring(it) },
            onAddRule = {
                startActivity(Intent(this, RuleEditActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            onOpenApp = {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            onTestRule = { quickTest(it) },
            onRunProfile = { id -> fab?.collapse(); loadProfile(id, false) },
            onHideButton = {
                RuleStore.setBubbleOn(this, false)
                fab?.hide()
                Toast.makeText(this, "Đã ẩn nút nổi. Bật lại trong app ở mục \"Hiện nút nổi\".", Toast.LENGTH_LONG).show()
            },
        ).also { fab = it }
        f.show()
    }

    /* ---------------- khởi động chụp màn hình ---------------- */

    private fun start() {
        val data = AppState.projData ?: return
        teardown()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "ScreenWatcher", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(1, notif())

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(AppState.projCode, data)
        AppState.projData = null
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopAll() } }
        }, main)
        projection = p
        setupDisplay()
        AppState.projectionReady = true
        loop = scope.launch { runLoop() }
        AppState.log("✔ Đã cấp quyền chụp màn hình")
        showBubble()
    }

    @Suppress("DEPRECATION")
    private fun realSize(): Triple<Int, Int, Int> {
        val dm = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }

    @Synchronized
    private fun setupDisplay() {
        val p = projection ?: return
        val (w, h, dpi) = realSize()
        if (vd != null && w == curW && h == curH) return
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val old = reader
        if (vd == null) {
            vd = p.createVirtualDisplay(
                "sw", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null,
            )
        } else {
            vd!!.surface = r.surface
            vd!!.resize(w, h, dpi)
        }
        reader = r
        old?.close()
        curW = w; curH = h
        last = null
    }

    /** Lấy khung hình mới nhất (nếu màn hình không đổi thì dùng lại khung cũ). */
    @Synchronized
    private fun grab(): Bitmap? {
        val r = reader ?: return last
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return last
        try {
            val pl = img.planes[0]
            val pad = pl.rowStride - pl.pixelStride * img.width
            val bmp = Bitmap.createBitmap(img.width + pad / pl.pixelStride, img.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(pl.buffer)
            last = if (pad == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, img.width, img.height).also { bmp.recycle() }
        } finally {
            img.close()
        }
        return last
    }

    /* ---------------- vòng lặp quét ---------------- */

    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                if (AppState.monitoring && !AppState.picking) {
                    setupDisplay()
                    tick()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppState.log("Lỗi: ${e.message}")
            }
            delay(RuleStore.interval(this))
        }
    }

    /**
     * Mốc gốc được LƯU VÀO FILE và cố định: chỉ chụp khi luật chưa có mốc (hoặc đã đổi vùng/độ phân giải),
     * hoặc khi người dùng bấm "Chụp mốc gốc mới". Chạy macro, Thử, Thử nhanh, dừng/chạy lại đều không đổi mốc.
     */
    private suspend fun ensureBaselines(frame: Bitmap) {
        if (baselined) return
        baselined = true
        for (r in RuleStore.load(this).filter { it.enabled && it.type == Rule.CHANGE }) {
            try { ChangeMatcher.capture(this, r, frame, baselines, force = false) } catch (e: CancellationException) { throw e } catch (e: Exception) { }
        }
    }

    private suspend fun tick() {
        val frame = grab() ?: return
        ensureBaselines(frame)
        if (RuleStore.seqOn(this)) { tickSequence(frame); return }
        for (rule in RuleStore.load(this).filter { it.enabled }) {
            val now = SystemClock.elapsedRealtime()
            if (now - (lastFire[rule.id] ?: -1_000_000L) < rule.cooldownMs) continue
            val hit = safeEval(rule, frame) ?: continue
            lastFire[rule.id] = SystemClock.elapsedRealtime()
            AppState.log("✔ '${rule.name}' ${hitText(rule, hit)}")
            Macro.runRule(rule, hit.x, hit.y)
            lastFire[rule.id] = SystemClock.elapsedRealtime()
            break // chụp lại khung mới rồi mới xét tiếp
        }
    }

    /**
     * Chế độ tuần tự: chờ luật 1 khớp -> chạy macro -> chờ luật 2 ... -> hết luật thì tính là xong 1 vòng,
     * rồi lặp lại theo số vòng đã đặt (0 = mãi mãi). Chỉ các luật đang bật mới tham gia, theo thứ tự trong danh sách.
     */
    private suspend fun tickSequence(frame: Bitmap) {
        ensureBaselines(frame)
        val rules = RuleStore.load(this).filter { it.enabled }
        if (rules.isEmpty()) return
        if (seqIndex >= rules.size) seqIndex = 0
        val total = RuleStore.seqCycles(this)
        val rule = rules[seqIndex]
        val cyc = if (total == 0) "${seqCycle + 1}" else "${seqCycle + 1}/$total"
        AppState.seqStatus = "Tuần tự: vòng $cyc • đang chờ luật ${seqIndex + 1}/${rules.size} '${rule.name}'"
        if (seqLogged != seqIndex) {
            seqLogged = seqIndex
            AppState.log("⏳ Chờ luật ${seqIndex + 1}/${rules.size}: '${rule.name}'")
        }
        val hit = safeEval(rule, frame) ?: return
        AppState.log("✔ [${seqIndex + 1}/${rules.size}] '${rule.name}' ${hitText(rule, hit)}")
        Macro.runRule(rule, hit.x, hit.y)
        if (!AppState.monitoring) return
        seqIndex++
        seqLogged = -1
        if (seqIndex >= rules.size) {
            seqIndex = 0
            seqCycle++
            if (total != 0 && seqCycle >= total) {
                AppState.log("✔ Hoàn thành $seqCycle vòng, dừng theo dõi")
                setMonitoring(false)
                return
            }
            AppState.log("↻ Bắt đầu vòng ${seqCycle + 1}" + (if (total == 0) "" else "/$total"))
        }
    }

    private suspend fun safeEval(
        rule: Rule, frame: Bitmap,
        test: Boolean = false,
    ): Hit? = try {
        evaluate(rule, frame, test)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppState.log("Lỗi quét '${rule.name}': ${e.message}")
        null
    }

    private fun hitText(rule: Rule, hit: Hit): String =
        if (rule.type == Rule.CHANGE) "CÓ THAY ĐỔI ${"%.0f".format(hit.score * 100)}% tại ${hit.x},${hit.y}"
        else "KHỚP (${"%.2f".format(hit.score)}, tỉ lệ ${"%.2f".format(hit.scale)}x) tại ${hit.x},${hit.y}"

    private fun missText(rule: Rule) = if (rule.type == Rule.CHANGE) "không thấy thay đổi" else "không khớp"

    /**
     * Thử luật "thay đổi": so với mốc gốc ĐÃ LƯU (không chụp lại). Chỉ khi luật chưa có mốc thì chụp mốc lần đầu,
     * chờ [waitMs] để người dùng làm vùng đó đổi, rồi so sánh.
     */
    private suspend fun probeChange(rule: Rule, waitMs: Long): Pair<Bitmap?, Hit?> {
        val f0 = grab() ?: return null to null
        val captured = try { ChangeMatcher.capture(this, rule, f0, baselines, force = false) } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
        if (!captured) return f0 to safeEval(rule, f0, test = true)
        main.post {
            Toast.makeText(
                this, "Luật chưa có mốc gốc nên đã chụp mốc '${rule.name}'. Hãy làm vùng đó thay đổi trong ${waitMs / 1000} giây…",
                Toast.LENGTH_LONG,
            ).show()
        }
        delay(waitMs)
        val f1 = grab() ?: return null to null
        return f1 to safeEval(rule, f1, test = true)
    }

    /** Người dùng chủ động chụp lại mốc gốc: chờ 4 giây để chuyển sang màn hình gốc rồi chụp (ghi đè mốc cũ). */
    private fun recaptureBaseline(id: String) {
        val rule = RuleStore.get(this, id) ?: return
        if (rule.type != Rule.CHANGE) return
        fab?.hide()
        scope.launch {
            delay(4000)
            val f = grab()
            val ok = f != null && try { ChangeMatcher.capture(this@CaptureService, rule, f, baselines, force = true) } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
            main.post {
                Toast.makeText(this@CaptureService, if (ok) "Đã chụp mốc gốc '${rule.name}'" else "Không chụp được mốc gốc", Toast.LENGTH_LONG).show()
                showBubble()
            }
        }
    }

    private suspend fun evaluate(rule: Rule, frame: Bitmap, test: Boolean = false): Hit? {
        if (rule.type == Rule.CHANGE) return ChangeMatcher.detect(this, rule, frame, baselines, test)
        val regs = if (rule.regions.isEmpty()) listOf(Region(0, 0, frame.width, frame.height)) else rule.regions
        val tpl: Bitmap? = if (rule.type == Rule.IMAGE) {
            BitmapFactory.decodeFile(RuleStore.templateFile(this, rule.id).path) ?: return null
        } else null

        for (rg in regs) {
            val l = rg.l.coerceIn(0, frame.width - 1)
            val t = rg.t.coerceIn(0, frame.height - 1)
            val w = rg.r.coerceAtMost(frame.width) - l
            val h = rg.b.coerceAtMost(frame.height) - t
            if (w < 8 || h < 8) continue
            val crop = Bitmap.createBitmap(frame, l, t, w, h)
            val hit = if (tpl != null) TemplateMatcher.find(crop, tpl, rule.threshold)
            else TextMatcher.find(crop, rule)
            if (hit != null) return hit.copy(x = hit.x + l, y = hit.y + t)
        }
        return null
    }

    /* ---------------- thử một luật ---------------- */

    /** Thử nhanh một luật từ bảng nút nổi: ẩn bảng + nút, chờ 0,8 giây rồi quét, báo kết quả (chưa chạy macro). */
    private fun quickTest(id: String) {
        val rule = RuleStore.get(this, id) ?: return
        fab?.hide()
        scope.launch {
            delay(800)
            val frame: Bitmap?
            val hit: Hit?
            if (rule.type == Rule.CHANGE) {
                val p = probeChange(rule, 4000)
                frame = p.first; hit = p.second
            } else {
                frame = grab()
                hit = if (frame == null) null else safeEval(rule, frame)
            }
            val msg = when {
                frame == null -> "Chưa có khung hình"
                hit != null -> hitText(rule, hit)
                else -> missText(rule)
            }
            AppState.log("Thử nhanh '${rule.name}': $msg")
            main.post {
                Toast.makeText(this@CaptureService, "'${rule.name}': $msg", Toast.LENGTH_LONG).show()
                if (hit != null) {
                    // vòng tròn đánh dấu chỗ khớp (cần bật Dịch vụ trợ năng và hiệu ứng chạm)
                    val x = hit.x.toFloat()
                    val y = hit.y.toFloat()
                    AutoClickService.instance?.flash("tap", x, y, x, y, 900L, "✔")
                }
                showBubble()
            }
        }
    }

    private fun test(id: String) {
        val rule = RuleStore.get(this, id) ?: return
        scope.launch {
            delay(4000) // thời gian để người dùng chuyển sang màn hình cần kiểm tra
            val frame: Bitmap?
            val hit: Hit?
            if (rule.type == Rule.CHANGE) {
                val p = probeChange(rule, 5000)
                frame = p.first; hit = p.second
            } else {
                frame = grab()
                hit = if (frame == null) null else safeEval(rule, frame)
            }
            val msg = when {
                frame == null -> "Chưa có khung hình"
                hit != null -> hitText(rule, hit)
                else -> missText(rule)
            }
            AppState.log("Thử '${rule.name}': $msg")
            main.post { Toast.makeText(this@CaptureService, "Thử '${rule.name}': $msg", Toast.LENGTH_LONG).show() }
        }
    }

    /* ---------------- chọn vùng / ảnh mẫu / điểm chạm ---------------- */

    private fun pick(mode: String, id: String, kind: String = "tap", ms: Long = 60L, times: Int = 1, gap: Long = 150L) {
        if (mode == "ACTIONS") { pickActions(id); return }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Cần cấp quyền hiển thị trên ứng dụng khác", Toast.LENGTH_LONG).show()
            return
        }
        overlay?.hide()
        fab?.hide()
        AppState.picking = true
        overlay = SelectorOverlay(this, mode, onFinish = { rects, pt, swipe ->
            AppState.picking = false
            overlay = null
            val rule = RuleStore.get(this, id)
            if (rule != null) {
                when (mode) {
                    "REGIONS" -> {
                        rule.regions.addAll(rects)
                        RuleStore.upsert(this, rule)
                        AppState.log("Đã thêm ${rects.size} vùng cho '${rule.name}'")
                        reopenEditor(id)
                    }
                    "POINT" -> {
                        if (pt != null) {
                            rule.macro = rule.macro.trimEnd() + "\n$kind ${pt.x} ${pt.y} $ms" + Macro.suffix(times, gap)
                            RuleStore.upsert(this, rule)
                        }
                        reopenEditor(id)
                    }
                    "SWIPE" -> {
                        if (swipe != null) {
                            val (a, b) = swipe
                            rule.macro = rule.macro.trimEnd() +
                                "\nswipe ${a.x} ${a.y} ${b.x} ${b.y} $ms" + Macro.suffix(times, gap)
                            RuleStore.upsert(this, rule)
                        }
                        reopenEditor(id)
                    }
                    "TEMPLATE" -> {
                        val rg = rects.lastOrNull()
                        if (rg != null) main.postDelayed({ saveTemplate(rule, rg); reopenEditor(id); showBubble() }, 450)
                        else showBubble()
                    }
                }
            }
            if (rule == null || mode != "TEMPLATE") showBubble()
        }, onCancel = {
            AppState.picking = false
            overlay = null
            showBubble()
            reopenEditor(id)
        }).also { it.show() }
    }

    /** Lớp phủ chỉnh điểm chạm / vuốt của luật: hiện số 1, 2, 3..., giữ ngón tay để thêm, chạm để chỉnh. */
    private fun pickActions(id: String) {
        val rule = RuleStore.get(this, id) ?: return
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Cần cấp quyền hiển thị trên ứng dụng khác", Toast.LENGTH_LONG).show()
            return
        }
        overlay?.hide(); overlay = null
        editor?.hide()
        fab?.hide()
        AppState.picking = true
        val base = rule.macro
        editor = ActionEditorOverlay(
            this, MacroEdit.parse(base).toMutableList(),
            onDone = { acts ->
                AppState.picking = false
                editor = null
                (RuleStore.get(this, id) ?: rule).let { r ->
                    r.macro = MacroEdit.apply(base, acts)
                    RuleStore.upsert(this, r)
                }
                AppState.log("Đã lưu ${acts.size} điểm chạm/vuốt cho '${rule.name}'")
                showBubble()
                reopenEditor(id)
            },
            onCancel = {
                AppState.picking = false
                editor = null
                showBubble()
                reopenEditor(id)
            },
        ).also { it.show() }
    }

    private fun saveTemplate(rule: Rule, rg: Region) {
        val f = grab()
        if (f == null) {
            Toast.makeText(this, "Không lấy được khung hình", Toast.LENGTH_LONG).show(); return
        }
        val l = rg.l.coerceIn(0, f.width - 1)
        val t = rg.t.coerceIn(0, f.height - 1)
        val w = (rg.r.coerceAtMost(f.width) - l).coerceAtLeast(1)
        val h = (rg.b.coerceAtMost(f.height) - t).coerceAtLeast(1)
        val crop = Bitmap.createBitmap(f, l, t, w, h)
        FileOutputStream(RuleStore.templateFile(this, rule.id)).use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.type = Rule.IMAGE
        RuleStore.upsert(this, rule)
        AppState.log("Đã lưu ảnh mẫu ${w}x$h cho '${rule.name}'")
    }

    /** Có quyền vẽ trên ứng dụng khác nên được phép mở lại màn hình sửa luật từ nền. */
    private fun reopenEditor(id: String) {
        startActivity(
            Intent(this, RuleEditActivity::class.java)
                .putExtra("id", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    /* ---------------- thông báo & dọn dẹp ---------------- */

    @Suppress("DEPRECATION")
    private fun notif(): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CaptureService::class.java).setAction(A_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("ScreenWatcher")
            .setContentText(if (AppState.monitoring) "Đang theo dõi màn hình" else "Sẵn sàng (chưa theo dõi)")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Dừng hẳn", stop)
            .setOngoing(true)
            .build()
    }

    private fun teardown() {
        loop?.cancel()
        overlay?.hide(); overlay = null
        editor?.hide(); editor = null
        fab?.hide(); fab = null
        vd?.release(); vd = null
        reader?.close(); reader = null
        runCatching { projection?.stop() }
        projection = null
        curW = 0; curH = 0
        AppState.monitoring = false
        AppState.picking = false
        AppState.projectionReady = false
    }

    private fun stopAll() {
        teardown()
        AppState.log("■ Đã dừng dịch vụ")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }
}
