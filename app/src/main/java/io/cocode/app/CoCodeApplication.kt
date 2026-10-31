package io.cocode.app

import android.app.Application
import io.cocode.app.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.android.ext.android.get
import org.koin.core.context.startKoin

class CoCodeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@CoCodeApplication)
            modules(appModule)
        }
        // Process-wide: runtime error alerts and the one-time notification bootstrap.
        get<io.cocode.app.notify.NotificationObserver>().start()
    }
}
