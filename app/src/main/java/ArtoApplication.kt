package com.arto.app
import android.app.Application
import com.arto.app.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

/**
 * ArtoApplication — App-level initialization.
 *
 * Starts Koin dependency injection so that services like
 * SmsReceiver and ArtoCallScreeningService can resolve
 * dependencies without needing an Activity context.
 */
class ArtoApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidLogger(Level.ERROR)  // Reduce noise; set to DEBUG when troubleshooting DI
            androidContext(this@ArtoApplication)
            modules(appModule)
        }
    }
}