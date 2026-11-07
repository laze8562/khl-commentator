package ru.khl.commentator

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.util.Locale

// ---------------------------------------------------------------
// Настройки: какая часть экрана читается (доли от ширины/высоты).
// Нижняя плашка с надписями «ГОЛ», «МАЛ. ШТРАФ» и т.д.
// Если плашка не читается, подгоняй эти числа.
// ---------------------------------------------------------------
object Cfg {
    const val BANNER_X = 0.28      // левый край
    const val BANNER_W = 0.57      // ширина
    const val BANNER_Y = 0.80      // верхний край
    const val BOARD_X = 0.09       // табло сверху: левый край
    const val BOARD_W = 0.52       // ширина
    const val BOARD_H = 0.085      // высота
    const val INTERVAL_MS = 700L   // как часто читать экран
    const val COOLDOWN_MS = 20000L // не повторять одно событие чаще
}

class MainActivity : Activity() {
    private val req = 42

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val info = TextView(this).apply {
            text = "1) Нажми «Старт» и разреши запись экрана.\n2) Открой PPSSPP и играй.\n" +
                "Плашки ГОЛ и ШТРАФ будут озвучены. Прочитанный текст виден в шторке уведомлений."
            textSize = 16f
            setPadding(48, 32, 48, 32)
        }
        val start = Button(this).apply { text = "Старт комментатора" }
        val stop = Button(this).apply { text = "Стоп" }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(info)
            addView(start)
            addView(stop)
        })
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
        start.setOnClickListener {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), req)
        }
        stop.setOnClickListener { stopService(Intent(this, CaptureService::class.java)) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == req && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            startForegroundService(i)
        }
    }
}

class CaptureService : Service(), TextToSpeech.OnInitListener {
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var tts: TextToSpeech? = null
    private var ttsOk = false
    private var tess: TessBaseAPI? = null
    private val ht = HandlerThread("ocr").also { it.start() }
    private val handler by lazy { Handler(ht.looper) }

    private var lastRun = 0L
    private var lastKind = ""
    private var seen = 0
    private var spokenAt = 0L
    private var lastShown = ""
    private var tick = 0
    private var lastPeriod = 0
    private var candPeriod = 0
    private var candSeen = 0
    private var candTeams = ""
    private var spokenTeams = ""

