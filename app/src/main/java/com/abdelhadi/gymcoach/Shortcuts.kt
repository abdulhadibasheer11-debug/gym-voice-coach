package com.abdelhadi.gymcoach

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews

private fun startIntent(ctx: Context): Intent =
    if (Session.running) Intent(ctx, WorkoutActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    else Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_START_NOW)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

private fun todayName(ctx: Context): String = runCatching {
    val p = Prefs(ctx); val days = p.days(); val id = p.dayId ?: p.suggestedDayId()
    (days.firstOrNull { it.id == id } ?: days[0]).ar
}.getOrDefault("")

/** Home-screen widget: today's day and one big Start button. */
class StartWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = refresh(ctx, mgr, ids)

    companion object {
        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, StartWidget::class.java))
            if (ids.isNotEmpty()) refresh(ctx, mgr, ids)
        }

        fun refresh(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val day = todayName(ctx)
            val pi = PendingIntent.getActivity(ctx, 11,
                Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_START_NOW)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val v = RemoteViews(ctx.packageName, R.layout.widget_start).apply {
                setTextViewText(R.id.widget_day, day)
                setContentDescription(R.id.widget_root, "ابدأ حصة اليوم: $day")
                setOnClickPendingIntent(R.id.widget_root, pi)
            }
            ids.forEach { mgr.updateAppWidget(it, v) }
        }
    }
}

/** Quick-settings tile: start the session (or open it if it is already running). */
class StartTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = if (Session.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = if (Session.running) "الحصة جارية" else "ابدأ الحصة"
            if (Build.VERSION.SDK_INT >= 29) subtitle = todayName(this@StartTile)
            contentDescription = if (Session.running) "الحصة جارية، افتح شاشة الحصة" else "ابدأ حصة اليوم: ${todayName(this@StartTile)}"
            updateTile()
        }
    }

    override fun onClick() {
        val i = startIntent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 12, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }
}
