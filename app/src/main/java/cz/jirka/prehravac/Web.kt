package cz.jirka.prehravac

import android.content.Context
import android.net.Uri
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject

/**
 * Prehravani videi z internetu.
 * - primy odkaz na soubor/stream (.mp4, .m3u8 ...) -> rovnou do mpv
 * - odkaz na stranku (YouTube, Vimeo, TikTok, zpravy ...) -> yt-dlp najde skutecnou adresu videa
 */
object Web {

    data class Stream(
        val video: String,
        val audio: String?,
        val title: String?,
        val headers: Map<String, String>
    )

    private val directExt = Regex(
        "\\.(mp4|m4v|mkv|webm|mov|avi|wmv|flv|ts|m2ts|3gp|ogv|mpg|mpeg|m3u8|mpd|mp3|m4a|aac|flac|ogg|opus|wav)$",
        RegexOption.IGNORE_CASE
    )
    private val urlRegex = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)

    private const val DAY = 24 * 60 * 60 * 1000L
    private var ready = false

    fun isWeb(uri: Uri) = uri.scheme.equals("http", true) || uri.scheme.equals("https", true)

    fun isDirect(uri: Uri) = directExt.containsMatchIn(uri.path ?: "")

    /** Vytahne prvni odkaz z textu (napr. ze sdileni z jine aplikace). */
    fun findUrl(text: String?): String? =
        text?.let { urlRegex.find(it)?.value?.trimEnd('.', ',', ')', ']') }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("web", Context.MODE_PRIVATE)

    @Synchronized
    private fun init(ctx: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(ctx.applicationContext)
        ready = true
    }

    /** Aktualizace yt-dlp (weby se meni, tak at to porad funguje). Vraci text pro uzivatele. */
    @Synchronized
    fun update(ctx: Context): String {
        init(ctx)
        val st = YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel.STABLE)
        prefs(ctx).edit().putLong("lastUpdate", System.currentTimeMillis()).apply()
        val ver = YoutubeDL.getInstance().versionName(ctx.applicationContext) ?: "?"
        return if (st == YoutubeDL.UpdateStatus.DONE) "yt-dlp aktualizováno ($ver)" else "yt-dlp je aktuální ($ver)"
    }

    fun version(ctx: Context): String =
        YoutubeDL.getInstance().versionName(ctx.applicationContext)
            ?: YoutubeDL.getInstance().version(ctx.applicationContext) ?: "přibalená (stará)"

    /** Na pozadi, max jednou za den. */
    fun autoUpdate(ctx: Context) {
        if (System.currentTimeMillis() - prefs(ctx).getLong("lastUpdate", 0) < DAY) return
        Thread {
            try { update(ctx) } catch (_: Throwable) {}
        }.start()
    }

    /**
     * Najde skutecnou adresu videa na strance. Bezi dlouho (sekundy) - volat mimo UI vlakno.
     * onStatus dostava texty typu "Hledám video…".
     */
    fun resolve(ctx: Context, url: String, onStatus: (String) -> Unit): Stream {
        onStatus("Připravuji…")
        init(ctx)
        // pribalene yt-dlp je stare - pred prvnim pouzitim aktualizovat
        if (prefs(ctx).getLong("lastUpdate", 0) == 0L) {
            onStatus("Stahuji aktuální yt-dlp (jen poprvé)…")
            try { update(ctx) } catch (_: Throwable) {}
        }
        onStatus("Hledám video na stránce…")
        return try {
            extract(url)
        } catch (e: Throwable) {
            // mozna web neco zmenil -> zkusit aktualizaci a jeste jednou
            onStatus("Nepovedlo se, zkouším aktualizovat yt-dlp…")
            val upd = try { update(ctx); true } catch (_: Throwable) { false }
            if (!upd) throw e
            onStatus("Hledám video na stránce…")
            extract(url)
        }
    }

    private fun extract(url: String): Stream {
        val req = YoutubeDLRequest(url)
        // nejlepsi kvalita do 1080p; video a zvuk muzou byt zvlast (mpv je spoji)
        req.addOption("-f", "bv*[height<=1080]+ba/b[height<=1080]/bv*+ba/b")
        req.addOption("--no-playlist")
        req.addOption("--no-warnings")
        req.addOption("--dump-json")
        val out = YoutubeDL.getInstance().execute(req).out
        val line = out.lineSequence().firstOrNull { it.trimStart().startsWith("{") }
            ?: throw IllegalStateException("yt-dlp nevrátilo žádné video")
        val j = JSONObject(line)
        val title = j.optString("title").ifBlank { null }

        val rf = j.optJSONArray("requested_formats")
        if (rf != null && rf.length() >= 2) {
            var video: JSONObject? = null
            var audio: JSONObject? = null
            for (i in 0 until rf.length()) {
                val f = rf.getJSONObject(i)
                val hasVideo = f.optString("vcodec", "none") != "none"
                if (hasVideo && video == null) video = f else if (audio == null) audio = f
            }
            val v = video ?: rf.getJSONObject(0)
            return Stream(v.getString("url"), audio?.optString("url")?.ifBlank { null }, title, headers(v, j))
        }
        val direct = j.optString("url").ifBlank { j.optString("manifest_url") }
        if (direct.isBlank()) throw IllegalStateException("Na stránce jsem nenašel adresu videa")
        return Stream(direct, null, title, headers(j, j))
    }

    private fun headers(f: JSONObject, root: JSONObject): Map<String, String> {
        val h = f.optJSONObject("http_headers") ?: root.optJSONObject("http_headers") ?: return emptyMap()
        val m = mutableMapOf<String, String>()
        for (k in h.keys()) m[k] = h.optString(k)
        return m
    }

    /** Kratka srozumitelna chyba z vystupu yt-dlp. */
    fun shortError(e: Throwable): String {
        val msg = e.message ?: e.javaClass.simpleName
        val line = msg.lineSequence().lastOrNull { it.contains("ERROR") } ?: msg.lineSequence().firstOrNull() ?: msg
        val clean = line.substringAfter("ERROR:").trim()
        return when {
            clean.contains("Unsupported URL", true) -> "Tenhle web yt-dlp nepodporuje"
            clean.contains("DRM", true) -> "Video je chráněné DRM – nejde přehrát"
            clean.contains("Sign in", true) || clean.contains("login", true) -> "Video vyžaduje přihlášení"
            clean.contains("Private", true) -> "Video je soukromé"
            clean.contains("Unable to download webpage", true) -> "Nedá se načíst stránka (internet?)"
            else -> clean.take(160)
        }
    }
}
