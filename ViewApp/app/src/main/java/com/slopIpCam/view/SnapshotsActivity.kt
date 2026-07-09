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
    private lateinit var viewer: FrameLayout
    private lateinit var viewerImage: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_snapshots)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val ctrlUrl = prefs.getString("ctrl_server_url", "ws://100.x.x.x:8080/ws") ?: ""
        val httpBase = ctrlUrl
            .replace("ws://", "http://")
            .replace("wss://", "https://")
            .substringBefore("/ws")
            .trimEnd('/')

        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipeRefresh)
        val grid = findViewById<GridView>(R.id.gridSnapshots)
        viewer = findViewById(R.id.viewer)
        viewerImage = findViewById(R.id.viewerImage)

        swipe.setColorSchemeColors(getColor(R.color.neon))
        swipe.setProgressBackgroundColorSchemeColor(getColor(R.color.surface))

        grid.setOnItemClickListener { _, _, pos, _ -> openViewer(urls[pos]) }
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

        swipe.setOnRefreshListener { loadSnapshots(grid, httpBase) { swipe.isRefreshing = false } }
        loadSnapshots(grid, httpBase) {}
    }

    private fun openViewer(url: String) {
        viewer.visibility = View.VISIBLE
        Glide.with(this).load(url).into(viewerImage)
    }

    private fun closeViewer() {
        Glide.with(this).clear(viewerImage)
        viewer.visibility = View.GONE
    }

    private fun loadSnapshots(grid: GridView, base: String, onDone: () -> Unit) {
        scope.launch {
            urls = withContext(Dispatchers.IO) {
                try {
                    val req = Request.Builder().url("$base/snapshots").build()
                    val body = client.newCall(req).execute().body?.string() ?: "[]"
                    val arr = JSONArray(body)
                    (0 until arr.length()).map { "$base${arr.getString(it)}" }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            grid.adapter = SnapshotAdapter(this@SnapshotsActivity, urls)
            findViewById<TextView>(R.id.tvCount).text = urls.size.toString()
            onDone()
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

class SnapshotAdapter(
    ctx: android.content.Context,
    private val urls: List<String>
) : BaseAdapter() {
    private val context = ctx

    override fun getCount() = urls.size
    override fun getItem(pos: Int) = urls[pos]
    override fun getItemId(pos: Int) = pos.toLong()

    override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
        val img = (convertView as? ImageView)
            ?: LayoutInflater.from(context)
                .inflate(R.layout.item_snapshot, parent, false) as ImageView
        Glide.with(context).load(urls[pos]).into(img)
        return img
    }
}
