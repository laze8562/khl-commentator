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
import android.media.AudioAttributes
import android.media.ImageReader
import android.media.MediaPlayer
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.googlecode.tesseract.android.TessBaseAPI
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------
// Настройки: какая часть экрана читается (доли от ширины/высоты).
// Нижняя плашка с надписями «ГОЛ», «МАЛ. ШТРАФ» и т.д.
// Если плашка не читается, подгоняй эти числа.
// ---------------------------------------------------------------
object Cfg {
    const val BANNER_X = 0.28      // левый край
    const val BANNER_W = 0.57      // ширина
    const val BANNER_Y = 0.72      // верхний край
    const val BOARD_X = 0.09       // табло сверху: левый край
    const val BOARD_W = 0.53       // ширина
    const val BOARD_H = 0.075      // высота
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
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val keyEdit = EditText(this).apply {
            hint = "Ключ ElevenLabs или Fish Audio (необязательно)"
            setText(prefs.getString("key", ""))
            setSingleLine()
        }
        val voiceEdit = EditText(this).apply {
            hint = "ID голоса (необязательно)"
            setText(prefs.getString("voice", ""))
            setSingleLine()
        }
        val fishBox = CheckBox(this).apply {
            text = "Использовать Fish Audio (вместо ElevenLabs)"
            isChecked = prefs.getBoolean("fish", false)
        }
        val start = Button(this).apply { text = "Старт комментатора" }
        val stop = Button(this).apply { text = "Стоп" }
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(32, 16, 32, 16)
                addView(info)
                addView(keyEdit)
                addView(voiceEdit)
                addView(fishBox)
                addView(start)
                addView(stop)
            })
        })
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
        start.setOnClickListener {
            prefs.edit()
                .putString("key", keyEdit.text.toString().trim())
                .putString("voice", voiceEdit.text.toString().trim())
                .putBoolean("fish", fishBox.isChecked)
                .apply()
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
        if (elevenOn) speak("Комментатор на связи")
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

    // ---------- Распознавание ----------
    // Игра рисует текст на пёстром фоне, поэтому перед чтением оставляем только
    // нужный цвет (белый, синий или оранжевый), всё остальное заливаем белым.
    private fun mask(src: Bitmap, scale: Int, pred: (Int, Int, Int) -> Boolean): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            px[i] = if (pred((c shr 16) and 255, (c shr 8) and 255, c and 255)) -0x1000000 else -1
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        if (scale <= 1) return out
        val big = Bitmap.createScaledBitmap(out, w * scale, h * scale, true)
        out.recycle()
        return big
    }

    private fun ocr(bmp: Bitmap, line: Boolean): String {
        val t = tess ?: return ""
        t.setPageSegMode(
            if (line) TessBaseAPI.PageSegMode.PSM_SINGLE_LINE else TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
        )
        t.setImage(bmp)
        val s = t.getUTF8Text() ?: ""
        bmp.recycle()
        return s
    }

    private val nameRe = Regex("[A-Za-z]{3,}")
    private val goodNameRe = Regex("^[A-Z][a-z]{2,}(\\s+[A-Z][a-z]{2,}){1,2}$")
    private val penWhiteRe = Regex("[МM][ИMUNH][НHN]\\s*[-–—]")
    private var evKind = ""
    private var evSeen = 0
    private var evMiss = 0
    private var evName = ""
    private var evScore = -1
    private var evAssist = ""
    private var evReason = ""
    private var dbgBanner = ""
    private var dbgBoard = ""

    private fun resetEvent() {
        evKind = ""; evSeen = 0; evName = ""; evScore = -1; evAssist = ""; evReason = ""
    }

    private fun refreshNote() {
        showNotification("Плашка: $dbgBanner\nТабло: $dbgBoard")
    }

    private fun analyze(crop: Bitmap) {
        val navy = ocr(mask(crop, 1) { r, g, b -> b > 90 && r < 90 && b - r > 50 && b > g }, false).uppercase()
        var kind = when {
            goalRe.containsMatchIn(navy) -> "goal"
            penRe.containsMatchIn(navy) -> "pen"
            else -> ""
        }
        var white = ""
        if (kind.isNotEmpty() || tick % 2 == 0) {
            white = ocr(mask(crop, 1) { r, g, b -> minOf(r, g, b) > 195 }, false)
            if (kind.isEmpty() && penWhiteRe.containsMatchIn(white.uppercase())) kind = "pen"
        }
        crop.recycle()

        val shown = "[" + navy.trim().replace('\n', ' ') + "] [" +
            white.trim().replace('\n', '|') + "]"
        if (shown.take(160) != dbgBanner) {
            dbgBanner = shown.take(160)
            refreshNote()
        }

        if (kind.isEmpty()) {
            if (++evMiss >= 2) resetEvent()
            return
        }
        evMiss = 0
        if (kind != evKind) { resetEvent(); evKind = kind }
        evSeen++
        if (white.isNotEmpty()) parseWhite(white)

        val now = SystemClock.elapsedRealtime()
        if ((evName.isNotEmpty() && evSeen >= 3 || evSeen >= 5) && now - spokenAt > Cfg.COOLDOWN_MS) {
            spokenAt = now
            speak(Commentary.make(evKind, evName, if (evKind == "goal") evAssist else evReason))
        }
    }

    private fun parseWhite(white: String) {
        val lines = white.lines().map { it.trim() }.filter { it.length > 2 }
        val names = ArrayList<String>()
        for (l in lines) {
            val c = l.replace(Regex("[^A-Za-z#0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            if (!nameRe.containsMatchIn(c)) continue
            val n = c.replace(Regex("^[#0-9 ]+"), "").replace(Regex("^[^A-Z]+"), "").trim()
            if (n.length < 4) continue
            names.add(n)
            val sc = (if (goodNameRe.matches(n)) 100 else 0) + n.length
            if (sc > evScore) { evScore = sc; evName = n }
        }
        for (l in lines) {
            val u = l.uppercase()
            if (u.contains("АССИ") || u.contains("БЕЗ ")) evAssist = "Без ассистентов"
            if (penWhiteRe.containsMatchIn(u)) {
                evReason = l.split(Regex("[-–—]"), limit = 2).getOrElse(1) { "" }.trim()
            }
        }
        if (evKind == "goal" && evAssist.isEmpty()) {
            val others = names.filter { it != evName && goodNameRe.matches(it) }
            if (others.isNotEmpty()) evAssist = others.joinToString(", ")
        }
    }

    // Табло: команды и период
    private val lookalike = mapOf(
        'А' to 'A', 'В' to 'B', 'С' to 'C', 'Е' to 'E', 'К' to 'K', 'М' to 'M',
        'Н' to 'H', 'О' to 'O', 'Р' to 'P', 'Т' to 'T', 'Х' to 'X'
    )
    private val digitFix = mapOf('6' to 'C', '5' to 'S', '0' to 'O', '8' to 'B', '1' to 'I')
    private val periodRe = Regex("([1-4])\\s*[-–—~]\\s*[ЙИNH]")
    private var knownPair = ""

    private fun analyzeBoard(board: Bitmap) {
        val w = board.width
        val h = board.height
        val txt = ocr(
            mask(board, 2) { r, g, b ->
                minOf(r, g, b) > 185 || (r > 190 && g in 110..214 && b < 110)
            }, true
        ).uppercase()
        val rx = (w * 0.72).toInt()
        val right = Bitmap.createBitmap(board, rx, 0, w - rx, h)
        board.recycle()
        val per = ocr(mask(right, 3) { r, g, b -> r > 190 && g in 110..214 && b < 110 }, true).uppercase()
        right.recycle()

        val shown = "[" + txt.trim() + "] [" + per.trim() + "]"
        if (shown != dbgBoard) { dbgBoard = shown; refreshNote() }

        val teams = Regex("[A-ZА-Я0-9]{3}").findAll(txt)
            .filter { m -> m.value.any { it in 'A'..'Z' || it in 'А'..'Я' } }
            .map { m -> m.value.map { lookalike[it] ?: it }.map { digitFix[it] ?: it }.joinToString("") }
            .filter { it != "MPH" && it.all { c -> c in 'A'..'Z' } }
            .toList()
        if (teams.size >= 2) knownPair = teams[0] + "-" + teams[1]

        val period = periodRe.find(per)?.groupValues?.get(1)?.toIntOrNull()
            ?: periodRe.find(txt)?.groupValues?.get(1)?.toIntOrNull()
            ?: return

        if (period == candPeriod) candSeen++ else { candPeriod = period; candSeen = 1 }
        if (candSeen < 2 || period == lastPeriod) return

        val a = knownPair.substringBefore("-", "").ifEmpty { null }
        val b = knownPair.substringAfter("-", "").ifEmpty { null }
        if (period == 1 && (lastPeriod == 0 || lastPeriod >= 3)) {
            lastPeriod = 1
            speak(Commentary.opening(a, b))
        } else if (lastPeriod == 0) {
            lastPeriod = period // зашли в середину матча, молча запоминаем период
        } else if (period == lastPeriod + 1) {
            lastPeriod = period
            speak(Commentary.periodStart(period))
        }
    }

    // Озвучка: если в приложении введены ключ и ID голоса ElevenLabs, говорит он,
    // иначе (или при ошибке сети) обычный голос Android.
    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }
    private val apiKey get() = prefs.getString("key", "")?.trim().orEmpty()
    private val voiceId get() = prefs.getString("voice", "")?.trim().orEmpty()
    private val useFish get() = prefs.getBoolean("fish", false)
    private val elevenOn get() = apiKey.isNotEmpty() && voiceId.isNotEmpty()
    private val audioExec = Executors.newSingleThreadExecutor()

    private fun speak(s: String) {
        if (elevenOn) {
            audioExec.execute {
                try {
                    playEleven(s)
                } catch (e: Exception) {
                    showNotification("Онлайн-голос не сработал: ${e.message}. Говорю голосом телефона")
                    if (ttsOk) tts?.speak(s, TextToSpeech.QUEUE_ADD, null, "u")
                }
            }
        } else if (ttsOk) {
            tts?.speak(s, TextToSpeech.QUEUE_ADD, null, "u")
        }
    }

    private fun playEleven(text: String) {
        val url = if (useFish) "https://api.fish.audio/v1/tts"
        else "https://api.elevenlabs.io/v1/text-to-speech/$voiceId"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10000
        conn.readTimeout = 20000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "audio/mpeg")
        conn.doOutput = true
        val body: String
        if (useFish) {
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("model", "s2.1-pro")
            body = JSONObject().put("text", text).put("reference_id", voiceId)
                .put("format", "mp3").put("latency", "balanced").toString()
        } else {
            conn.setRequestProperty("xi-api-key", apiKey)
            body = JSONObject().put("text", text).put("model_id", "eleven_multilingual_v2").toString()
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        if (conn.responseCode != 200) {
            val err = conn.errorStream?.bufferedReader()?.readText()?.take(120) ?: ""
            throw RuntimeException("код ${conn.responseCode} $err")
        }
        val f = File(cacheDir, "v${System.nanoTime()}.mp3")
        conn.inputStream.use { i -> f.outputStream().use { i.copyTo(it) } }
        val latch = CountDownLatch(1)
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener { latch.countDown() }
            mp.setOnErrorListener { _, _, _ -> latch.countDown(); true }
            mp.prepare()
            mp.start()
            latch.await(30, TimeUnit.SECONDS)
        } finally {
            mp.release()
            f.delete()
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            showNotification("Ошибка: синтез речи Android не запустился (код $status)")
            return
        }
        val t = tts ?: return
        t.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        val r = t.setLanguage(Locale("ru", "RU"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            showNotification("На телефоне нет русского голоса: обнови «Синтез речи Google» и скачай русский язык")
        }
        ttsOk = true
        if (!elevenOn) speak("Комментатор на связи")
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
        audioExec.shutdownNow()
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

    fun opening(a: String?, b: String?): String =
        if (a != null && b != null)
            "Добрый вечер! Сегодня играют ${teamName(a)} и ${teamName(b)}. Начинается первый период!"
        else "Добрый вечер! Начинается первый период!"

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
