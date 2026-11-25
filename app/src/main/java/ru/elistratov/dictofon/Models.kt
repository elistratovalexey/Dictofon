package ru.elistratov.dictofon

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

object ModelManager {
    val progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    fun dir(ctx: Context, id: String): File = File(ctx.filesDir, "models/$id")
    fun vadFile(ctx: Context): File = File(ctx.filesDir, "models/silero_vad.onnx")
    fun isReady(ctx: Context, spec: ModelSpec): Boolean =
        vadFile(ctx).exists() && spec.files.all { File(dir(ctx, spec.id), it).exists() }

    suspend fun download(ctx: Context, spec: ModelSpec) = withContext(Dispatchers.IO) {
        val jobs = mutableListOf<Pair<File, List<String>>>()
        if (!vadFile(ctx).exists()) {
            vadFile(ctx).parentFile.mkdirs()
            jobs.add(
                vadFile(ctx) to listOf(
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
                )
            )
        }
        val d = dir(ctx, spec.id).apply { mkdirs() }
        for (name in spec.files) {
            val f = File(d, name)
            if (!f.exists()) {
                jobs.add(
                    f to listOf(
                        "https://huggingface.co/${spec.repo}/resolve/main/$name",
                        "https://hf-mirror.com/${spec.repo}/resolve/main/$name"
                    )
                )
            }
        }
        val total = jobs.size.coerceAtLeast(1)
        jobs.forEachIndexed { i, (file, urls) ->
            var ok = false
            var lastErr: Exception? = null
            for (url in urls) {
                try { fetch(url, file); ok = true; break } catch (e: Exception) { lastErr = e }
            }
            if (!ok) throw lastErr ?: RuntimeException("Не удалось скачать ${file.name}")
            progress.value = progress.value + (spec.id to (i + 1f) / total)
        }
        progress.value = progress.value - spec.id
    }

    private fun fetch(url: String, dest: File) {
        val part = File(dest.parentFile, dest.name + ".part")
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) throw RuntimeException("HTTP ${conn.responseCode} для $url")
            conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it, 1 shl 16) } }
            if (!part.renameTo(dest)) throw RuntimeException("Не удалось сохранить ${dest.name}")
        } finally {
            conn.disconnect()
        }
    }
}
