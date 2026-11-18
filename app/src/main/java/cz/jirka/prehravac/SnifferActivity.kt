package cz.jirka.prehravac

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Kdyz yt-dlp web nezna: stranka se nacte NEVIDITELNE na pozadi, appka v ni sama
 * klepne na prehrat, odchyti adresu videa (m3u8, mp4 ...) a rovnou ho pusti v prehravaci.
 * Stranku uvidi uzivatel jen kdyz se video do 25 s nenajde a sam si o ni rekne.
 */
class SnifferActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var cover: View
    private lateinit var status: TextView
    private lateinit var buttons: LinearLayout
    private lateinit var pageUrl: String
    private var pageHost = ""
    private var firstLoadDone = false
    private var done = false
    private val handler = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, String>() // url -> referer
    private val durations = HashMap<String, Double>()   // url -> delka videa (kdyz ji zname)

    /** Reklamni a sledovaci servery - blokujeme jako adblock, at se reklama pred videem vubec nenacte. */
    private val adHosts = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.",
        "imasdk.googleapis.com", "pagead2.", "googletagservices.com", "g.doubleclick",
        "adnxs.com", "rubiconproject.com", "pubmatic.com", "openx.net", "criteo.", "casalemedia.com",
        "smartadserver.com", "teads.tv", "spotxchange.com", "spotx.tv", "springserve.com", "adform.net",
        "taboola.com", "outbrain.com", "amazon-adsystem.com", "adsrvr.org", "yieldmo.com", "3lift.com",
        "exoclick.com", "exosrv.com", "popads.net", "popcash.net", "propellerads.com", "adsterra.com",
        "onclickads.net", "juicyads.com", "trafficjunky.", "hilltopads.", "a-ads.com", "adcash.com",
        "r2b2.cz", "r2b2.io", "caroda.io", "ssp.seznam.cz", "im.seznam.cz/ads", "sklik.cz", "performax.cz",
        "etarget.", "imedia.cz", "adocean.", "gemius.", "aimatch.com", "ad.cpex.cz", "vidoomy.com",
        "connatix.com", "primis.tech", "aniview.com", "vlitag.com", "jwpltx.com", "mgid.com", "revcontent.com"
    )
    private val emptyResponse get() = WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))

    /** Seznam (Novinky, Stream.cz, Seznam Zpravy): video se popisuje JSON playlistem "spl2,..." na *.sdn.szn.cz */
    private val seznamSpl = Regex("sdn\\.(szn\\.)?cz/.*spl2,", RegexOption.IGNORE_CASE)
    private var splResolving = false

    private fun resolveSeznam(splUrl: String, referer: String) {
        if (splResolving || done) return
        splResolving = true
        runOnUiThread { status.text = "Našel jsem video, spouštím…"; cover.visibility = View.VISIBLE }
        Thread {
            val stream = try { Seznam.resolve(splUrl, referer) } catch (_: Throwable) { null }
            runOnUiThread {
                splResolving = false
                if (stream != null && !done) {
                    found[stream] = referer
                    play(stream)
                }
            }
        }.start()
    }

    private fun isAd(host: String, url: String) = adHosts.any { host.contains(it) || url.contains(it) }

    private val media = Regex(
        "\\.(m3u8|mpd|mp4|m4v|webm|mkv|mov|flv)(\\?|#|$)|/manifest(\\.|/|\\?)|mime=video",
        RegexOption.IGNORE_CASE
    )
    private val playlist = Regex("\\.(m3u8|mpd)|/manifest", RegexOption.IGNORE_CASE)
    // reklamy a kousky streamu nechceme
    private val junk = Regex(
        "doubleclick|googlesyndication|googleads|adservice|/ads?/|vast|preroll|\\.m4s|/seg[-_]?\\d|/chunk|init\\.mp4|thumb|preview|sprite|\\.ts(\\?|$)",
        RegexOption.IGNORE_CASE
    )

    /** Bezi v kazdem ramu stranky (i v iframe s prehravacem): pusti videa a klika na tlacitka play. */
    private val autoPlayJs = """
        (function(){
          if (window.__vp) return; window.__vp = 1;
          var sel = ['.vjs-big-play-button','.jw-icon-display','.jw-display-icon-container','.plyr__control--overlaid',
            '.fp-play','.mejs__overlay-button','.play-button','.playButton','.btn-play','.play-btn','#play',
            '[class*="big-play"]','[class*="play-icon"]','[aria-label*="play" i]','[title*="play" i]','[aria-label*="přehrát" i]'];
          var n = 0;
          function report(){
            document.querySelectorAll('video, video source').forEach(function(v){
              var s = v.currentSrc || v.src;
              if (s && s.indexOf('http') === 0) { try { VP.found(s, location.href, (isFinite(v.duration) ? v.duration : 0) || 0); } catch(e){} }
            });
          }
          var consent = /^(souhlasím|souhlasím a pokračovat|přijmout vše|přijmout všechny|přijmout|povolit vše|rozumím|accept all|accept|i agree|agree|allow all|ok)$/i;
          var playTxt = /^(přehrát|přehrát video|spustit|spustit video|play|play video|▶)$/i;
          function deep(root, fn){
            try {
              root.querySelectorAll('*').forEach(function(e){
                fn(e);
                if (e.shadowRoot) deep(e.shadowRoot, fn);
              });
            } catch(e){}
          }
          function clickText(){
            deep(document, function(e){
              if (e.__vpc) return;
              var tag = e.tagName;
              if (tag !== 'BUTTON' && tag !== 'A' && e.getAttribute('role') !== 'button') return;
              var t = (e.innerText || e.textContent || '').trim();
              if (t.length > 40) return;
              if (consent.test(t) || playTxt.test(t)) { e.__vpc = 1; try { e.click(); } catch(x){} }
            });
          }
          function tick(){
            n++;
            clickText();
            document.querySelectorAll('video').forEach(function(v){
              try { v.muted = true; if (v.paused) { var p = v.play(); if (p && p.catch) p.catch(function(){}); } } catch(e){}
            });
            sel.forEach(function(s){
              try { document.querySelectorAll(s).forEach(function(e){
                if (!e.__vpc && e.offsetParent !== null) { e.__vpc = 1; e.click(); }
              }); } catch(e){}
            });
            report();
            if (n < 20) setTimeout(tick, 1200);
          }
          if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', function(){ setTimeout(tick, 800); });
          else setTimeout(tick, 800);
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pageUrl = intent.dataString ?: run { finish(); return }
        pageHost = Uri.parse(pageUrl).host.orEmpty()
        buildUi()

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false) // vyskakovaci reklamy neotevirat
            javaScriptCanOpenWindowsAutomatically = false
        }
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun found(src: String, frame: String, duration: Double) = runOnUiThread {
                if (duration > 0) durations[src] = duration
                add(src, frame)
            }
        }, "VP")
        // skript do vsech ramu (i do iframe s prehravacem z jineho webu)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, autoPlayJs, setOf("*"))
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url.toString()
                if (isAd(req.url.host.orEmpty(), u)) return emptyResponse
                if (seznamSpl.containsMatchIn(u)) {
                    resolveSeznam(u, req.requestHeaders["Referer"] ?: pageUrl)
                    return null
                }
                if (u.startsWith("http") && media.containsMatchIn(u) && !junk.containsMatchIn(u)) {
                    val ref = req.requestHeaders["Referer"] ?: pageUrl
                    runOnUiThread { add(u, ref) }
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (!u.scheme.orEmpty().startsWith("http")) return true // intent://, market:// ...
                // po nacteni stranky uz nepustit hlavni okno na reklamu na jinem webu
                if (firstLoadDone && req.isForMainFrame && !sameSite(u.host.orEmpty())) return true
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                firstLoadDone = true
                if (url.startsWith("http")) pageHost = Uri.parse(url).host.orEmpty()
                // zaloha pro stare WebView bez DOCUMENT_START_SCRIPT (aspon hlavni ram)
                view.evaluateJavascript(autoPlayJs, null)
            }
        }
        web.loadUrl(pageUrl)
        handler.postDelayed(timeout, 25_000)
    }

    private fun sameSite(host: String): Boolean {
        fun base(h: String) = h.split('.').takeLast(2).joinToString(".")
        // povolit i navrat na puvodni web (napr. po souhlasu s cookies na cmp.seznam.cz)
        return base(host) == base(pageHost) || base(host) == base(Uri.parse(pageUrl).host.orEmpty())
    }

    private fun buildUi() {
        val d = resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))

        // cerna obrazovka pres stranku - stranku neni videt
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            isClickable = true
            setPadding((24 * d).toInt(), 0, (24 * d).toInt(), 0)
        }
        box.addView(ProgressBar(this))
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            text = "Hledám video…"
            setPadding(0, (16 * d).toInt(), 0, (16 * d).toInt())
        }
        box.addView(status)
        buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        buttons.addView(Button(this, null, 0, R.style.Btn).apply {
            text = "Ukázat stránku"
            setOnClickListener { showPage() }
        })
        buttons.addView(Button(this, null, 0, R.style.Btn).apply {
            text = "Zavřít"
            setOnClickListener { finish() }
        })
        box.addView(buttons)
        cover = box
        root.addView(box, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private val timeout = Runnable {
        if (done) return@Runnable
        status.text = "Video jsem na stránce nenašel.\n\nMůžeš si stránku zobrazit a klepnout na přehrát sám – pak ho hned převezmu."
        buttons.visibility = View.VISIBLE
    }

    private fun showPage() {
        cover.visibility = View.GONE
    }

    private var pickScheduled = false

    private fun add(url: String, referer: String) {
        if (done || found.containsKey(url) || isAd(Uri.parse(url).host.orEmpty(), url)) return
        found[url] = referer
        status.text = "Našel jsem video, spouštím…"
        cover.visibility = View.VISIBLE
        if (playlist.containsMatchIn(url)) { pick(); return } // stream = to hlavni video
        // u mp4 chvili pockat - prvni byva reklama, hlavni video prijde po ni
        if (!pickScheduled) {
            pickScheduled = true
            handler.postDelayed({ pick() }, 3000)
        }
    }

    private fun pick() {
        if (done || found.isEmpty() || splResolving) return
        // stream (m3u8/mpd) > nejdelsi video (reklama byva kratka) > posledni nalezene
        val best = found.keys.firstOrNull { playlist.containsMatchIn(it) }
            ?: found.keys.maxByOrNull { durations[it] ?: -1.0 }?.takeIf { (durations[it] ?: 0.0) > 0 }
            ?: found.keys.last()
        play(best)
    }

    private fun play(stream: String) {
        done = true
        handler.removeCallbacksAndMessages(null)
        val referer = found[stream] ?: pageUrl
        val ref = Uri.parse(referer)
        val headers = HashMap<String, String>()
        headers["Referer"] = referer
        headers["Origin"] = "${ref.scheme}://${ref.host}"
        CookieManager.getInstance().getCookie(stream)?.let { headers["Cookie"] = it }
        headers["User-Agent"] = web.settings.userAgentString
        val title = web.title?.takeIf { it.isNotBlank() && !it.startsWith("http") }

        web.onPause()
        web.loadUrl("about:blank") // at stranka nehraje na pozadi
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .setData(Uri.parse(pageUrl))
                .putExtra(PlayerActivity.EXTRA_STREAM, stream)
                .putExtra(PlayerActivity.EXTRA_HEADERS, headers)
                .putExtra(PlayerActivity.EXTRA_TITLE, title)
        )
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (cover.visibility == View.GONE && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }
}
