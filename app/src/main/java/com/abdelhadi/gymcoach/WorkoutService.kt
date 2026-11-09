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
        const val ACTION_NEXT_SONG = "next_song"
        const val ACTION_VOICE = "voice"
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
    private var voice: VoiceInput? = null
    private var listening = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var singlePending = false
    private val singlePress = Runnable { singlePending = false; togglePause() }
    private var prefsCache: Prefs? = null

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
            ACTION_NEXT_SONG -> music?.next()
            ACTION_VOICE -> voiceCommand()
        }
        return START_NOT_STICKY
    }

    private fun start(dayId: String?) {
        if (job != null) return
        val prefs = Prefs(this)
        prefsCache = prefs
        val wantsMic = (prefs.voiceWeights || prefs.voiceCommands) &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        startForegroundCompat(buildNotification("جاري التجهيز"), wantsMic)
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
            val haptics = Haptics(this@WorkoutService, prefs.haptics)
            sp.onBeep = { haptics.beep(it) }
            sp.onRep = { haptics.rep() }
            val m = Music(this@WorkoutService, this, prefs.musicMode, prefs.musicFolder, prefs.musicVolume)
            music = m
            sp.onSpeech = { m.duck(it) }
            m.start()
            // music pauses completely while listening, so the recogniser hears the voice
            if (wantsMic) voice = VoiceInput(this@WorkoutService) { on -> listening = on; if (!Session.paused) m.pause(on) }
            if (!sp.arabicAvailable) sp.say("Arabic voice is not installed. Please install Arabic in Google text to speech settings.")
            // keep notification text in sync
            val notifJob = launch {
                var seen = -1L
                while (true) {
                    if (Session.version != seen) { seen = Session.version; updateNotification(Session.progress.ifBlank { Session.status }) }
                    delay(1000)
                }
            }
            val engine = Engine(this, sp, controls, prefs, this@WorkoutService, if (prefs.voiceWeights) voice else null)
            val result = engine.run(plan, settings)
            notifJob.cancel()

            val totalEx = plan.blocks.sumOf { it.ex.size }
            val completed = result.exercisesDone >= (totalEx + 1) / 2
            if (completed) { prefs.lastDay = day.id; prefs.bumpVariant(day.id) }
            prefs.dayId = prefs.suggestedDayId()
            StartWidget.refreshAll(this@WorkoutService)
            prefs.addLog(JSONObject().apply {
                put("day", day.id); put("dayAr", day.ar); put("date", System.currentTimeMillis())
                put("planned", result.plannedSec); put("actual", result.elapsedSec)
                put("stopped", result.stopped); put("exercises", result.exercisesDone)
                put("ids", org.json.JSONArray(result.doneIds))
            })
            val diffMin = (result.elapsedSec - result.plannedSec) / 60
            val minutes = (result.elapsedSec + 30) / 60
            if (result.stopped) {
                sp.say("تم إنهاء الحصة. المدة $minutes دقيقة.", interrupt = true)
            } else {
                val week = History.sessionsThisWeek(History.entries(this@WorkoutService))
                // remind about exercises done today without a weight (only for people who log weights)
                val sessionStart = System.currentTimeMillis() - result.elapsedSec * 1000L
                val logsWeights = prefs.voiceWeights || Weights.all(this@WorkoutService).values.any { h -> h.any { it.date >= sessionStart } }
                val missing = if (logsWeights) result.doneIds.filter { id -> (Weights.history(this@WorkoutService, id).lastOrNull()?.date ?: 0L) < sessionStart } else emptyList()
                sp.say("انتهت الحصة!", "Great workout!",
                    "المدة $minutes دقيقة${if (diffMin > 1) "، متأخرين $diffMin دقائق عن الخطة" else "، في الوقت المحدد"}.",
                    if (week > 0) "هذه حصتك ${History.ordinalAr(week)} هذا الأسبوع." else "",
                    if (missing.isNotEmpty()) "لم يُسجَّل وزن ${History.nAr(missing.size, "تمرين واحد", "تمرينين", "تمارين", "تمريناً")}: ${missing.joinToString("، ") { Catalog[it].nameAr }}. تقدر تسجّلها من شاشة الأوزان." else "",
                    "لا تنسَ تشرب ماء.")
            }
            finish()
        }
    }

    private fun finish() {
        mainHandler.removeCallbacks(singlePress)
        voice = null
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
            Weights.addRestRequest(this, id)
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
                    override fun onPlay() = headsetPlayPause()
                    override fun onPause() = headsetPlayPause()
                    override fun onSkipToNext() { controls.skip = true; speaker?.stop() }
                    override fun onSkipToPrevious() = repeatLast()
                })
                isActive = true
            }
            updateMediaState()
        }
    }

    /** With voice commands on: one press = pause/resume (after a short wait), two quick presses = listen. */
    private fun headsetPlayPause() {
        val vc = prefsCache?.voiceCommands == true && voice != null
        if (!vc) { togglePause(); return }
        if (singlePending) { singlePending = false; mainHandler.removeCallbacks(singlePress); voiceCommand() }
        else { singlePending = true; mainHandler.postDelayed(singlePress, 450) }
    }

    /** Listen for one spoken command while the session holds still. */
    private fun voiceCommand() {
        val v = voice
        val sp = speaker ?: return
        if (!Session.running || listening) return
        if (v == null) {
            scope.launch { sp.say("الأوامر الصوتية غير مفعّلة. فعّلها من الإعدادات واسمح باستخدام الميكروفون.", record = false, interrupt = true) }
            return
        }
        scope.launch {
            val wasPaused = controls.paused
            controls.paused = true // hold counting and timers while listening
            sp.stop()
            sp.say("نعم؟", record = false, interrupt = true)
            sp.beep(Beep.TICK)
            val alts = v.listen(4000)
            controls.paused = wasPaused
            if (alts == null) { sp.say("ما سمعت أمر.", record = false); return@launch }
            when (VoiceInput.command(alts)) {
                VoiceInput.Companion.Cmd.PAUSE -> if (!wasPaused) togglePause() else sp.say("الحصة متوقفة أصلاً.", record = false)
                VoiceInput.Companion.Cmd.RESUME -> if (wasPaused) togglePause() else sp.say("نكمل.", record = false)
                VoiceInput.Companion.Cmd.SKIP -> { controls.skip = true; sp.stop() }
                VoiceInput.Companion.Cmd.SKIP_EXERCISE -> { controls.skipExercise = true; sp.stop(); sp.say("تخطي إلى التمرين التالي.", record = false) }
                VoiceInput.Companion.Cmd.MORE_REST -> moreRest()
                VoiceInput.Companion.Cmd.REPEAT -> repeatLast()
                VoiceInput.Companion.Cmd.TIME -> {
                    val left = ((Session.endMs - System.currentTimeMillis()) / 60000.0).let { kotlin.math.ceil(it).toInt() }.coerceAtLeast(0)
                    val fmt = java.text.SimpleDateFormat("h:mm", java.util.Locale.US).format(java.util.Date(Session.endMs))
                    sp.say("باقي حوالي $left دقيقة، والانتهاء الساعة $fmt.", record = false)
                }
                VoiceInput.Companion.Cmd.WEIGHT -> {
                    val id = Session.exId
                    val kg = VoiceInput.number(alts)?.takeIf { it in 0.5..500.0 }
                    if (id != null && kg != null) { Weights.add(this@WorkoutService, id, kg); sp.say("سجّلت ${Weights.kgAr(kg)} لـ", Catalog[id].nameAr, record = false) }
                    else sp.say("قل مثلاً: الوزن أربعين.", record = false)
                }
                VoiceInput.Companion.Cmd.UNKNOWN -> sp.say("ما فهمت. الأوامر: وقف، كمل، تخطي، التمرين التالي، راحة، كرر، كم باقي، الوزن ورقم.", record = false)
            }
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
    private fun startForegroundCompat(n: Notification, mic: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mic) {
            // the microphone type lets speech recognition work with the screen locked
            val ok = runCatching {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            }.isSuccess
            if (ok) return
        }
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
            .setSmallIcon(R.drawable.ic_stat_coach)
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
