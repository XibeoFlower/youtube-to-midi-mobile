package com.xibeoflower.yttomidi

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.xibeoflower.yttomidi.core.ExtractionSettings
import com.xibeoflower.yttomidi.core.MidiWriter
import com.xibeoflower.yttomidi.core.NoteEvent
import com.xibeoflower.yttomidi.core.SynthesiaExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScreen()
                }
            }
        }
    }
}

private enum class Stage { PICK, READY, RUNNING, DONE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var videoLabel by remember { mutableStateOf("") }
    var videoDurationSec by remember { mutableStateOf(0.0) }
    var videoW by remember { mutableStateOf(0) }
    var videoH by remember { mutableStateOf(0) }

    var keyY by remember { mutableStateOf(500f) }
    var skipSeconds by remember { mutableStateOf(5f) }
    var analysisFps by remember { mutableStateOf(20f) }
    var tempoBpm by remember { mutableStateOf(120f) }

    var stage by remember { mutableStateOf(Stage.PICK) }
    var progress by remember { mutableStateOf(0f) }
    var statusText by remember { mutableStateOf("") }
    var resultFile by remember { mutableStateOf<File?>(null) }
    var noteCount by remember { mutableStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                videoUri = uri
                videoLabel = uri.lastPathSegment ?: "video"
                videoDurationSec = durMs / 1000.0
                videoW = w
                videoH = h
                stage = Stage.READY
                statusText = ""
                resultFile = null
            } finally {
                retriever.release()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.MusicNote, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("YouTube → MIDI", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Trích xuất MIDI từ video hướng dẫn đàn piano kiểu Synthesia",
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Button(
                    onClick = { pickVideo.launch(arrayOf("video/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = stage != Stage.RUNNING
                ) {
                    Icon(Icons.Filled.VideoFile, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (videoUri == null) "Chọn video" else "Đổi video khác")
                }
                if (videoUri != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(videoLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "%dx%d · %.0f giây".format(videoW, videoH, videoDurationSec),
                        fontSize = 12.sp
                    )
                }
            }
        }

        if (videoUri != null) {
            Spacer(Modifier.height(12.dp))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Tuỳ chỉnh", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))

                    LabeledSlider(
                        label = "Vị trí dòng phím (Y) = ${keyY.toInt()}",
                        value = keyY, range = 0f..1080f, enabled = stage != Stage.RUNNING
                    ) { keyY = it }

                    LabeledSlider(
                        label = "Bỏ qua đầu video = %.1f giây".format(skipSeconds),
                        value = skipSeconds, range = 0f..30f, enabled = stage != Stage.RUNNING
                    ) { skipSeconds = it }

                    LabeledSlider(
                        label = "Tốc độ lấy mẫu = ${analysisFps.toInt()} khung/giây (cao hơn = chính xác hơn nhưng chậm hơn)",
                        value = analysisFps, range = 5f..60f, enabled = stage != Stage.RUNNING
                    ) { analysisFps = it }

                    LabeledSlider(
                        label = "Tempo xuất MIDI = ${tempoBpm.toInt()} BPM",
                        value = tempoBpm, range = 40f..240f, enabled = stage != Stage.RUNNING
                    ) { tempoBpm = it }

                    Text(
                        "Mặc định căn theo video 1276x720. Ứng dụng tự co giãn theo độ phân giải video của bạn, " +
                            "nhưng nếu kết quả sai nhiều, hãy chỉnh lại vị trí dòng phím (Y) cho khớp hàng phím sáng.",
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    if (stage == Stage.RUNNING) {
                        job?.cancel()
                        stage = Stage.READY
                        statusText = "Đã huỷ"
                        return@Button
                    }
                    val uri = videoUri ?: return@Button
                    stage = Stage.RUNNING
                    progress = 0f
                    statusText = "Đang xử lý..."
                    job = scope.launch {
                        runExtraction(
                            contextResolverUri = uri,
                            context = context,
                            videoW = videoW, videoH = videoH, durationSec = videoDurationSec,
                            settings = ExtractionSettings(
                                keyY = keyY.toInt(),
                                skipSeconds = skipSeconds.toDouble(),
                                analysisFps = analysisFps.toDouble(),
                                tempoBpm = tempoBpm.toInt()
                            ),
                            onProgress = { p -> progress = p },
                            onDone = { file, notes ->
                                resultFile = file
                                noteCount = notes.size
                                stage = Stage.DONE
                                statusText = "Hoàn tất: ${notes.size} nốt nhạc"
                            },
                            onError = { msg ->
                                stage = Stage.READY
                                statusText = "Lỗi: $msg"
                            }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (stage == Stage.RUNNING) "Huỷ" else "Bắt đầu trích xuất")
            }

            if (stage == Stage.RUNNING) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text("${(progress * 100).toInt()}%  ·  $statusText", fontSize = 12.sp)
            }

            if (stage == Stage.DONE && resultFile != null) {
                Spacer(Modifier.height(12.dp))
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("✅ $statusText", fontWeight = FontWeight.Bold)
                        Text(resultFile!!.name, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val uri = FileProvider.getUriForFile(
                                    context, "${context.packageName}.fileprovider", resultFile!!
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "audio/midi"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Lưu / Chia sẻ file MIDI"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Chia sẻ / Lưu file .mid")
                        }
                    }
                }
            } else if (statusText.isNotEmpty() && stage != Stage.RUNNING) {
                Spacer(Modifier.height(8.dp))
                Text(statusText, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, fontSize = 13.sp)
        Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled)
    }
}

private suspend fun runExtraction(
    contextResolverUri: Uri,
    context: android.content.Context,
    videoW: Int,
    videoH: Int,
    durationSec: Double,
    settings: ExtractionSettings,
    onProgress: (Float) -> Unit,
    onDone: (File, List<NoteEvent>) -> Unit,
    onError: (String) -> Unit
) {
    withContext(Dispatchers.Default) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, contextResolverUri)
            val extractor = SynthesiaExtractor(retriever, videoW, videoH, durationSec, settings)
            val notes = extractor.extract { frac ->
                onProgress(frac)
                isActive
            }
            if (notes.isEmpty()) {
                withContext(Dispatchers.Main) { onError("Không phát hiện được nốt nào. Thử chỉnh lại vị trí Y hoặc thời gian bỏ qua.") }
                return@withContext
            }
            val dir = File(context.getExternalFilesDir(null), "midi").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outFile = File(dir, "extracted_$stamp.mid")
            MidiWriter(bpm = settings.tempoBpm).write(notes, outFile)
            withContext(Dispatchers.Main) { onDone(outFile, notes) }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onError(e.message ?: e.toString()) }
        } finally {
            retriever.release()
        }
    }
}
