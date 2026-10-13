package com.kalpande.nkpt

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kalpande.nkpt.data.AccountStore
import com.kalpande.nkpt.data.Broker
import com.kalpande.nkpt.data.InstrumentRepository
import com.kalpande.nkpt.data.MarketData
import com.kalpande.nkpt.data.SettingsRepository
import com.kalpande.nkpt.work.FillWorker
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

class NkptApp : Application() {
    lateinit var broker: Broker
        private set
    lateinit var instruments: InstrumentRepository
        private set
    lateinit var settings: SettingsRepository
        private set

    /** Upstox access token for NSE live quotes (blank = candles only). Kept in sync by Settings. */
    @Volatile
    var upstoxToken: String = ""

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        upstoxToken = runBlocking { settings.tokenNow() }
        broker = Broker(AccountStore(filesDir), MarketData { upstoxToken }, settings)
        instruments = InstrumentRepository(filesDir)

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(FillWorker.CHANNEL, getString(R.string.channel_fills), NotificationManager.IMPORTANCE_DEFAULT)
        )
        runCatching { scheduleWorker() }
    }

    private fun scheduleWorker() {
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            FillWorker.NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FillWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }
}
