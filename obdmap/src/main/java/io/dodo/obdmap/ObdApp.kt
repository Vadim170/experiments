package io.dodo.obdmap

import android.app.Application
import android.content.Context
import io.dodo.obdmap.util.Logger
import org.osmdroid.config.Configuration
import java.io.File

class ObdApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Logger.init(this)

        // osmdroid по умолчанию лезет во внешнее хранилище и требует разрешений.
        // Держим тайлы в приватном кеше приложения — разрешения не нужны.
        val configuration = Configuration.getInstance()
        configuration.load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        configuration.userAgentValue = packageName
        configuration.osmdroidBasePath = File(cacheDir, "osmdroid").apply { mkdirs() }
        configuration.osmdroidTileCache = File(cacheDir, "osmdroid/tiles").apply { mkdirs() }
    }
}
