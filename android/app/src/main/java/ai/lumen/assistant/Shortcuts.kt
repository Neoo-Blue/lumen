package ai.lumen.assistant

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.widget.RemoteViews

private fun talkIntent(ctx: Context) = Intent(ctx, OverlayActivity::class.java).putExtra(MainActivity.EXTRA_VOICE, true)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** A Quick Settings tile: pull down the shade, tap, talk. */
class LumenTile : TileService() {
    override fun onClick() {
        super.onClick()
        val pi = PendingIntent.getActivity(this, 3, talkIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(pi)
        else @Suppress("DEPRECATION") startActivityAndCollapse(talkIntent(this))
    }
}

/** A home-screen orb: one tap and Lumen is listening. */
class LumenWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pi = PendingIntent.getActivity(ctx, 4, talkIntent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val views = RemoteViews(ctx.packageName, R.layout.widget_orb).apply { setOnClickPendingIntent(R.id.orb, pi) }
        for (id in ids) mgr.updateAppWidget(id, views)
    }
}
