package com.abdelhadi.gymcoach

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Foreground service that runs the whole session, so voice guidance continues with the screen off
 * and the phone in a pocket. Headset buttons: play/pause = pause/resume, next = skip, previous = repeat.
 */
class WorkoutService : Service() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_PAUSE = "pause"
        const val ACTION_REPEAT = "repeat"
        const val ACTION_MORE_REST = "more_rest"
        const val ACTION_SKIP = "skip"
        const val ACTION_SKIP_EXERCISE = "skip_exercise"
        const val ACTION_STOP = "stop"
        const val EXTRA_DAY = "day"
        private const val CHANNEL = "workout"
        private const val NOTIF_ID = 7

        fun send(ctx: Context, action: String) {
            ctx.startService(Intent(ctx, WorkoutService::class.java).setAction(action))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var speaker: Speaker? = null
    private var music: Music? = null
    private val controls = Controls()
    private var wakeLock: PowerManager.WakeLock? = null
    private var media: MediaSession? = null
    private var lastStopPress = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.getStringExtra(EXTRA_DAY))
            ACTION_PAUSE -> togglePause()
            ACTION_REPEAT -> repeatLast()
            ACTION_MORE_REST -> moreRest()
            ACTION_SKIP -> { controls.skip = true; speaker?.stop() }
            ACTION_SKIP_EXERCISE -> {
                controls.skipExercise = true; speaker?.stop()
                scope.launch { speaker?.say("تخطي إلى التمرين التالي.", record = false) }
            }
            ACTION_STOP -> stopPressed()
        }
        return START_NOT_STICKY
    }

    private fun start(dayId: String?) {
        if (job != null) return
        startForegroundCompat(buildNotification("جاري التجهيز"))
        val prefs = Prefs(this)
        val days = prefs.days()
        val day = days.firstOrNull { it.id == dayId } ?: days[0]
        val settings = prefs.settingsFor(day)
        val plan = buildPlan(day, settings)
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gymcoach:session").apply { acquire(3 * 60 * 60 * 1000L) }
        setupMediaSession()
        Session.running = true; Session.paused = false; Session.status = ""; Session.touch()

        job = scope.launch {
            val sp = Speaker(this@WorkoutService)
            speaker = sp
            if (!sp.init()) {
                Session.status = "تعذر تشغيل محرك النطق في الهاتف."
                Session.touch(); finish(); return@launch
            }
            sp.rate = prefs.speechRate
            sp.useRecorded = prefs.useRecorded
            sp.onText = { Session.status = it; Session.touch() }
            val m = Music(this@WorkoutService, this, prefs.musicMode, prefs.musicFolder, prefs.musicVolume)
            music = m
            sp.onSpeech = { m.duck(it) }
            m.start()
            if (!sp.arabicAvailable) sp.say("Arabic voice is not installed. Please install Arabic in Google text to speech settings.")
            // keep notification text in sync
            val notifJob = launch {
                var seen = -1L
                while (true) {
                    if (Session.version != seen) { seen = Session.version; updateNotification(Session.progress.ifBlank { Session.status }) }
                    delay(1000)
                }
            }
            val engine = Engine(this, sp, controls, prefs)
            val result = engine.run(plan, settings)
            notifJob.cancel()

            val totalEx = plan.blocks.sumOf { it.ex.size }
            val completed = result.exercisesDone >= (totalEx + 1) / 2
            if (completed) { prefs.lastDay = day.id; prefs.bumpVariant(day.id) }
            prefs.dayId = prefs.suggestedDayId()
            prefs.addLog(JSONObject().apply {
                put("day", day.id); put("date", System.currentTimeMillis())
                put("planned", plan.totalSec); put("actual", result.elapsedSec)
                put("stopped", result.stopped); put("exercises", result.exercisesDone)
            })
            val diffMin = (result.elapsedSec - plan.totalSec) / 60
            val minutes = (result.elapsedSec + 30) / 60
            if (result.stopped) {
                sp.say("تم إنهاء الحصة. المدة $minutes دقيقة.", interrupt = true)
            } else {
                sp.say("انتهت الحصة!", "Great workout!",
                    "المدة $minutes دقيقة${if (diffMin > 1) "، متأخرين $diffMin دقائق عن الخطة" else "، في الوقت المحدد"}.",
                    "لا تنسَ تشرب ماء.")
            }
            finish()
        }
    }

    private fun finish() {
        Session.running = false; Session.paused = false; Session.touch()
        speaker?.shutdown(); speaker = null
        music?.stop(); music = null
        media?.release(); media = null
        runCatching { wakeLock?.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun togglePause() {
        if (!Session.running) return
        controls.paused = !controls.paused
        Session.paused = controls.paused; Session.touch()
        updateMediaState()
        speaker?.stop()
        music?.pause(controls.paused)
        scope.launch {
            speaker?.say(if (controls.paused) "متوقف مؤقتاً. اضغط استئناف عندما تكون جاهز." else "نكمل.", record = false)
        }
    }

    private fun repeatLast() {
        val sp = speaker ?: return
        val last = sp.lastSpoken
        if (last.isNotEmpty()) scope.launch { sp.say(last, record = false, interrupt = true) }
    }

    private fun moreRest() {
        controls.addMs.set(30_000)
        scope.launch { speaker?.say("أضفت ثلاثين ثانية.", record = false) }
        // adaptive: remember that this exercise needs more rest next time
        val id = Session.restId
        if (id != null) {
            val prefs = Prefs(this)
            prefs.restAdj = prefs.restAdj + (id to minOf(45, (prefs.restAdj[id] ?: 0) + 10))
        }
    }

    private fun stopPressed() {
        val now = System.currentTimeMillis()
        if (now - lastStopPress < 4000) {
            controls.stop = true; speaker?.stop()
        } else {
            lastStopPress = now
            scope.launch { speaker?.say("لإنهاء الحصة اضغط زر الإنهاء مرة ثانية خلال أربع ثواني.", record = false) }
        }
    }

    // ---------- headset buttons ----------
    private fun setupMediaSession() {
        runCatching {
            media = MediaSession(this, "GymCoach").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() = togglePause()
                    override fun onPause() = togglePause()
                    override fun onSkipToNext() { controls.skip = true; speaker?.stop() }
                    override fun onSkipToPrevious() = repeatLast()
                })
                isActive = true
            }
            updateMediaState()
        }
    }

    private fun updateMediaState() {
        media?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(if (controls.paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING, 0, 1f)
                .build()
        )
    }

    // ---------- notification ----------
    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else startForeground(NOTIF_ID, n)
    }

    private fun pi(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, WorkoutService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "الحصة الجارية", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, WorkoutActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, if (controls.paused) "استئناف" else "إيقاف مؤقت", pi(ACTION_PAUSE, 1)).build())
            .addAction(Notification.Action.Builder(null, "تخطي", pi(ACTION_SKIP, 2)).build())
            .addAction(Notification.Action.Builder(null, "إنهاء", pi(ACTION_STOP, 3)).build())
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        controls.stop = true
        scope.cancel()
        speaker?.shutdown()
        music?.stop()
        Session.running = false; Session.touch()
        super.onDestroy()
    }
}
