package com.slopIpCam.view

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * In-app update via the ctrl-server file drop: the latest staged debug APK
 * is served at /snapshots/viewapp.apk (stage a build on the server with
 * `cp <apk> /data/snapshots/viewapp.apk`). "Newer" is the file's
 * Last-Modified vs this install's lastUpdateTime — staging is what bumps
 * it, so no version bookkeeping is needed. The download goes through
 * DownloadManager and the system installer confirms the actual install.
 */
object Updater {
    private const val APK_PATH = "/snapshots/viewapp.apk"
    private const val APK_MIME = "application/vnd.android.package-archive"

    fun check(ctx: Context, httpBase: String) {
        val app = ctx.applicationContext
        if (httpBase.isBlank()) {
            Toast.makeText(app, "Set the ctrl-server URL first", Toast.LENGTH_LONG).show()
            return
        }
        val url = "$httpBase$APK_PATH"
        CoroutineScope(Dispatchers.Main).launch {
            val remote = withContext(Dispatchers.IO) { remoteLastModified(url) }
            val installed = app.packageManager
                .getPackageInfo(app.packageName, 0).lastUpdateTime
            when {
                remote == null ->
                    Toast.makeText(app, "Update check failed — server unreachable?",
                        Toast.LENGTH_LONG).show()
                !isRemoteNewer(remote, installed) ->
                    Toast.makeText(app, "Already up to date", Toast.LENGTH_SHORT).show()
                else -> {
                    Toast.makeText(app, "Downloading update…", Toast.LENGTH_SHORT).show()
                    download(app, url)
                }
            }
        }
    }

    // strictly newer: staging the same build again re-offers it, which is
    // fine — the installer just reinstalls
    fun isRemoteNewer(remoteMs: Long, installedMs: Long) = remoteMs > installedMs

    private fun remoteLastModified(url: String): Long? = try {
        OkHttpClient().newCall(Request.Builder().url(url).head().build())
            .execute().use { resp ->
                if (!resp.isSuccessful) null
                else resp.headers.getDate("Last-Modified")?.time
            }
    } catch (e: Exception) {
        null
    }

    private fun download(app: Context, url: String) {
        // DownloadManager won't overwrite; clear the previous update file
        File(app.getExternalFilesDir(null), "viewapp.apk").delete()
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(url))
                .setMimeType(APK_MIME)
                .setTitle("SlopCam viewer update")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(app, null, "viewapp.apk")
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                app.unregisterReceiver(this)
                val uri = dm.getUriForDownloadedFile(id)
                if (uri == null) {
                    Toast.makeText(app, "Update download failed", Toast.LENGTH_LONG).show()
                    return
                }
                app.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, APK_MIME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }
        }
        // ACTION_DOWNLOAD_COMPLETE is a system broadcast: API 33+ requires
        // an explicit exported flag on dynamic receivers
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }
}
