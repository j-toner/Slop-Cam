package com.slopIpCam.view

import android.content.ContentValues
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.preference.PreferenceManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bumptech.glide.Glide
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SnapshotsActivity : AppCompatActivity() {
    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var urls: List<String> = emptyList()
    private var showingClips = false
    private val selected = linkedSetOf<String>()
    private lateinit var httpBase: String
    private lateinit var grid: GridView
    private lateinit var viewer: FrameLayout
    private lateinit var viewerImage: ImageView
    private lateinit var viewerVideo: PlayerView
    private var player: ExoPlayer? = null
    private var currentPos = -1
    private lateinit var gestureDetector: GestureDetector
    private val swipeThreshold = 80
    private val swipeVelocity = 120

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_snapshots)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val ctrlUrl = prefs.getString("ctrl_server_url", "ws://100.x.x.x:8080/ws") ?: ""
        httpBase = ctrlUrl
            .replace("ws://", "http://")
            .replace("wss://", "https://")
            .substringBefore("/ws")
            .trimEnd('/')

        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipeRefresh)
        grid = findViewById(R.id.gridSnapshots)
        viewer = findViewById(R.id.viewer)
        viewerImage = findViewById(R.id.viewerImage)
        viewerVideo = findViewById(R.id.viewerVideo)
        // controls auto-hide after a short beat and reappear on tap, so they
        // don't sit over the video; the center play overlay is dropped too
        viewerVideo.controllerShowTimeoutMs = 2500
        viewerVideo.controllerHideOnTouch = true

        swipe.setColorSchemeColors(getColor(R.color.neon))
        swipe.setProgressBackgroundColorSchemeColor(getColor(R.color.surface))

        findViewById<TextView>(R.id.tabSnaps).setOnClickListener { switchTab(false) }
        findViewById<TextView>(R.id.tabClips).setOnClickListener { switchTab(true) }

        grid.setOnItemClickListener { _, _, pos, _ ->
            when {
                selected.isNotEmpty() -> toggleSelection(urls[pos])
                else -> openItem(pos)
            }
        }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            toggleSelection(urls[pos])
            true
        }
        findViewById<TextView>(R.id.btnDelete).setOnClickListener { deleteSelected() }
        findViewById<TextView>(R.id.btnCloseViewer).setOnClickListener { closeViewer() }
        findViewById<TextView>(R.id.btnDownload).setOnClickListener { downloadClip() }

        // swipe left/right in the fullscreen viewer to page through items
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, v1: Float, v2: Float
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (kotlin.math.abs(dx) > kotlin.math.abs(dy) &&
                    kotlin.math.abs(dx) > swipeThreshold &&
                    kotlin.math.abs(v1) > swipeVelocity
                ) {
                    if (dx < 0) openItem(currentPos + 1) // swipe left -> next
                    else openItem(currentPos - 1)        // swipe right -> prev
                    return true
                }
                return false
            }
        })
        viewer.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }

        // back closes the fullscreen viewer, then selection mode, then leaves
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    viewer.visibility == View.VISIBLE -> closeViewer()
                    selected.isNotEmpty() -> clearSelection()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        swipe.setOnRefreshListener { loadItems { swipe.isRefreshing = false } }
        loadItems {}
    }

    private fun switchTab(clips: Boolean) {
        if (clips == showingClips) return
        showingClips = clips
        clearSelection()
        val on = getColor(R.color.neon)
        val off = getColor(R.color.text_dim)
        findViewById<TextView>(R.id.tabSnaps).setTextColor(if (clips) off else on)
        findViewById<TextView>(R.id.tabClips).setTextColor(if (clips) on else off)
        urls = emptyList()
        grid.adapter = SnapshotAdapter(this, urls, showingClips, selected)
        loadItems {}
    }

    private fun toggleSelection(url: String) {
        if (!selected.remove(url)) selected.add(url)
        val btn = findViewById<TextView>(R.id.btnDelete)
        btn.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        btn.text = "🗑 ${selected.size}"
        (grid.adapter as? SnapshotAdapter)?.notifyDataSetChanged()
    }

    private fun clearSelection() {
        selected.clear()
        findViewById<TextView>(R.id.btnDelete).visibility = View.GONE
        (grid.adapter as? SnapshotAdapter)?.notifyDataSetChanged()
    }

    private fun deleteSelected() {
        val doomed = selected.toList()
        scope.launch {
            val failures = withContext(Dispatchers.IO) {
                doomed.count { url ->
                    try {
                        !client.newCall(Request.Builder().url(url).delete().build())
                            .execute().use { it.isSuccessful }
                    } catch (e: Exception) {
                        true
                    }
                }
            }
            if (failures > 0) {
                Toast.makeText(this@SnapshotsActivity,
                    "$failures delete(s) failed", Toast.LENGTH_SHORT).show()
            }
            clearSelection()
            loadItems {}
        }
    }

    private fun openItem(pos: Int) {
        if (pos < 0 || pos >= urls.size) return
        currentPos = pos
        val url = urls[pos]
        if (showingClips) openClip(url) else openImage(url)
    }

    private fun openImage(url: String) {
        viewerVideo.visibility = View.GONE
        viewerImage.visibility = View.VISIBLE
        viewer.visibility = View.VISIBLE
        findViewById<View>(R.id.btnDownload).visibility = View.GONE
        Glide.with(this).load(url).into(viewerImage)
    }

    private fun openClip(url: String) {
        viewerImage.visibility = View.GONE
        viewerVideo.visibility = View.VISIBLE
        viewer.visibility = View.VISIBLE
        findViewById<View>(R.id.btnDownload).visibility = View.VISIBLE
        val p = player ?: ExoPlayer.Builder(this).build().also {
            player = it
            viewerVideo.player = it
            it.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Toast.makeText(this@SnapshotsActivity,
                        "Playback failed: ${error.errorCodeName}", Toast.LENGTH_SHORT).show()
                    closeViewer()
                }
            })
        }
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.play()
    }

    private fun closeViewer() {
        player?.pause()
        player?.clearMediaItems()
        Glide.with(this).clear(viewerImage)
        viewer.visibility = View.GONE
        findViewById<View>(R.id.btnDownload).visibility = View.GONE
    }

    /**
     * Stream the currently-open clip to the shared Downloads folder via
     * MediaStore (no extra permission needed on Android 10+).
     */
    private fun downloadClip() {
        if (currentPos < 0 || currentPos >= urls.size) return
        val url = urls[currentPos]
        val name = url.substringAfterLast('/').ifEmpty { "clip.mp4" }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val resp = client.newCall(Request.Builder().url(url).build()).execute()
                    if (!resp.isSuccessful) return@withContext false
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                    }
                    val uri = contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                    ) ?: return@withContext false
                    contentResolver.openOutputStream(uri).use { out ->
                        resp.body?.byteStream()?.copyTo(out!!)
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            Toast.makeText(this@SnapshotsActivity,
                if (ok) "Saved to Downloads" else "Download failed",
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadItems(onDone: () -> Unit) {
        val endpoint = if (showingClips) "/recordings" else "/snapshots"
        val forClips = showingClips
        scope.launch {
            // null = fetch failed; distinct from an empty gallery so "server
            // unreachable" doesn't render as "no files"
            val fetched: List<String>? = withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder().url("$httpBase$endpoint").build()
                    val body = client.newCall(req).execute().body?.string() ?: "[]"
                    val arr = JSONArray(body)
                    (0 until arr.length()).map { "$httpBase${arr.getString(it)}" }
                } catch (e: Exception) {
                    null
                }
            }
            if (forClips == showingClips) { // tab may have switched mid-fetch
                if (fetched == null) {
                    findViewById<TextView>(R.id.tvCount).text = "offline"
                    Toast.makeText(this@SnapshotsActivity,
                        "Server unreachable", Toast.LENGTH_SHORT).show()
                } else {
                    urls = fetched
                    grid.adapter = SnapshotAdapter(this@SnapshotsActivity, urls, showingClips, selected)
                    findViewById<TextView>(R.id.tvCount).text = urls.size.toString()
                }
            }
            onDone()
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        scope.cancel()
        super.onDestroy()
    }
}