    private val goalRe = Regex("Г[ОO0][ЛП]\\s*[-–—]")
    private val penRe = Regex("ШТР[АA]Ф")

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i == null) return START_NOT_STICKY
        showNotification("Запускаюсь…", first = true)
        tts = TextToSpeech(this, this)
        handler.post { initTess() }

        val code = i.getIntExtra("code", 0)
        val data = i.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)

        val dm = resources.displayMetrics
        val w = maxOf(dm.widthPixels, dm.heightPixels)
        val h = minOf(dm.widthPixels, dm.heightPixels)
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        vd = p.createVirtualDisplay(
            "khl-cap", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
        r.setOnImageAvailableListener({ onFrame(it) }, handler)
        return START_NOT_STICKY
    }

    private fun initTess() {
        val dir = File(filesDir, "tessdata").apply { mkdirs() }
        for (n in listOf("rus.traineddata", "eng.traineddata")) {
            val f = File(dir, n)
            if (!f.exists()) {
                assets.open("tessdata/$n").use { src -> f.outputStream().use { src.copyTo(it) } }
            }
        }
        val t = TessBaseAPI()
        if (t.init(filesDir.absolutePath, "rus+eng")) {
            tess = t
            showNotification("Слежу за плашками…")
        } else {
            showNotification("Ошибка: не удалось запустить распознавание")
        }
    }

    private fun onFrame(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRun < Cfg.INTERVAL_MS || tess == null) return
            lastRun = now
            val p = img.planes[0]
            val rowPad = p.rowStride - p.pixelStride * img.width
            val full = Bitmap.createBitmap(
                img.width + rowPad / p.pixelStride, img.height, Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(p.buffer)
            val x = (img.width * Cfg.BANNER_X).toInt()
            val y = (img.height * Cfg.BANNER_Y).toInt()
            val crop = Bitmap.createBitmap(full, x, y, (img.width * Cfg.BANNER_W).toInt(), img.height - y)
            analyze(crop)
            tick++
            if (tick % 3 == 0) {
                val board = Bitmap.createBitmap(
                    full, (img.width * Cfg.BOARD_X).toInt(), 0,
                    (img.width * Cfg.BOARD_W).toInt(), (img.height * Cfg.BOARD_H).toInt()
                )
                analyzeBoard(board)
            }
            full.recycle()
        } finally {
            img.close()
        }
    }

    private fun analyze(bmp: Bitmap) {
        val t = tess ?: return
        t.setImage(bmp)
        val text = t.getUTF8Text() ?: ""
        bmp.recycle()
        val lines = text.lines().map { it.trim() }.filter { it.length > 1 }

        val shown = lines.joinToString(" | ").take(200)
        if (shown != lastShown) {
            lastShown = shown
            showNotification(if (shown.isEmpty()) "Плашки нет" else "Вижу: $shown")
        }

        var kind = ""
        var idx = -1
        for ((i, l) in lines.withIndex()) {
            val u = l.uppercase()
            if (goalRe.containsMatchIn(u)) { kind = "goal"; idx = i; break }
            if (penRe.containsMatchIn(u)) { kind = "pen"; idx = i; break }
        }
        if (idx < 0) { lastKind = ""; seen = 0; return }

        if (kind == lastKind) seen++ else { lastKind = kind; seen = 1 }
        val now = SystemClock.elapsedRealtime()
        if (seen >= 2 && now - spokenAt > Cfg.COOLDOWN_MS) {
            spokenAt = now
            val l1 = lines.getOrNull(idx + 1) ?: ""
            val l2 = lines.getOrNull(idx + 2) ?: ""
            speak(Commentary.make(kind, l1, l2))
        }
    }

    // Табло: команды и период
    private val lookalike = mapOf(
        'А' to 'A', 'В' to 'B', 'С' to 'C', 'Е' to 'E', 'К' to 'K', 'М' to 'M',
        'Н' to 'H', 'О' to 'O', 'Р' to 'P', 'Т' to 'T', 'Х' to 'X'
    )
    private val periodRe = Regex("([1-4])\\s*[-–—~]\\s*[ЙИNH]")
    private val teamRe = Regex("[A-ZА-Я]{3}")

    private fun analyzeBoard(bmp: Bitmap) {
        val t = tess ?: return
        t.setImage(bmp)
        val text = (t.getUTF8Text() ?: "").uppercase()
        bmp.recycle()

        val teams = teamRe.findAll(text)
            .map { m -> m.value.map { lookalike[it] ?: it }.joinToString("") }
            .filter { it != "MPH" && it.all { c -> c in 'A'..'Z' } }
            .toList()
        val period = periodRe.find(text)?.groupValues?.get(1)?.toInt() ?: return
        if (teams.size < 2) return
        val pair = teams[0] + "-" + teams[1]

        if (period == candPeriod && pair == candTeams) candSeen++
        else { candPeriod = period; candTeams = pair; candSeen = 1 }
        if (candSeen < 2 || period == lastPeriod) return

        val newGame = lastPeriod == 0 || (period == 1 && lastPeriod >= 3) || pair != spokenTeams
        if (newGame && period == 1) {
            spokenTeams = pair
            lastPeriod = 1
            speak(Commentary.opening(teams[0], teams[1]))
        } else if (lastPeriod == 0) {
            lastPeriod = period // зашли в середину матча, молча запоминаем период
            spokenTeams = pair
        } else if (period == lastPeriod + 1) {
            lastPeriod = period
            speak(Commentary.periodStart(period))
        }
    }

    private fun speak(s: String) {
        if (ttsOk) tts?.speak(s, TextToSpeech.QUEUE_ADD, null, "u")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("ru", "RU")
            ttsOk = true
            speak("Комментатор на связи")
        }
    }

    private fun showNotification(text: String, first: Boolean = false) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("c", "Комментатор", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "c")
            .setContentTitle("КХЛ-комментатор")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOnlyAlertOnce(true)
            .build()
        if (first) {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, n)
            }
        } else {
            nm.notify(1, n)
        }
    }

    override fun onDestroy() {
        vd?.release()
        reader?.close()
        projection?.stop()
        tts?.shutdown()
        handler.post { tess?.recycle() }
        ht.quitSafely()
        super.onDestroy()
    }
}

