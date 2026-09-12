package com.hpu.transview.ui.common

import android.media.MediaMetadataRetriever
import java.util.concurrent.ConcurrentHashMap

/** 视频时长缓存：列表可见项异步加载，避免全量扫描耗时 */
object VideoMeta {

    private val durationCache = ConcurrentHashMap<String, Long>()

    suspend fun getDuration(path: String): Long? {
        durationCache[path]?.let { return it }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(path)
                    val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    durationCache[path] = ms
                    ms
                }
            }.getOrNull()
        }
    }
}
