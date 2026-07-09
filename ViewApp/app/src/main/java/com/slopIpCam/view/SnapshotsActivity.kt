package com.slopIpCam.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bumptech.glide.Glide
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

class SnapshotsActivity : AppCompatActivity() {
    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var urls: List<String> = emptyList()
    private var showingClips = false
    private lateinit var httpBase: String
    private lateinit var grid: GridView
    private lateinit var viewer: FrameLayout
    private lateinit var viewerImage: ImageView
    private lateinit var viewerVideo: VideoView

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
        viewerVideo.setMediaController(MediaController(this).apply {
            setAnchorView(viewerVideo)
        })

        swipe.setColorSchemeColors(getColor(R.color.neon))
        swipe.setProgressBackgroundColorSchemeColor(getColor(R.color.surface))

        findViewById<TextView>(R.id.tabSnaps).setOnClickListener { switchTab(false) }
        findViewById<TextView>(R.id.tabClips).setOnClickListener { switchTab(true) }

        grid.setOnItemClickListener { _, _, pos, _ ->
            if (showingClips) openClip(urls[pos]) else openImage(urls[pos])
        }
        findViewById<TextView>(R.id.btnCloseViewer).setOnClickListener { closeViewer() }

        // back closes the fullscreen viewer first, then the activity
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewer.visibility == View.VISIBLE) closeViewer()
                else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        swipe.setOnRefreshListener { loadItems { swipe.isRefreshing = false } }
        loadItems {}
    }

    private fun switchTab(clips: Boolean) {
        if (clips == showingClips) return
        showingClips = clips
        val on = getColor(R.color.neon)
        val off = getColor(R.color.text_dim)
        findViewById<TextView>(R.id.tabSnaps).setTextColor(if (clips) off else on)
        findViewById<TextView>(R.id.tabClips).setTextColor(if (clips) on else off)
        urls = emptyList()
        grid.adapter = SnapshotAdapter(this, urls, showingClips)
        loadItems {}
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
        viewerVideo.setVideoURI(android.net.Uri.parse(url))
        viewerVideo.setOnPreparedListener { it.start() }
        viewerVideo.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "Playback failed", Toast.LENGTH_SHORT).show()
            closeViewer()
            true
        }
    }

    private fun closeViewer() {
        viewerVideo.stopPlayback()
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
                grid.adapter = SnapshotAdapter(this@SnapshotsActivity, urls, showingClips)
                findViewById<TextView>(R.id.tvCount).text = urls.size.toString()
            }
            onDone()
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

class SnapshotAdapter(
    ctx: android.content.Context,
    private val urls: List<String>,
    private val clips: Boolean = false
) : BaseAdapter() {
    private val context = ctx

    override fun getCount() = urls.size
    override fun getItem(pos: Int) = urls[pos]
    override fun getItemId(pos: Int) = pos.toLong()

    override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
        if (clips) {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_clip, parent, false)
            view.findViewById<TextView>(R.id.tvClipDate).text = clipLabel(urls[pos])
            return view
        }
        val img = (convertView as? ImageView)
            ?: LayoutInflater.from(context)
                .inflate(R.layout.item_snapshot, parent, false) as ImageView
        Glide.with(context).load(urls[pos]).into(img)
        return img
    }

    // ".../cam_2026-07-09_06-25-30.mp4" -> "2026-07-09\n06:25"
    private fun clipLabel(url: String): String {
        val name = url.substringAfterLast('/')
            .removePrefix("cam_").removeSuffix(".mp4")
        val parts = name.split("_")
        if (parts.size != 2) return name
        val time = parts[1].split("-")
        return parts[0] + "\n" + if (time.size == 3) "${time[0]}:${time[1]}" else parts[1]
    }
}
