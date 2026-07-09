package com.slopIpCam.view

import android.os.Bundle
import android.view.LayoutInflater
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

        swipe.setColorSchemeColors(getColor(R.color.neon))
        swipe.setProgressBackgroundColorSchemeColor(getColor(R.color.surface))

        findViewById<TextView>(R.id.tabSnaps).setOnClickListener { switchTab(false) }
        findViewById<TextView>(R.id.tabClips).setOnClickListener { switchTab(true) }

        grid.setOnItemClickListener { _, _, pos, _ ->
            when {
                selected.isNotEmpty() -> toggleSelection(urls[pos])
                showingClips -> openClip(urls[pos])
                else -> openImage(urls[pos])
            }
        }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            toggleSelection(urls[pos])
            true
        }
        findViewById<TextView>(R.id.btnDelete).setOnClickListener { deleteSelected() }
        findViewById<TextView>(R.id.btnCloseViewer).setOnClickListener { closeViewer() }

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

    private fun openImage(url: String) {
        viewerVideo.visibility = View.GONE
        viewerImage.visibility = View.VISIBLE
        viewer.visibility = View.VISIBLE
        Glide.with(this).load(url).into(viewerImage)
    }

    private fun openClip(url: String) {
        viewerImage.visibility = View.GONE
        viewerVideo.visibility = View.VISIBLE
        viewer.visibility = View.VISIBLE
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
    }

    private fun loadItems(onDone: () -> Unit) {
        val endpoint = if (showingClips) "/recordings" else "/snapshots"
        val forClips = showingClips
        scope.launch {
            val fetched = withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder().url("$httpBase$endpoint").build()
                    val body = client.newCall(req).execute().body?.string() ?: "[]"
                    val arr = JSONArray(body)
                    (0 until arr.length()).map { "$httpBase${arr.getString(it)}" }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            if (forClips == showingClips) { // tab may have switched mid-fetch
                urls = fetched
                grid.adapter = SnapshotAdapter(this@SnapshotsActivity, urls, showingClips, selected)
                findViewById<TextView>(R.id.tvCount).text = urls.size.toString()
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
        } else {
            val img = (convertView as? ImageView)
                ?: LayoutInflater.from(context)
                    .inflate(R.layout.item_snapshot, parent, false) as ImageView
            Glide.with(context).load(urls[pos]).into(img)
            view = img
        }
        view.alpha = if (urls[pos] in selected) 0.35f else 1f
        return view
    }

    private val stamp = Regex("""(\d{4}-\d{2}-\d{2})_(\d{2})-(\d{2})-(\d{2})""")

    // "clip_motion_2026-07-09_17-30-01.mp4" -> "motion 2026-07-09\n17:30"
    private fun clipLabel(url: String): String {
        val name = url.substringAfterLast('/')
        val m = stamp.find(name) ?: return name.removeSuffix(".mp4")
        val kind = when {
            name.startsWith("clip_motion") -> "motion "
            name.startsWith("clip_manual") -> "manual "
            else -> ""
        }
        return "$kind${m.groupValues[1]}\n${m.groupValues[2]}:${m.groupValues[3]}"
    }
}
