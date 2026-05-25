package com.slopIpCam.view

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
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

        swipe.setOnRefreshListener { loadSnapshots(grid, httpBase) { swipe.isRefreshing = false } }
        loadSnapshots(grid, httpBase) {}
    }

    private fun loadSnapshots(grid: GridView, base: String, onDone: () -> Unit) {
        scope.launch {
            val urls = withContext(Dispatchers.IO) {
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
        val img = (convertView as? ImageView) ?: ImageView(context).apply {
            layoutParams = AbsListView.LayoutParams(300, 300)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        Glide.with(context).load(urls[pos]).into(img)
        return img
    }
}
