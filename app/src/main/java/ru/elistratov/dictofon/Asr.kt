package ru.elistratov.dictofon

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

data class ModelSpec(
    val id: String, val title: String, val langs: String, val license: String,
    val sizeMb: Int, val repo: String, val files: List<String>, val kind: String, val note: String,
)

object Catalog {
    val models = listOf(
        ModelSpec(
            "gigaam-v3", "GigaAM v3 — русский, с пунктуацией", "ru", "MIT", 232,
            "csukuangfj/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16",
            listOf("encoder.int8.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"),
            "transducer", "Лучшее качество для русского; текст сразу с пунктуацией и заглавными"
        ),
        ModelSpec(
            "parakeet-v3", "Parakeet v3 — 25 языков", "ru/en/de/…", "CC-BY-4.0", 670,
            "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
            listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
            "transducer", "Очень быстрый; сам определяет язык; пунктуация"
        ),
        ModelSpec(
            "whisper-small", "Whisper small — 99 языков", "99 языков", "MIT", 375,
            "csukuangfj/sherpa-onnx-whisper-small",
            listOf("small-encoder.int8.onnx", "small-decoder.int8.onnx", "small-tokens.txt"),
            "whisper", "Компактный многоязычный; язык определяется автоматически"
        ),
        ModelSpec(
            "whisper-turbo", "Whisper large-v3-turbo — 99 языков", "99 языков", "MIT", 1060,
            "csukuangfj/sherpa-onnx-whisper-turbo",
            listOf("turbo-encoder.int8.onnx", "turbo-decoder.int8.onnx", "turbo-tokens.txt"),
            "whisper", "Максимальное качество; ~1 ГБ, для мощных телефонов"
        ),
    )
    fun byId(id: String): ModelSpec = models.firstOrNull { it.id == id } ?: models[0]
}

data class Seg(val start: Float, val end: Float, val text: String)

private fun recognizerConfig(dir: File, spec: ModelSpec): OfflineRecognizerConfig {
    val mc = OfflineModelConfig(numThreads = 2, provider = "cpu")
    if (spec.kind == "whisper") {
        mc.whisper = OfflineWhisperModelConfig(
            encoder = File(dir, spec.files[0]).absolutePath,
            decoder = File(dir, spec.files[1]).absolutePath,
            language = "", task = "transcribe"
        )
        mc.tokens = File(dir, spec.files[2]).absolutePath
        mc.modelType = "whisper"
    } else {
        mc.transducer = OfflineTransducerModelConfig(
            encoder = File(dir, spec.files[0]).absolutePath,
            decoder = File(dir, spec.files[1]).absolutePath,
            joiner = File(dir, spec.files[2]).absolutePath
        )
        mc.tokens = File(dir, spec.files[3]).absolutePath
        mc.modelType = "nemo_transducer"
    }
    return OfflineRecognizerConfig(modelConfig = mc, decodingMethod = "greedy_search")
}

fun transcribe(ctx: Context, wav: File, spec: ModelSpec): List<Seg> {
    val samples = readWav(wav)
    val vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = ModelManager.vadFile(ctx).absolutePath,
                threshold = 0.5f, minSilenceDuration = 0.3f, minSpeechDuration = 0.25f,
                windowSize = 512, maxSpeechDuration = 15f
            ),
            sampleRate = 16000, numThreads = 1
        )
    )
    val rec = OfflineRecognizer(config = recognizerConfig(ModelManager.dir(ctx, spec.id), spec))
    val out = mutableListOf<Seg>()
    try {
        vad.acceptWaveform(samples)
        vad.flush()
        while (!vad.empty()) {
            val s = vad.front()
            vad.pop()
            val stream = rec.createStream()
            stream.acceptWaveform(s.samples, 16000)
            rec.decode(stream)
            val text = rec.getResult(stream).text.replace('\u2581', ' ').trim()
            stream.release()
            if (text.isNotEmpty()) {
                val start = s.start.toFloat() / 16000f
                out.add(Seg(start, start + s.samples.size.toFloat() / 16000f, text))
            }
        }
    } finally {
        rec.release()
        vad.release()
    }
    return out
}

