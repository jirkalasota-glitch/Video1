package cz.jirka.prehravac

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Kdyz yt-dlp web nezna: otevre stranku ve vestavenem prohlizeci a odchytava,
 * jake video soubory / streamy stranka stahuje (m3u8, mp4 ...). Ty pak pusti v prehravaci.
 */
class SnifferActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var btnPlay: Button
    private lateinit var pageUrl: String
    private val found = LinkedHashMap<String, String>() // url -> referer (stranka/iframe)

    private val media = Regex(
        "\\.(m3u8|mpd|mp4|m4v|webm|mkv|mov|flv)(\\?|#|$)|/manifest(\\.|/|\\?)|mime=video",
        RegexOption.IGNORE_CASE
    )
    // reklamy a kousky streamu nechceme
    private val junk = Regex(
        "doubleclick|googlesyndication|googleads|adservice|/ads?/|vast|preroll|\\.m4s|/seg[-_]?\\d|/chunk|init\\.mp4|thumb|preview|sprite",
        RegexOption.IGNORE_CASE
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pageUrl = intent.dataString ?: run { finish(); return }

        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
        }
        val back = Button(this, null, 0, R.style.Btn).apply { text = "✕"; setOnClickListener { finish() } }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            text = "Hledám video… Když se nic nenajde, klepni na stránce na přehrát ▶"
        }
        btnPlay = Button(this, null, 0, R.style.Btn).apply {
            text = "▶ Přehrát"
            setTextColor(Color.parseColor("#4FC3F7"))
            visibility = View.GONE
            setOnClickListener { choose() }
        }
        bar.addView(back)
        bar.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(btnPlay)

        web = WebView(this)
        root.addView(bar)
        root.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false) // vyskakovaci reklamy neotevirat
            javaScriptCanOpenWindowsAutomatically = false
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url.toString()
                if (u.startsWith("http") && media.containsMatchIn(u) && !junk.containsMatchIn(u)) {
                    val ref = req.requestHeaders["Referer"] ?: pageUrl
                    runOnUiThread { add(u, ref) }
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                // presmerovani na jine aplikace (intent://, market://) ignorovat
                return !req.url.scheme.orEmpty().startsWith("http")
            }

            override fun onPageFinished(view: WebView, url: String) {
                // primy <video src="..."> v strance
                view.evaluateJavascript(
                    "(function(){var r=[];document.querySelectorAll('video,video source').forEach(function(v){var s=v.currentSrc||v.src;if(s&&s.indexOf('http')==0)r.push(s)});return r.join('\\n')})()"
                ) { res ->
                    res?.trim('"')?.split("\\n")?.filter { it.startsWith("http") }?.forEach { add(it, url) }
                }
            }
        }
        web.loadUrl(pageUrl)
    }

    private fun add(url: String, referer: String) {
        if (found.containsKey(url)) return
        found[url] = referer
        btnPlay.visibility = View.VISIBLE
        status.text = if (found.size == 1) "Našel jsem video" else "Našel jsem ${found.size} videa"
        // prvni nalezeny stream (playlist m3u8/mpd) rovnou pustit
        if (found.size == 1 && Regex("\\.(m3u8|mpd)", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
            status.text = "Našel jsem stream – klepni na ▶ Přehrát"
        }
    }

    private fun choose() {
        val list = found.keys.sortedBy { if (Regex("\\.(m3u8|mpd)", RegexOption.IGNORE_CASE).containsMatchIn(it)) 0 else 1 }
        if (list.size == 1) { play(list[0]); return }
        AlertDialog.Builder(this)
            .setTitle("Které video?")
            .setItems(list.map { label(it) }.toTypedArray()) { _, i -> play(list[i]) }
            .show()
    }

    private fun label(u: String): String {
        val uri = Uri.parse(u)
        val name = uri.lastPathSegment ?: u
        return "${uri.host}\n…/${name.take(60)}"
    }

    private fun play(stream: String) {
        val referer = found[stream] ?: pageUrl
        val ref = Uri.parse(referer)
        val title = web.title
        val headers = HashMap<String, String>()
        headers["Referer"] = referer
        headers["Origin"] = "${ref.scheme}://${ref.host}"
        CookieManager.getInstance().getCookie(stream)?.let { headers["Cookie"] = it }
        headers["User-Agent"] = web.settings.userAgentString

        web.onPause()
        web.loadUrl("about:blank") // at stranka nehraje zvuk na pozadi
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .setData(Uri.parse(pageUrl))
                .putExtra(PlayerActivity.EXTRA_STREAM, stream)
                .putExtra(PlayerActivity.EXTRA_HEADERS, headers)
                .putExtra(PlayerActivity.EXTRA_TITLE, title)
        )
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
