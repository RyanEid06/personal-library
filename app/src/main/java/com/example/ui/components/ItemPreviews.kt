package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.ItemEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

private data class PdfPagePreview(val pixels: Bitmap?, val number: Int = 0, val count: Int = 0)

@Composable
fun ItemPdfPreview(item: ItemEntity, blobFile: File?) {
    if (blobFile == null || !blobFile.exists()) return
    val mime = (item.mimeType ?: "").lowercase()
    val isPdf = mime == "application/pdf" || (item.originalFileName ?: "").endsWith(".pdf", ignoreCase = true)
    if (!isPdf) return

    var pageNumber by rememberSaveable(item.id) { mutableIntStateOf(0) }
    val preview by produceState<PdfPagePreview?>(null, item.id, blobFile, pageNumber) {
        value = null
        value = withContext(Dispatchers.IO) {
            try {
                ParcelFileDescriptor.open(blobFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { document ->
                        if (document.pageCount <= 0) return@withContext PdfPagePreview(null)
                        val number = pageNumber.coerceIn(0, document.pageCount - 1)
                        document.openPage(number).use { page ->
                            val scale = 1200.0 / max(page.width, page.height).coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(
                                max(1, (page.width * scale).roundToInt()),
                                max(1, (page.height * scale).roundToInt()),
                                Bitmap.Config.ARGB_8888
                            )
                            bitmap.eraseColor(Color.WHITE)
                            try {
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            } catch (e: Exception) {
                                bitmap.recycle()
                                throw e
                            }
                            PdfPagePreview(bitmap, number, document.pageCount)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PdfPagePreview(null)
            }
        }
    }

    val result = preview
    val bitmap = result?.pixels
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when {
            result == null -> {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.height(6.dp))
                Text("Rendering PDF page…", style = MaterialTheme.typography.bodySmall)
            }
            bitmap == null -> {
                Text(
                    "PDF preview unavailable. Use Open to view original.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            else -> {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "PDF page ${result.number + 1}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Page ${result.number + 1} of ${result.count}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        enabled = result.number > 0,
                        onClick = { pageNumber = result.number - 1 },
                        modifier = Modifier.semantics { contentDescription = "Previous PDF page" }
                    ) {
                        Text("Previous")
                    }
                    TextButton(
                        enabled = result.number + 1 < result.count,
                        onClick = { pageNumber = result.number + 1 },
                        modifier = Modifier.semantics { contentDescription = "Next PDF page" }
                    ) {
                        Text("Next")
                    }
                }
            }
        }
    }
}

@Composable
fun ItemAudioPreview(item: ItemEntity, blobFile: File?) {
    if (blobFile == null || !blobFile.exists()) return
    if (item.kind != "AUDIO") return

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(blobFile.absolutePath) { MediaPlayer() }
    var prepared by remember(player) { mutableStateOf(false) }
    var playing by remember(player) { mutableStateOf(false) }
    var failed by remember(player) { mutableStateOf(false) }

    DisposableEffect(player, lifecycle) {
        try {
            player.setOnPreparedListener { prepared = true }
            player.setOnCompletionListener { playing = false }
            player.setOnErrorListener { _, _, _ ->
                failed = true
                playing = false
                prepared = false
                true
            }
            player.setDataSource(blobFile.absolutePath)
            player.prepareAsync()
        } catch (_: Exception) {
            failed = true
        }

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && playing) {
                runCatching { player.pause() }
                playing = false
            }
        }
        lifecycle.addObserver(observer)

        onDispose {
            lifecycle.removeObserver(observer)
            player.release()
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            when {
                failed -> {
                    Text(
                        "Audio preview unavailable. Use Open to play original.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                !prepared -> {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Preparing audio preview…", style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    Text(
                        if (playing) "Playing audio voice note" else "Voice note / audio clip",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    IconButton(
                        onClick = {
                            try {
                                if (playing) player.pause() else player.start()
                                playing = !playing
                            } catch (_: Exception) {
                                failed = true
                                playing = false
                            }
                        }
                    ) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ItemTextPreview(item: ItemEntity, blobFile: File?) {
    if (blobFile == null || !blobFile.exists()) return

    val textPreview by produceState<String?>(null, blobFile.absolutePath) {
        value = withContext(Dispatchers.IO) {
            try {
                val buffer = CharArray(10000)
                blobFile.bufferedReader().use { reader ->
                    val read = reader.read(buffer)
                    if (read > 0) String(buffer, 0, read) else ""
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    if (!textPreview.isNullOrBlank()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "File Content Preview",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = textPreview!!,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 8
                )
            }
        }
    }
}