class SnapshotAdapter(
    ctx: android.content.Context,
    private val urls: List<String>,
    private val clips: Boolean = false,
    private val selected: Set<String> = emptySet()
) : BaseAdapter() {
    private val context = ctx

    override fun getCount() = urls.size
    override fun getItem(pos: Int) = urls[pos]
    override fun getItemId(pos: Int) = pos.toLong()

    override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
        val view: View
        if (clips) {
            view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_clip, parent, false)
            view.findViewById<TextView>(R.id.tvClipDate).text = clipLabel(urls[pos])
            // the server writes "<video>.jpg" next to each finished segment;
            // in-progress ones just show the dark cell + play glyph
            Glide.with(context).load(urls[pos] + ".jpg")
                .into(view.findViewById(R.id.imgClipThumb))
        } else {
            view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_snapshot, parent, false)
            Glide.with(context).load(urls[pos])
                .into(view.findViewById(R.id.imgThumb))
            view.findViewById<TextView>(R.id.tvSnapDate).text = snapLabel(urls[pos])
        }
        view.alpha = if (urls[pos] in selected) 0.35f else 1f
        return view
    }

    private val stamp = Regex("""(\d{4}-\d{2}-\d{2})_(\d{2})-(\d{2})-(\d{2})""")

    // "cam_2026-07-09_17-30-01.mp4" -> "2026-07-09\n17:30"
    private fun clipLabel(url: String): String {
        val name = url.substringAfterLast('/')
        val m = stamp.find(name) ?: return name.removeSuffix(".mp4")
        return "${m.groupValues[1]}\n${m.groupValues[2]}:${m.groupValues[3]}"
    }

    // "snap_2026-07-09_17-30-01.jpg" -> "2026-07-09  17:30"
    private fun snapLabel(url: String): String {
        val name = url.substringAfterLast('/')
        val m = stamp.find(name) ?: return name.substringBeforeLast('.')
        return "${m.groupValues[1]}  ${m.groupValues[2]}:${m.groupValues[3]}"
    }
}
