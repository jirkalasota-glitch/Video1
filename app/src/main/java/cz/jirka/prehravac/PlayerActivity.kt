package cz.jirka.prehravac

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.jdtech.mpv.MPVLib
import kotlin.math.abs

class PlayerActivity : ComponentActivity(), SurfaceHolder.Callback {

    private lateinit var uri: Uri
    private lateinit var audio: AudioManager
    private lateinit var gesturesView: View
    private lateinit var centerInfo: TextView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var seek: SeekBar
    private lateinit var timeCur: TextView
    private lateinit var timeDur: TextView
    private lateinit var btnPlay: Button
    private lateinit var btnSpeed: Button
    private lateinit var btnNorm: Button
    private lateinit var btnSleep: Button
    private lateinit var btnAB: Button

    private val handler = Handler(Looper.getMainLooper())
    private var fileLoaded = false
    private var duration = 0.0
    private var position = 0.0
    private var userSeeking = false
    private var controlsVisible = true
    private var orientationSet = false

    private val speeds = doubleArrayOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 3.0)
    private var speedIdx = 2
    private var normOn = false
    private var abStep = 0
    private val sleepSteps = intArrayOf(0, 15, 30, 45, 60, 90)
    private var sleepIdx = 0
    private var sleepAt = 0L

    // gesta
    private enum class Mode { NONE, SEEK, BRIGHT, VOL }
    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var startVal = 0f
    private var startPos = 0.0
    private var seekTarget = 0.0
    private var longPressSpeed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_player)
        hideSystemBars()

        uri = intent.data ?: run { finish(); return }
        audio = getSystemService(AUDIO_SERVICE) as AudioManager

        gesturesView = findViewById(R.id.gestures)
        centerInfo = findViewById(R.id.centerInfo)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        seek = findViewById(R.id.seek)
        timeCur = findViewById(R.id.timeCur)
        timeDur = findViewById(R.id.timeDur)
        btnPlay = findViewById(R.id.btnPlay)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnNorm = findViewById(R.id.btnNorm)
        btnSleep = findViewById(R.id.btnSleep)
        btnAB = findViewById(R.id.btnAB)
        findViewById<TextView>(R.id.title).text = displayName(uri)

        Mpv.init(this)
        findViewById<SurfaceView>(R.id.surface).holder.addCallback(this)

        setupButtons()
        setupSeekBar()
        setupGestures()
        handler.post(tick)
        scheduleHide()
    }

    private fun hideSystemBars() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ---------- mpv a povrch ----------

    override fun surfaceCreated(holder: SurfaceHolder) {
        MPVLib.attachSurface(holder.surface)
        MPVLib.setOptionString("force-window", "yes")
        MPVLib.setPropertyString("vo", "gpu")
        if (!fileLoaded) {
            fileLoaded = true
            loadFile()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setOptionString("force-window", "no")
        MPVLib.detachSurface()
    }

    private fun loadFile() {
        val path = try {
            contentResolver.openFileDescriptor(uri, "r")?.detachFd()?.let { "fdclose://$it" }
        } catch (_: Exception) {
            null
        } ?: uri.toString()

        val saved = Positions.get(this, uri)
        MPVLib.setPropertyString("start", if (saved > 5f) saved.toString() else "0")
        MPVLib.command(arrayOf("loadfile", path))
        if (saved > 5f) toast("Pokračuji od ${fmtTime(saved.toDouble())}")
    }

    private fun mpvDouble(name: String): Double =
        try { MPVLib.getPropertyDouble(name) ?: 0.0 } catch (_: Exception) { 0.0 }

    private fun mpvBool(name: String): Boolean =
        try { MPVLib.getPropertyBoolean(name) ?: false } catch (_: Exception) { false }

    private fun mpvString(name: String): String? =
        try { MPVLib.getPropertyString(name) } catch (_: Exception) { null }

    private val tick = object : Runnable {
        override fun run() {
            duration = mpvDouble("duration")
            position = mpvDouble("time-pos")
            val paused = mpvBool("pause")
            if (!userSeeking && mode != Mode.SEEK) {
                if (duration > 0) seek.progress = ((position / duration) * 1000).toInt()
                timeCur.text = fmtTime(position)
            }
            timeDur.text = fmtTime(duration)
            btnPlay.text = if (paused) "▶" else "⏸"

            if (!orientationSet) {
                val w = mpvDouble("video-params/dw")
                val h = mpvDouble("video-params/dh")
                if (w > 0 && h > 0) {
                    orientationSet = true
                    requestedOrientation =
                        if (w >= h) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                }
            }

            if (sleepAt > 0 && SystemClock.elapsedRealtime() >= sleepAt) {
                sleepAt = 0
                sleepIdx = 0
                btnSleep.text = "Spánek: vyp"
                MPVLib.setPropertyString("pause", "yes")
                savePosition()
                toast("Časovač spánku – pozastaveno")
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun savePosition() {
        if (duration <= 0) return
        if (position > 5 && position < duration - 10) Positions.set(this, uri, position.toFloat())
        else if (position >= duration - 10) Positions.clear(this, uri)
    }

    // ---------- ovladani ----------

    private fun setupButtons() {
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        btnPlay.setOnClickListener { MPVLib.command(arrayOf("cycle", "pause")); scheduleHide() }

        findViewById<Button>(R.id.btnRepair).setOnClickListener {
            MPVLib.setPropertyString("pause", "yes")
            startActivity(
                Intent(this, RepairActivity::class.java).setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }

        findViewById<Button>(R.id.btnSub).setOnClickListener {
            MPVLib.command(arrayOf("cycle", "sid"))
            handler.postDelayed({
                val sid = mpvString("sid")
                info(if (sid == null || sid == "no") "Titulky: vypnuto" else "Titulky: stopa $sid")
            }, 250)
            scheduleHide()
        }

        findViewById<Button>(R.id.btnAudio).setOnClickListener {
            MPVLib.command(arrayOf("cycle", "aid"))
            handler.postDelayed({
                val aid = mpvString("aid")
                info(if (aid == null || aid == "no") "Zvuk: vypnuto" else "Zvuková stopa $aid")
            }, 250)
            scheduleHide()
        }

        btnSpeed.setOnClickListener {
            speedIdx = (speedIdx + 1) % speeds.size
            setSpeed(speeds[speedIdx])
            btnSpeed.text = "Rychlost ${speedLabel(speeds[speedIdx])}"
            info(speedLabel(speeds[speedIdx]))
            scheduleHide()
        }

        btnNorm.setOnClickListener {
            normOn = !normOn
            // dynamicka normalizace: tiche dialogy nahlas, hlasite vybuchy ztlumit
            MPVLib.command(arrayOf("af", "toggle", "@norm:lavfi=[dynaudnorm=f=150:g=15:p=0.9]"))
            btnNorm.text = if (normOn) "Vyrovnat hlasitost: zap" else "Vyrovnat hlasitost: vyp"
            info(if (normOn) "Hlasitost se vyrovnává" else "Vyrovnání vypnuto")
            scheduleHide()
        }

        btnSleep.setOnClickListener {
            sleepIdx = (sleepIdx + 1) % sleepSteps.size
            val min = sleepSteps[sleepIdx]
            if (min == 0) {
                sleepAt = 0
                btnSleep.text = "Spánek: vyp"
                info("Časovač vypnut")
            } else {
                sleepAt = SystemClock.elapsedRealtime() + min * 60_000L
                btnSleep.text = "Spánek: $min min"
                info("Zastavím za $min min")
            }
            scheduleHide()
        }

        btnAB.setOnClickListener {
            MPVLib.command(arrayOf("ab-loop"))
            abStep = (abStep + 1) % 3
            when (abStep) {
                1 -> { btnAB.text = "Smyčka: A nastaveno"; info("Bod A") }
                2 -> { btnAB.text = "Smyčka A-B: běží"; info("Bod B – opakuji úsek") }
                else -> { btnAB.text = "Smyčka A-B"; info("Smyčka zrušena") }
            }
            scheduleHide()
        }

        findViewById<Button>(R.id.btnFrame).setOnClickListener {
            MPVLib.command(arrayOf("frame-step"))
            scheduleHide()
        }

        findViewById<Button>(R.id.btnRotate).setOnClickListener {
            orientationSet = true
            requestedOrientation =
                if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            scheduleHide()
        }
    }

    private fun speedLabel(s: Double) =
        (if (s == s.toLong().toDouble()) s.toLong().toString() else s.toString()) + "×"

    private fun setSpeed(s: Double) = MPVLib.setPropertyString("speed", s.toString())

    private fun setupSeekBar() {
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && duration > 0) timeCur.text = fmtTime(duration * progress / 1000.0)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                userSeeking = true
                handler.removeCallbacks(hideRunnable)
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                if (duration > 0) seekAbs(duration * sb.progress / 1000.0)
                scheduleHide()
            }
        })
    }

    private fun seekAbs(sec: Double) =
        MPVLib.command(arrayOf("seek", sec.coerceAtLeast(0.0).toString(), "absolute"))

    private fun seekRel(sec: Int) = MPVLib.command(arrayOf("seek", sec.toString(), "relative"))

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val w = gesturesView.width
                when {
                    e.x < w / 3f -> { seekRel(-10); info("⏪ 10 s") }
                    e.x > w * 2 / 3f -> { seekRel(10); info("10 s ⏩") }
                    else -> MPVLib.command(arrayOf("cycle", "pause"))
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (mode != Mode.NONE) return
                longPressSpeed = true
                setSpeed(2.0)
                centerInfo.text = "2× ⏩"
                centerInfo.visibility = View.VISIBLE
            }
        })

        gesturesView.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; mode = Mode.NONE
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (mode == Mode.NONE && !longPressSpeed && (abs(dx) > 40 || abs(dy) > 40)) {
                        mode = when {
                            abs(dx) > abs(dy) -> { startPos = position; Mode.SEEK }
                            downX < v.width / 2f -> {
                                val b = window.attributes.screenBrightness
                                startVal = if (b < 0) 0.5f else b
                                Mode.BRIGHT
                            }
                            else -> {
                                startVal = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                                Mode.VOL
                            }
                        }
                    }
                    when (mode) {
                        Mode.SEEK -> {
                            // pres celou sirku obrazovky = 2 minuty
                            val delta = dx / v.width * 120.0
                            seekTarget = (startPos + delta).coerceIn(0.0, if (duration > 0) duration else Double.MAX_VALUE)
                            val sign = if (delta >= 0) "+" else "−"
                            info("$sign${abs(delta).toInt()} s  (${fmtTime(seekTarget)})", 0)
                        }
                        Mode.BRIGHT -> {
                            val b = (startVal - dy / v.height * 1.2f).coerceIn(0.01f, 1f)
                            val lp = window.attributes
                            lp.screenBrightness = b
                            window.attributes = lp
                            info("☀ ${(b * 100).toInt()} %", 0)
                        }
                        Mode.VOL -> {
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val vol = (startVal - dy / v.height * max * 1.2f).toInt().coerceIn(0, max)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
                            info("🔊 ${vol * 100 / max} %", 0)
                        }
                        Mode.NONE -> {}
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (mode == Mode.SEEK) seekAbs(seekTarget)
                    if (longPressSpeed) {
                        longPressSpeed = false
                        setSpeed(speeds[speedIdx])
                    }
                    if (mode != Mode.NONE || centerInfo.visibility == View.VISIBLE) hideInfoLater()
                    mode = Mode.NONE
                }
            }
            if (mode == Mode.NONE) detector.onTouchEvent(ev)
            true
        }
    }

    // ---------- zobrazovani ----------

    private val hideInfo = Runnable { centerInfo.visibility = View.GONE }
    private val hideRunnable = Runnable { setControls(false) }

    private fun info(text: String, hideAfter: Long = 900) {
        centerInfo.text = text
        centerInfo.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        if (hideAfter > 0) handler.postDelayed(hideInfo, hideAfter)
    }

    private fun hideInfoLater() {
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 600)
    }

    private fun toggleControls() = setControls(!controlsVisible)

    private fun setControls(show: Boolean) {
        controlsVisible = show
        val vis = if (show) View.VISIBLE else View.GONE
        topBar.visibility = vis
        bottomBar.visibility = vis
        if (show) scheduleHide() else hideSystemBars()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 4000)
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // ---------- zivotni cyklus ----------

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        savePosition()
        if (fileLoaded) MPVLib.setPropertyString("pause", "yes")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::uri.isInitialized) {
            savePosition()
            try {
                MPVLib.command(arrayOf("stop"))
            } catch (_: Exception) {
            }
            Mpv.destroy()
        }
        super.onDestroy()
    }
}