fun readWav(f: File): FloatArray {
    RandomAccessFile(f, "r").use { raf ->
        val n = ((raf.length() - 44) / 2).toInt().coerceAtLeast(0)
        raf.seek(44)
        val buf = ByteArray(n * 2)
        raf.readFully(buf)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val lo = buf[2 * i].toInt() and 0xff
            val hi = buf[2 * i + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort() / 32768f
        }
        return out
    }
}

class WavWriter(private val raf: RandomAccessFile, private val sampleRate: Int = 16000) {
    private var dataBytes = 0L
    init { raf.setLength(0); raf.write(ByteArray(44)) }
    fun write(buf: ShortArray, n: Int) {
        val b = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = buf[i].toInt()
            b[2 * i] = (v and 0xff).toByte()
            b[2 * i + 1] = ((v shr 8) and 0xff).toByte()
        }
        raf.write(b)
        dataBytes += b.size
    }
    fun close() {
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt((36 + dataBytes).toInt()); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(sampleRate); bb.putInt(sampleRate * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataBytes.toInt())
        raf.seek(0); raf.write(bb.array()); raf.close()
    }
}

private fun ts(sec: Float): String {
    val ms = (sec * 1000).toLong().coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
}

fun writeTxt(segs: List<Seg>, f: File) {
    val sb = StringBuilder()
    var prevEnd = -1f
    for (s in segs) {
        if (prevEnd >= 0 && s.start - prevEnd > 1.5f) sb.append("\n\n")
        else if (sb.isNotEmpty()) sb.append(' ')
        sb.append(s.text)
        prevEnd = s.end
    }
    f.writeText(sb.toString() + "\n")
}

fun writeSrt(segs: List<Seg>, f: File) {
    val sb = StringBuilder()
    segs.forEachIndexed { i, s ->
        sb.append(i + 1).append('\n').append(ts(s.start)).append(" --> ").append(ts(s.end))
            .append('\n').append(s.text).append("\n\n")
    }
    f.writeText(sb.toString())
}

data class Rec(val wav: File, val txt: File?, val srt: File?, val title: String)

object Library {
    fun dir(ctx: Context): File = File(ctx.filesDir, "recordings").apply { mkdirs() }
    fun list(ctx: Context): List<Rec> =
        dir(ctx).listFiles { f -> f.extension == "wav" }?.sortedByDescending { it.name }?.map { w ->
            val b = w.nameWithoutExtension
            Rec(
                w,
                File(w.parentFile, "$b.txt").takeIf { it.exists() },
                File(w.parentFile, "$b.srt").takeIf { it.exists() },
                b
            )
        } ?: emptyList()
    fun delete(rec: Rec) { rec.wav.delete(); rec.txt?.delete(); rec.srt?.delete() }
}

object Transcriber {
    val busy = MutableStateFlow(false)
    val status = MutableStateFlow("")
    val revision = MutableStateFlow(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    fun submit(ctx: Context, wav: File, modelId: String) {
        if (busy.value) return
        val app = ctx.applicationContext
        scope.launch {
            busy.value = true
            try {
                val spec = Catalog.byId(modelId)
                status.value = "Расшифровка ${wav.name}…"
                val segs = transcribe(app, wav, spec)
                writeTxt(segs, File(wav.parentFile, wav.nameWithoutExtension + ".txt"))
                writeSrt(segs, File(wav.parentFile, wav.nameWithoutExtension + ".srt"))
                status.value = if (segs.isEmpty()) "Речь не найдена" else "Готово: ${segs.size} сегм."
            } catch (e: Exception) {
                status.value = "Ошибка: ${e.message}"
            } finally {
                busy.value = false
                revision.value++
            }
        }
    }
}
