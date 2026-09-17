package com.hpu.transview.ui.common

import android.media.MediaMetadataRetriever
import java.util.concurrent.ConcurrentHashMap

/** 视频时长缓存：列表可见项异步加载，避免全量扫描耗时 */
object VideoMeta {

    private val durationCache = ConcurrentHashMap<String, Long>()

    suspend fun getDuration(path: String): Long? {
        durationCache[path]?.let { return it }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // 不能用 use{}：MediaMetadataRetriever 的 close()/AutoCloseable 是 API 29 才有的，
            // minSdk 21 的机器上 finally 会抛 NoSuchMethodError，时长被吞成 null 且原生资源泄漏。
            // 显式 release()（API 10 起可用）才是全版本安全写法，与 FileUtils.extractVideoDuration 同规。
            val retriever = MediaMetadataRetriever()
            runCatching {
                retriever.setDataSource(path)
                val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                durationCache[path] = ms
                ms
            }.also { runCatching { retriever.release() } }.getOrNull()
        }
    }
}
