package com.example.capture

import android.app.*
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.PersonalLibraryApp
import com.example.data.LibraryRepository
import com.example.indexing.LocalIndexWorker
import com.example.model.SharePayload
import kotlinx.coroutines.*
import java.util.UUID

class CaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var active: Job? = null
    private val store get() = (application as PersonalLibraryApp).captureState
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Saving files", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            if (active != null) active?.cancel() else stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null || active != null) { if (active == null) stopSelf(); return START_NOT_STICKY }
        val decoded = SharePayloadDecoder.decode(intent) ?: run { stopSelf(); return START_NOT_STICKY }
        val payload = decoded.copy(rejectedCount = decoded.rejectedCount + intent.getIntExtra("capture_rejected", 0))
        val batchId = intent.getStringExtra(BATCH_ID)
        if (batchId == null || store.state.value.batchId != batchId) { stopSelf(); return START_NOT_STICKY }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Saving to your library").setContentText("Copying original files to this phone")
            .setContentIntent(open).addAction(0, "Cancel", cancel).setOngoing(true).setProgress(0, 0, true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        try { ServiceCompat.startForeground(this, 1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
        catch (_: Exception) {
            stopSelfResult(startId)
            store.finish("Saving could not start. Keep the originals and try sharing again.", batchId)
            return START_NOT_STICKY
        }
        active = scope.launch {
            var message = "Saving could not finish. Items already committed are kept; share unfinished files again."
            try {
                val repository = LibraryRepository(this@CaptureService)
                val result = CaptureBatchProcessor.android(this@CaptureService, repository).save(payload,
                    intent.getStringArrayListExtra(COLLECTIONS).orEmpty(), intent.getBooleanExtra(STICKER, false)) { saved, failed -> store.progress(saved, failed, batchId) }
                message = if (result.failed == 0) "Saved ${result.saved} item(s) to your library."
                    else "Saved ${result.saved} item(s). ${result.failed} file(s) could not be saved; share them again."
            } catch (cancelled: CancellationException) {
                message = "Saving stopped. Items already committed are kept; share unfinished files again."
            } catch (_: Exception) {
                // Keep the default failure message and committed counts.
            } finally {
                try { LocalIndexWorker.enqueue(this@CaptureService) } finally {
                    withContext(NonCancellable + Dispatchers.Main) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        active = null
                        stopSelf()
                        // Release admission last, after foreground teardown and active-job reset.
                        store.finish(message, batchId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { active?.cancel(); stopSelf() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "capture"
        private const val CANCEL = "com.example.capture.CANCEL"
        private const val BATCH_ID = "capture_batch_id"
        private const val COLLECTIONS = "capture_collections"
        private const val STICKER = "capture_sticker"
        fun start(context: Context, payload: SharePayload, collections: List<String>, sticker: Boolean = false): Boolean {
            if (payload.uris.isEmpty() && payload.text.isNullOrBlank() && payload.rejectedCount == 0) return false
            val store = (context.applicationContext as PersonalLibraryApp).captureState
            val batch = UUID.randomUUID().toString()
            if (!store.begin(batch, payload.uris.distinct().size + (if (payload.text.isNullOrBlank()) 0 else 1) + payload.rejectedCount)) return false
            val intent = Intent(context, CaptureService::class.java).apply {
                action = Intent.ACTION_SEND_MULTIPLE
                type = payload.mimeType
                putExtra(Intent.EXTRA_TEXT, payload.text)
                putExtra(BATCH_ID, batch)
                putExtra(STICKER, sticker)
                putStringArrayListExtra(COLLECTIONS, ArrayList(collections))
                val uris = payload.uris.map(Uri::parse)
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                putExtra("capture_rejected", payload.rejectedCount)
                if (uris.isNotEmpty()) {
                    clipData = ClipData.newRawUri("Shared files", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            return try { ContextCompat.startForegroundService(context, intent); true }
            catch (_: Exception) { store.finish("Saving could not start. Keep the originals and try sharing again.", batch); false }
        }
        fun cancel(context: Context) { context.startService(Intent(context, CaptureService::class.java).setAction(CANCEL)) }
    }
}