object Commentary {
    private val goals = listOf(
        "Гол! %s забрасывает шайбу! %s",
        "Шайба в воротах! Отличился %s! %s",
        "Есть гол! Это сделал %s. %s"
    )
    private val pens = listOf(
        "Удаление! %s отправляется на скамью штрафников. Причина: %s.",
        "Судья показывает на %s. Причина: %s.",
        "%s наказан. Причина: %s. Команда остаётся в меньшинстве."
    )

    fun make(kind: String, l1: String, l2: String): String {
        val name = translit(l1.replace(Regex("^[#№]?\\s*\\d+\\s*"), "").trim())
            .ifBlank { "игрок" }
        return if (kind == "goal") {
            val low = l2.lowercase()
            val assist = when {
                l2.isBlank() -> ""
                low.contains("без") -> "Без ассистентов."
                else -> "Передачи: ${translit(l2)}."
            }
            goals.random().format(name, assist).trim()
        } else {
            val reason = l2.substringAfter("-", l2).trim().lowercase().ifBlank { "нарушение правил" }
            pens.random().format(name, reason)
        }
    }

    // Сокращения команд на табло -> как говорить. Незнакомые читаются как есть,
    // добавляй свои строки сюда.
    private val teams = mapOf(
        "CSK" to "ЦСКА", "AKB" to "Ак Барс", "SKA" to "СКА", "DYN" to "Динамо Москва",
        "DMO" to "Динамо Москва", "SPA" to "Спартак", "SPR" to "Спартак", "LOK" to "Локомотив",
        "MMG" to "Металлург", "MET" to "Металлург", "AVT" to "Автомобилист", "TRK" to "Трактор",
        "TRA" to "Трактор", "SAL" to "Салават Юлаев", "SYU" to "Салават Юлаев", "AVG" to "Авангард",
        "AVA" to "Авангард", "SIB" to "Сибирь", "NKH" to "Нефтехимик", "NEF" to "Нефтехимик",
        "TOR" to "Торпедо", "SEV" to "Северсталь", "DMN" to "Динамо Минск", "DMI" to "Динамо Минск",
        "LAD" to "Лада", "AMR" to "Амур", "AMU" to "Амур", "VIT" to "Витязь", "SCH" to "Сочи",
        "SOC" to "Сочи", "ADM" to "Адмирал", "KUN" to "Куньлунь", "SHA" to "Шанхайские Драконы",
        "BAR" to "Барыс", "HCS" to "Сочи"
    )

    fun teamName(abbr: String) = teams[abbr] ?: abbr.lowercase().toList().joinToString(" ")

    fun opening(a: String, b: String): String =
        "Добрый вечер! Сегодня играют ${teamName(a)} и ${teamName(b)}. Начинается первый период!"

    fun periodStart(p: Int): String {
        val n = when (p) { 2 -> "второй"; 3 -> "третий"; 4 -> "четвёртый"; else -> "$p-й" }
        return listOf("Начинается $n период!", "Команды вышли на $n период.").random()
    }

    private val multi = listOf(
        "shch" to "щ", "sch" to "щ", "zh" to "ж", "kh" to "х", "ch" to "ч", "sh" to "ш",
        "ts" to "ц", "yu" to "ю", "ya" to "я", "yo" to "ё", "ye" to "е",
        "iy" to "ий", "yy" to "ый", "ei" to "ей", "ey" to "ей"
    )
    private val single = mapOf(
        'a' to "а", 'b' to "б", 'c' to "к", 'd' to "д", 'e' to "е", 'f' to "ф", 'g' to "г",
        'h' to "х", 'i' to "и", 'j' to "дж", 'k' to "к", 'l' to "л", 'm' to "м", 'n' to "н",
        'o' to "о", 'p' to "п", 'q' to "к", 'r' to "р", 's' to "с", 't' to "т", 'u' to "у",
        'v' to "в", 'w' to "в", 'x' to "кс", 'y' to "и", 'z' to "з"
    )

    fun translit(s: String): String = Regex("[A-Za-z]+").replace(s) { m ->
        var w = m.value.lowercase()
        for ((a, b) in multi) w = w.replace(a, b)
        val sb = StringBuilder()
        for (ch in w) sb.append(single[ch] ?: ch.toString())
        sb.toString().replaceFirstChar { it.uppercase() }
    }
}
