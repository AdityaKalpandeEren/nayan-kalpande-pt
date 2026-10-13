package com.kalpande.nkpt.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kalpande.nkpt.MainActivity
import com.kalpande.nkpt.NkptApp
import com.kalpande.nkpt.R
import com.kalpande.nkpt.data.Market
import com.kalpande.nkpt.domain.Engine
import com.kalpande.nkpt.domain.EngineEvent

/**
 * Every ~15 minutes while the app is closed: runs the order engine for both accounts so limit /
 * stop orders, stop-losses, targets and the intraday square-off happen on time (fills use the
 * 1-minute bars, so a late check still fills at the right minute and price), then notifies.
 */
class FillWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as NkptApp
        val events = mutableListOf<EngineEvent>()
        for (m in Market.entries) {
            val a = app.broker.store.load(m)
            if (!Engine.hasWork(a)) continue
            runCatching { app.broker.refresh(m) }.onSuccess { events += it.events }
        }
        notify(applicationContext, events)
        return Result.success()
    }

    companion object {
        const val NAME = "nkpt-fills"
        const val CHANNEL = "fills"
        private var id = 2000

        fun notify(ctx: Context, events: List<EngineEvent>) {
            if (events.isEmpty()) return
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            for (e in events.filter { "filled" in it.text || "expired" in it.text }.take(6)) {
                val n = NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle("${e.market.flag} ${e.market.label} paper trade")
                    .setContentText(e.text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(e.text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
                runCatching { NotificationManagerCompat.from(ctx).notify(id++, n) }
            }
        }
    }
}
