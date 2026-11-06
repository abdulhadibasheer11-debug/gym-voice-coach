# مدرّب الجيم الصوتي — Gym Voice Coach

تطبيق أندرويد يوجّهك صوتياً داخل الجيم من البداية للنهاية: يعدّ التكرارات، ويحسب الراحة بين الجولات حسب نوع التمرين، ويدير وقت الحصة حتى تخرج في الوقت المحدد. مصمم للاستخدام الكامل مع قارئ الشاشة TalkBack وبدون لمس الهاتف بعد الضغط على "ابدأ".

## التثبيت
نزّل آخر إصدار من هذا الرابط وافتح الملف على هاتف الأندرويد:

https://github.com/abdulhadibasheer11-debug/gym-voice-coach/releases/latest/download/gym-voice-coach.apk

في أول مرة سيطلب أندرويد السماح بتثبيت التطبيقات من المتصفح أو مدير الملفات.

## التحديثات
عند فتح التطبيق يفحص صفحة الإصدارات هنا. إذا وجد إصداراً أحدث يعرض رسالة "يوجد تحديث جديد" وينزّله ويفتح شاشة التثبيت.
كل تعديل يُرفع إلى الفرع main يبني نسخة APK جديدة تلقائياً وينشرها كإصدار جديد.

## Structure
- `Plan.kt` — exercises, 5-day split, warm-up/stretch, timing rules, step builder
- `Engine.kt` — session runner: voice flow, rep counting, rest, time management
- `Speaker.kt` — mixed Arabic/English TTS, audio focus (ducks music, does not fight TalkBack), beeps
- `WorkoutService.kt` — foreground service (works with screen off), headset buttons, notification
- `MainActivity.kt` / `WorkoutActivity.kt` — accessible setup and control screens
- `Updater.kt` — self-update from GitHub Releases

Rest and tempo rationale: Singer et al. 2024 (rest > 60 s helps, little difference past 90 s); Schoenfeld et al. 2015 (0.5–8 s per rep give similar hypertrophy).
