package io.dodo.obdmap

import android.app.Application
import android.content.Context
import io.dodo.obdmap.util.Logger
import org.osmdroid.config.Configuration
import java.io.File

class ObdApp : Application() {

    private companion object {
        /** Потолок кеша тайлов. Города хватает с запасом. */
        const val TILE_CACHE_MAX_BYTES = 600L * 1024 * 1024

        /** До какого объёма подчищаем, когда потолок достигнут. */
        const val TILE_CACHE_TRIM_BYTES = 500L * 1024 * 1024

        /**
         * Насколько считаем скачанный тайл годным. OSM отдаёт короткий срок
         * жизни, и с ним карта в офлайне была бы пустой: тайл есть, но протух.
         */
        val TILE_EXPIRATION_MS = 365L * 24 * 60 * 60 * 1000
    }

    override fun onCreate() {
        super.onCreate()
        Logger.init(this)

        val configuration = Configuration.getInstance()
        configuration.load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))

        // Политика OSM требует внятный User-Agent, иначе тайлы отдавать перестанут
        configuration.userAgentValue = packageName

        // Кеш держим в files, а не в cacheDir: систему никто не просил чистить
        // карту, когда на телефоне кончается место — иначе офлайн отвалится.
        val base = File(getExternalFilesDir(null) ?: filesDir, "osmdroid").apply { mkdirs() }
        configuration.osmdroidBasePath = base
        configuration.osmdroidTileCache = File(base, "tiles").apply { mkdirs() }

        configuration.tileFileSystemCacheMaxBytes = TILE_CACHE_MAX_BYTES
        configuration.tileFileSystemCacheTrimBytes = TILE_CACHE_TRIM_BYTES
        configuration.expirationOverrideDuration = TILE_EXPIRATION_MS

        Logger.log("кеш карт: ${configuration.osmdroidTileCache.absolutePath}")
    }
}
