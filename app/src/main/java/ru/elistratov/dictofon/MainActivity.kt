package ru.elistratov.dictofon

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) { App() }
        }
    }
}

private fun shareText(ctx: Context, title: String, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(i, "Поделиться текстом"))
}

private fun shareFile(ctx: Context, f: File, mime: String) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val i = Intent(Intent.ACTION_SEND).setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Поделиться"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("dictofon", Context.MODE_PRIVATE) }
    var modelId by remember { mutableStateOf(prefs.getString("model_id", "gigaam-v3") ?: "gigaam-v3") }
    val spec = Catalog.byId(modelId)
    val recording by RecorderService.recording.collectAsState()
    val level by RecorderService.level.collectAsState()
    val seconds by RecorderService.seconds.collectAsState()
    val lastFinished by RecorderService.lastFinished.collectAsState()
    val busy by Transcriber.busy.collectAsState()
    val status by Transcriber.status.collectAsState()
    val revision by Transcriber.revision.collectAsState()
    val progress by ModelManager.progress.collectAsState()
    var ready by remember { mutableStateOf(ModelManager.isReady(ctx, spec)) }
    var items by remember { mutableStateOf(Library.list(ctx)) }
    var menu by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Rec?>(null) }
    var dlError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) RecorderService.start(ctx)
    }

    fun startRec() {
        val audioOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val notifOk = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        when {
            audioOk && notifOk -> RecorderService.start(ctx)
            audioOk -> perm.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            else -> perm.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    LaunchedEffect(revision) { items = Library.list(ctx) }
    LaunchedEffect(modelId) { ready = ModelManager.isReady(ctx, spec); dlError = null }
    LaunchedEffect(lastFinished) {
        val p = lastFinished
        if (p != null) {
            RecorderService.lastFinished.value = null
            if (ready) Transcriber.submit(ctx, File(p), modelId)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Dictofon 🎙️") }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            Box {
                OutlinedButton(onClick = { menu = true }, enabled = !recording && !busy) {
                    Text(spec.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Catalog.models.forEach { m ->
                        DropdownMenuItem(
                            text = { Text("${m.title} · ${m.sizeMb} МБ · ${m.license}") },
                            onClick = {
                                modelId = m.id
                                prefs.edit().putString("model_id", m.id).apply()
                                menu = false
                            }
                        )
                    }
                }
            }
            Text(spec.note, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))

            val dl = progress[spec.id]
            if (!ready) {
                if (dl != null) {
                    LinearProgressIndicator(progress = { dl }, modifier = Modifier.fillMaxWidth())
                    Text("Скачивание модели… ${(dl * 100).toInt()}%")
                } else {
                    Button(
                        onClick = {
                            dlError = null
                            scope.launch {
                                try {
                                    ModelManager.download(ctx, spec)
                                    ready = true
                                } catch (e: Exception) {
                                    dlError = e.message
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Скачать модель (~${spec.sizeMb} МБ)") }
                    dlError?.let { Text("Ошибка загрузки: $it", color = MaterialTheme.colorScheme.error) }
                }
                Spacer(Modifier.height(8.dp))
            }

            Button(
                onClick = { if (recording) RecorderService.stop(ctx) else startRec() },
                enabled = ready && !busy,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = if (recording) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.buttonColors()
            ) {
                Text(
                    if (recording) "■ Стоп  %02d:%02d".format(seconds / 60, seconds % 60) else "● Запись",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            if (recording) {
                LinearProgressIndicator(
                    progress = { (level * 4f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(8.dp))
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
            } else if (status.isNotEmpty()) {
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Text("Записи", style = MaterialTheme.typography.titleSmall)

            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.wav.absolutePath }) { rec ->
                    Card(onClick = { viewing = rec }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(rec.title, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    rec.txt?.readText()?.trim()?.take(120) ?: "не расшифровано",
                                    style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (rec.txt == null && ready && !busy && !recording) {
                                TextButton(onClick = { Transcriber.submit(ctx, rec.wav, modelId) }) { Text("Расшифровать") }
                            }
                            if (rec.txt != null) {
                                TextButton(onClick = { shareText(ctx, rec.title, rec.txt.readText()) }) { Text("↗") }
                            }
                            TextButton(onClick = { Library.delete(rec); Transcriber.revision.value++ }) { Text("✕") }
                        }
                    }
                }
            }
        }
    }

    viewing?.let { rec ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(rec.title) },
            text = {
                Column {
                    Text(rec.txt?.readText() ?: "Нет расшифровки. Нажмите «Расшифровать» в списке.")
                    Spacer(Modifier.height(8.dp))
                    Row {
                        TextButton(onClick = { shareFile(ctx, rec.wav, "audio/wav") }) { Text("WAV") }
                        rec.srt?.let { s -> TextButton(onClick = { shareFile(ctx, s, "text/plain") }) { Text("SRT") } }
                    }
                }
            },
            confirmButton = {
                if (rec.txt != null) {
                    TextButton(onClick = { shareText(ctx, rec.title, rec.txt.readText()) }) { Text("Поделиться текстом") }
                }
            },
            dismissButton = { TextButton(onClick = { viewing = null }) { Text("Закрыть") } }
        )
    }
}
