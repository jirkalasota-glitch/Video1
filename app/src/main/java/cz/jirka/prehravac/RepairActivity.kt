package cz.jirka.prehravac

import android.content.ContentValues
import android.content.Intent
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class RepairActivity : ComponentActivity() {

    private lateinit var uri: Uri
    private lateinit var diagView: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnQuick: Button
    private lateinit var btnFull: Button
    private lateinit var btnPlayFixed: Button

    private val exec = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var outFile: File? = null
    private var fixedUri: Uri? = null
    private var name = "video"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_repair)
        uri = intent.data ?: run { finish(); return }
        name = displayName(uri)

        findViewById<TextView>(R.id.rName).text = name
        diagView = findViewById(R.id.rDiag)
        status = findViewById(R.id.rStatus)
        progress = findViewById(R.id.rProgress)
        btnQuick = findViewById(R.id.btnQuick)
        btnFull = findViewById(R.id.btnFull)
        btnPlayFixed = findViewById(R.id.btnPlayFixed)

        btnQuick.setOnClickListener { startRepair(transcode = false) }
        btnFull.setOnClickListener { startRepair(transcode = true) }
        btnPlayFixed.setOnClickListener {
            fixedUri?.let {
                startActivity(Intent(this, PlayerActivity::class.java).setData(it))
            }
        }

        exec.execute {
            val d = try { diagnose() } catch (e: Exception) { Diag("Analýza selhala: ${e.message}", true, false) }
            runOnUiThread {
                diagView.text = d.text
                btnQuick.isEnabled = d.canTry
                btnFull.isEnabled = d.canTry
                status.text = when {
                    !d.canTry -> ""
                    d.recommendFull -> "Doporučuji: Plná oprava."
                    else -> "Doporučuji: Rychlá oprava (zkus nejdřív tu)."
                }
            }
        }
    }

    // ---------- diagnostika ----------

    private data class Diag(val text: String, val canTry: Boolean, val recommendFull: Boolean)

    /** Projde zakladni "krabice" MP4/MOV souboru (ftyp, moov, mdat...). */
    private fun scanMp4Boxes(): List<String>? {
        val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return null
        pfd.use {
            FileInputStream(it.fileDescriptor).channel.use { ch ->
                val total = ch.size()
                val boxes = mutableListOf<String>()
                var pos = 0L
                val hdr = ByteBuffer.allocate(16)
                while (pos + 8 <= total && boxes.size < 64) {
                    hdr.clear(); hdr.limit(8)
                    ch.position(pos)
                    if (ch.read(hdr) < 8) break
                    hdr.flip()
                    var size = hdr.int.toLong() and 0xFFFFFFFFL
                    val typeBytes = ByteArray(4); hdr.get(typeBytes)
                    val type = String(typeBytes, Charsets.ISO_8859_1)
                    if (boxes.isEmpty() && type != "ftyp" && type != "wide" && type != "free" &&
                        type != "mdat" && type != "moov" && type != "skip"
                    ) return null // neni MP4/MOV
                    if (!type.all { c -> c.code in 32..126 }) { boxes += "?poškozeno"; break }
                    if (size == 1L) {
                        hdr.clear(); hdr.limit(8)
                        ch.position(pos + 8)
                        if (ch.read(hdr) < 8) break
                        hdr.flip(); size = hdr.long
                    } else if (size == 0L) size = total - pos
                    boxes += type
                    if (size < 8) { boxes += "?poškozeno"; break }
                    if (pos + size > total) { boxes += "?useknuto"; break }
                    pos += size
                }
                return boxes
            }
        }
    }

    private fun diagnose(): Diag {
        val sb = StringBuilder()
        val problems = mutableListOf<String>()
        var recommendFull = false

        val size = fileSize(uri)
        if (size >= 0) sb.append("Velikost: ${fmtSize(size)}\n")
        if (size == 0L) return Diag("Soubor je prázdný (0 bajtů). Tady už není co zachránit.", false, false)

        val boxes = try { scanMp4Boxes() } catch (_: Exception) { null }
        if (boxes != null) {
            sb.append("Struktura MP4: ${boxes.joinToString(", ")}\n")
            if ("mdat" in boxes && "moov" !in boxes) {
                sb.append(
                    "\n❌ Chybí index videa (moov). Typicky: nahrávání se nedokončilo – vybitý telefon, " +
                        "pád aplikace, nedokončené stažení.\n\nObraz a zvuk v souboru jsou, ale bez indexu je " +
                        "neumí nic otevřít. Obnovu pomocí referenčního videa ze stejného telefonu přidáme " +
                        "v další verzi."
                )
                return Diag(sb.toString(), false, false)
            }
            if ("?useknuto" in boxes) problems += "Soubor je useknutý (konec chybí) – přehraje se jen část."
            if ("?poškozeno" in boxes) problems += "Struktura souboru je uprostřed poškozená."
        }

        val ex = MediaExtractor()
        try {
            try {
                ex.setDataSource(this, uri, null)
            } catch (e: Exception) {
                sb.append(
                    "\n⚠ Vestavěný Android tenhle soubor neotevře (formát jako AVI, WMV, FLV nebo poškozená hlavička). " +
                        "Tento přehrávač ho přehraje přes mpv. Plná oprava se ho pokusí převést do MP4, " +
                        "které otevře každé zařízení."
                )
                return Diag(sb.toString(), true, true)
            }

            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            var videoTrack = -1
            var declaredDurUs = 0L
            sb.append("\nStopy:\n")
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: "?"
                if (f.containsKey(MediaFormat.KEY_DURATION))
                    declaredDurUs = maxOf(declaredDurUs, f.getLong(MediaFormat.KEY_DURATION))
                val line = StringBuilder(" • $mime")
                if (mime.startsWith("video/")) {
                    if (videoTrack < 0) videoTrack = i
                    if (f.containsKey(MediaFormat.KEY_WIDTH))
                        line.append("  ${f.getInteger(MediaFormat.KEY_WIDTH)}×${f.getInteger(MediaFormat.KEY_HEIGHT)}")
                    if (f.containsKey("rotation-degrees")) {
                        val r = f.getInteger("rotation-degrees")
                        if (r != 0) line.append("  otočeno o $r°")
                    }
                }
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    val dec = try { codecs.findDecoderForFormat(f) } catch (_: Exception) { null }
                    if (dec == null) {
                        line.append("  ⚠ telefon neumí dekódovat")
                        problems += "Kodek $mime telefon sám neumí. mpv ho přehraje, ale jinde video nepůjde – pomůže Plná oprava."
                        recommendFull = true
                    }
                }
                sb.append(line).append('\n')
            }
            if (videoTrack < 0) problems += "V souboru není žádná video stopa."
            if (declaredDurUs <= 0) problems += "Chybí údaj o délce videa (posouvání nefunguje správně)."

            // projdi casove znacky video stopy
            if (videoTrack >= 0) {
                ex.selectTrack(videoTrack)
                var last = -1L
                var lastMax = 0L
                var count = 0
                var backwards = 0
                var gaps = 0
                while (count < 300_000) {
                    val t = ex.sampleTime
                    if (t < 0) break
                    if (last >= 0) {
                        if (t < last - 1_000_000) backwards++
                        if (t - last > 2_000_000) gaps++
                    }
                    if (t > lastMax) lastMax = t
                    last = t
                    count++
                    if (!ex.advance()) break
                }
                sb.append("Snímků přečteno: $count\n")
                if (backwards > 0) problems += "Časové značky jdou $backwards× pozpátku (trhání, rozjetý zvuk)."
                if (gaps > 0) problems += "V obraze je $gaps děr delších než 2 s (chybějící úseky)."
                if (declaredDurUs > 0 && lastMax > 0 && abs(declaredDurUs - lastMax) > 5_000_000) {
                    problems += "Uvedená délka (${fmtTime(declaredDurUs / 1e6)}) nesedí se skutečnou (${fmtTime(lastMax / 1e6)})."
                }
                if (count == 0) problems += "Video stopa neobsahuje žádné čitelné snímky."
            }
        } finally {
            ex.release()
        }

        if (problems.isEmpty()) {
            sb.append("\n✅ Nenašel jsem žádný problém. Pokud se video přesto chová divně, zkus Rychlou opravu – přestaví index a časové značky.")
        } else {
            sb.append("\nNalezené problémy:\n")
            problems.forEach { sb.append(" ❗ ").append(it).append('\n') }
        }
        return Diag(sb.toString(), true, recommendFull)
    }

    private fun abs(v: Long) = if (v < 0) -v else v

    // ---------- oprava ----------

    private fun startRepair(transcode: Boolean) {
        btnQuick.isEnabled = false
        btnFull.isEnabled = false
        btnPlayFixed.visibility = View.GONE
        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = if (transcode) "Přepočítávám video… (může trvat i několik minut)" else "Přestavuji soubor…"

        val out = File(cacheDir, "oprava_${System.currentTimeMillis()}.mp4")
        outFile = out

        val itemBuilder = EditedMediaItem.Builder(MediaItem.fromUri(uri))
        if (transcode) {
            // "prazdny" efekt donuti kompletni prepocitani obrazu
            itemBuilder.setEffects(Effects(listOf(), listOf(Brightness(0f))))
        }

        val builder = Transformer.Builder(this)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    saveResult(out)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    handler.removeCallbacks(progressTick)
                    progress.visibility = View.GONE
                    out.delete()
                    btnQuick.isEnabled = true
                    btnFull.isEnabled = true
                    val reason = exportException.cause?.message ?: exportException.message ?: "neznámá chyba"
                    status.text = buildString {
                        append("❌ Oprava se nepovedla.\n")
                        append("Důvod: ").append(reason).append("\n\n")
                        append(
                            if (!transcode) "Zkus Plnou opravu – přepočítá celé video."
                            else "Tenhle typ poškození zatím neumím. Video ale zkus přehrát – mpv přeskakuje rozbitá místa."
                        )
                    }
                }
            })
        if (transcode) {
            builder.setVideoMimeType(MimeTypes.VIDEO_H264)
            builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
        }
        val t = builder.build()
        transformer = t
        t.start(itemBuilder.build(), out.absolutePath)
        handler.post(progressTick)
    }

    private val progressTick = object : Runnable {
        private val holder = ProgressHolder()
        override fun run() {
            val t = transformer ?: return
            if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                progress.progress = holder.progress
            }
            handler.postDelayed(this, 300)
        }
    }

    private fun saveResult(out: File) {
        handler.removeCallbacks(progressTick)
        progress.progress = 100
        status.text = "Ukládám…"
        exec.execute {
            val base = name.substringBeforeLast('.')
            val newName = "${base}_opraveno.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, newName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Opravene")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val result = try {
                val dest = contentResolver.insert(
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
                ) ?: throw IllegalStateException("nelze vytvořit soubor")
                contentResolver.openOutputStream(dest)!!.use { os -> out.inputStream().use { it.copyTo(os) } }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(dest, values, null, null)
                dest
            } catch (e: Exception) {
                null
            } finally {
                out.delete()
            }
            runOnUiThread {
                progress.visibility = View.GONE
                btnQuick.isEnabled = true
                btnFull.isEnabled = true
                if (result != null) {
                    fixedUri = result
                    status.text = "✅ Hotovo. Uloženo jako Movies/Opravene/$newName\nPůvodní video zůstalo beze změny."
                    btnPlayFixed.visibility = View.VISIBLE
                } else {
                    status.text = "❌ Opravené video se nepodařilo uložit (málo místa?)."
                }
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        transformer?.cancel()
        outFile?.takeIf { it.exists() && fixedUri == null }?.delete()
        super.onDestroy()
    }
}
