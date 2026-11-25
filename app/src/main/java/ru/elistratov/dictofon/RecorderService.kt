package ru.elistratov.dictofon

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

class RecorderService : Service() {
    companion object {
        const val ACTION_START = "ru.elistratov.dictofon.START"
        const val ACTION_STOP = "ru.elistratov.dictofon.STOP"
        val recording = MutableStateFlow(false)
        val level = MutableStateFlow(0f)
        val seconds = MutableStateFlow(0)
        val lastFinished = MutableStateFlow<String?>(null)

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, RecorderService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> recording.value = false
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (recording.value) return
        val channel = NotificationChannel("rec", "Запись", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        ServiceCompat.startForeground(
            this, 1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dictofon:rec").apply {
            acquire(4 * 60 * 60 * 1000L)
        }
        val out = File(
            Library.dir(this),
            "REC_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav"
        )
        recording.value = true
        seconds.value = 0
        lastFinished.value = null
        job = scope.launch {
            var rec: AudioRecord? = null
            var wav: WavWriter? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                rec = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4
                )
                wav = WavWriter(RandomAccessFile(out, "rw"))
                rec.startRecording()
                val buf = ShortArray(1600)
                val t0 = SystemClock.elapsedRealtime()
                while (recording.value) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        wav.write(buf, n)
                        var sum = 0.0
                        for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                        level.value = (sqrt(sum / n) / 32768.0).toFloat()
                        seconds.value = ((SystemClock.elapsedRealtime() - t0) / 1000).toInt()
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { rec?.stop() } catch (_: Exception) {}
                rec?.release()
                wav?.close()
                recording.value = false
                level.value = 0f
                lastFinished.value = out.absolutePath
                if (wakeLock?.isHeld == true) wakeLock?.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, "rec")
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Dictofon — идёт запись")
        .setContentText("Нажмите «Стоп», чтобы завершить и расшифровать")
        .setOngoing(true)
        .addAction(
            android.R.drawable.ic_media_pause, "Стоп",
            PendingIntent.getService(
                this, 0,
                Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .setContentIntent(
            PendingIntent.getActivity(
                this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    override fun onDestroy() {
        recording.value = false
        job?.cancel()
        super.onDestroy()
    }
}
