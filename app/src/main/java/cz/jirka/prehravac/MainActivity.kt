package cz.jirka.prehravac

import android.Manifest
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    data class Video(val uri: Uri, val name: String, val durationMs: Long, val size: Long)

    private val videos = mutableListOf<Video>()
    private val exec = Executors.newFixedThreadPool(2)
    private val thumbs = LruCache<String, Bitmap>(100)
    private lateinit var adapter: VideoAdapter
    private lateinit var empty: TextView
    private lateinit var btnPerm: Button

    private val perm =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) load() else showNoPermission()
        }

    private val openLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                }
                play(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        empty = findViewById(R.id.empty)
        btnPerm = findViewById(R.id.btnPerm)
        adapter = VideoAdapter()

        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> play(videos[pos].uri) }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val v = videos[pos]
            AlertDialog.Builder(this)
                .setTitle(v.name)
                .setItems(arrayOf("Přehrát", "Přehrát od začátku", "Diagnostika a oprava")) { _, which ->
                    when (which) {
                        0 -> play(v.uri)
                        1 -> { Positions.clear(this, v.uri); play(v.uri) }
                        2 -> repair(v.uri)
                    }
                }.show()
            true
        }

        findViewById<Button>(R.id.btnOpen).setOnClickListener { openLauncher.launch(arrayOf("*/*")) }
        btnPerm.setOnClickListener { permLauncher.launch(perm) }

        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) load()
        else permLauncher.launch(perm)
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    private fun showNoPermission() {
        empty.text = "Bez povolení nevidím videa v telefonu. Můžeš je ale otevřít přes „Otevřít soubor“."
        empty.visibility = View.VISIBLE
        btnPerm.visibility = View.VISIBLE
    }

    private fun load() {
        btnPerm.visibility = View.GONE
        exec.execute {
            val found = mutableListOf<Video>()
            val proj = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE
            )
            try {
                contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                    MediaStore.Video.Media.DATE_ADDED + " DESC"
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        found += Video(
                            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                            c.getString(1) ?: "video",
                            if (c.isNull(2)) 0 else c.getLong(2),
                            if (c.isNull(3)) 0 else c.getLong(3)
                        )
                    }
                }
            } catch (_: Exception) {
            }
            runOnUiThread {
                videos.clear()
                videos.addAll(found)
                adapter.notifyDataSetChanged()
                if (found.isEmpty()) {
                    empty.text = "V telefonu jsem nenašel žádná videa. Zkus „Otevřít soubor“."
                    empty.visibility = View.VISIBLE
                } else empty.visibility = View.GONE
            }
        }
    }

    private fun play(uri: Uri) {
        startActivity(
            Intent(this, PlayerActivity::class.java).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun repair(uri: Uri) {
        startActivity(
            Intent(this, RepairActivity::class.java).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    inner class VideoAdapter : BaseAdapter() {
        override fun getCount() = videos.size
        override fun getItem(position: Int) = videos[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_video, parent, false)
            val v = videos[position]
            view.findViewById<TextView>(R.id.name).text = v.name

            val parts = mutableListOf<String>()
            if (v.durationMs > 0) parts += fmtTime(v.durationMs / 1000.0)
            if (v.size > 0) parts += fmtSize(v.size)
            val saved = Positions.get(this@MainActivity, v.uri)
            if (saved > 0) parts += "▶ pokračovat od ${fmtTime(saved.toDouble())}"
            view.findViewById<TextView>(R.id.info).text = parts.joinToString("  •  ")

            val img = view.findViewById<ImageView>(R.id.thumb)
            val key = v.uri.toString()
            img.tag = key
            val cached = thumbs.get(key)
            if (cached != null) img.setImageBitmap(cached)
            else {
                img.setImageDrawable(null)
                exec.execute {
                    val bmp = try {
                        contentResolver.loadThumbnail(v.uri, Size(320, 180), null)
                    } catch (_: Exception) {
                        null
                    }
                    if (bmp != null) {
                        thumbs.put(key, bmp)
                        runOnUiThread { if (img.tag == key) img.setImageBitmap(bmp) }
                    }
                }
            }
            return view
        }
    }
}
