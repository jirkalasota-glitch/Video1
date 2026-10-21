package cz.jirka.prehravac

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.jdtech.mpv.MPVLib

fun fmtTime(sec: Double): String {
    if (sec.isNaN() || sec < 0) return "0:00"
    val s = sec.toLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
}

fun fmtSize(bytes: Long): String =
    if (bytes >= 1L shl 30) "%.1f GB".format(bytes / 1073741824.0)
    else "%.0f MB".format(bytes / 1048576.0)

fun Context.displayName(uri: Uri): String {
    try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) return it.getString(0)
        }
    } catch (_: Exception) {
    }
    return uri.lastPathSegment ?: "video"
}

fun Context.fileSize(uri: Uri): Long {
    try {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) return it.getLong(0)
        }
    } catch (_: Exception) {
    }
    return -1
}

/** Pamatovani pozice, kde se video naposledy zastavilo. */
object Positions {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pozice", Context.MODE_PRIVATE)
    fun get(ctx: Context, uri: Uri): Float = prefs(ctx).getFloat(uri.toString(), 0f)
    fun set(ctx: Context, uri: Uri, sec: Float) = prefs(ctx).edit().putFloat(uri.toString(), sec).apply()
    fun clear(ctx: Context, uri: Uri) = prefs(ctx).edit().remove(uri.toString()).apply()
}

/** Obal kolem mpv jadra. */
object Mpv {
    private var created = false

    fun init(ctx: Context) {
        if (created) return
        MPVLib.create(ctx.applicationContext)
        MPVLib.setOptionString("config", "no")
        MPVLib.setOptionString("vo", "gpu")
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")
        // hardwarove dekodovani kde to jde, jinak softwarove (FFmpeg) - proto prehraje vsechno
        MPVLib.setOptionString("hwdec", "mediacodec-copy")
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        MPVLib.setOptionString("ao", "audiotrack,opensles")
        MPVLib.setOptionString("input-default-bindings", "no")
        MPVLib.setOptionString("keep-open", "yes")
        MPVLib.setOptionString("hr-seek", "yes")
        MPVLib.setOptionString("demuxer-max-bytes", "64MiB")
        // odolnost proti poskozenym souborum: zahodit rozbite pakety a pokracovat
        MPVLib.setOptionString("demuxer-lavf-o", "fflags=+discardcorrupt")
        MPVLib.setOptionString("vd-lavc-check-hw-profile", "no")
        // titulky - systemove fonty
        MPVLib.setOptionString("sub-fonts-dir", "/system/fonts")
        MPVLib.setOptionString("sub-font", "Roboto")
        MPVLib.setOptionString("osd-font", "Roboto")
        MPVLib.setOptionString("sub-auto", "fuzzy")
        MPVLib.setOptionString("slang", "cs,cze,ces,sk,slo,en,eng")
        MPVLib.setOptionString("alang", "cs,cze,ces,sk,slo,en,eng")
        MPVLib.init()
        MPVLib.setOptionString("force-window", "no")
        MPVLib.setOptionString("idle", "once")
        created = true
    }

    fun destroy() {
        if (!created) return
        MPVLib.destroy()
        created = false
    }
}
