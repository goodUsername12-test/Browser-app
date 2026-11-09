package com.fable.browser.engine

import android.content.Context
import com.fable.browser.data.prefs.SettingsStore
import com.fable.browser.data.repo.FaviconStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CacheCleaner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engineManager: EngineManager,
    private val settingsStore: SettingsStore,
    private val faviconStore: FaviconStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val busy = AtomicBoolean(false)

    fun onAppBackgrounded() {
        if (!busy.compareAndSet(false, true)) return
        scope.launch {
            try {
                if (!settingsStore.settings.first().autoClearCache) return@launch
                val now = System.currentTimeMillis()
                val due = now - prefs.getLong(KEY_LAST, 0L) >= DAY_MS
                if (!due && sizeOf(context.cacheDir) < LIMIT_BYTES) return@launch
                withContext(Dispatchers.Main) { engineManager.clearCache() }
                faviconStore.prune()
                prefs.edit().putLong(KEY_LAST, now).apply()
            } catch (e: Exception) {
            } finally {
                busy.set(false)
            }
        }
    }

    private fun sizeOf(dir: File): Long {
        var total = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile) {
                total += f.length()
                if (total >= LIMIT_BYTES) return total
            }
        }
        return total
    }

    private companion object {
        const val PREFS = "fable_cache_cleaner"
        const val KEY_LAST = "last_clean"
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val LIMIT_BYTES = 300L * 1024 * 1024
    }
}
