package cz.jirka.prehravac

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Prevod Seznam playlistu (spl2) na adresu, kterou mpv prehraje: HLS stream, jinak nejlepsi MP4. */
object Seznam {

    private fun get(url: String, referer: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("Referer", referer)
        c.setRequestProperty("User-Agent", PlayerActivity.DEFAULT_UA)
        try {
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }

    fun resolve(splUrl: String, referer: String): String? {
        var base = splUrl
        var j = get(base, referer)
        val loc = j.optString("Location")
        if (loc.isNotBlank() && !j.has("data")) {
            base = URL(URL(base), loc).toString()
            j = get(base, referer)
        }
        fun abs(rel: String) = URL(URL(base), rel).toString()

        // HLS = vsechny kvality, mpv si vybere
        j.optJSONObject("pls")?.optJSONObject("hls")?.optString("url")?.takeIf { it.isNotBlank() }?.let { return abs(it) }

        // jinak MP4 s nejvyssim rozlisenim
        val data = j.optJSONObject("data") ?: return null
        val mp4 = data.optJSONObject("mp4") ?: return null
        var best: String? = null
        var bestH = -1
        for (k in mp4.keys()) {
            val f = mp4.optJSONObject(k) ?: continue
            val u = f.optString("url")
            if (u.isBlank()) continue
            val h = f.optJSONArray("resolution")?.optInt(1) ?: k.filter { it.isDigit() }.toIntOrNull() ?: 0
            if (h > bestH) { bestH = h; best = u }
        }
        return best?.let { abs(it) }
    }
}
