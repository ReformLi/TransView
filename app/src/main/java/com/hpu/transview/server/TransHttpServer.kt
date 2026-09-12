package com.hpu.transview.server

import android.content.Context
import com.hpu.transview.data.MediaRepository
import com.hpu.transview.data.MediaType
import com.hpu.transview.data.UploadRecordRepository
import com.hpu.transview.data.UploadStateCode
import com.hpu.transview.model.Category
import com.hpu.transview.util.FileUtils
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * 内嵌 HTTP 服务器（NanoHTTPD）：
 * - GET  /        → 手机上传网页（assets/web/index.html）
 * - POST /upload  → multipart 上传，字段：category / relativePath / file
 *
 * 上传链路（数据库驱动）：
 * - 每个上传请求在 upload_records 表建立记录（上传中），经计数流实时回写百分比进度；
 * - 落盘成功/失败后更新记录状态，并将新文件立即写入 media_items 索引（视频后台提取时长）；
 * - UploadBus 仅保留为事件总线（媒体库自动刷新 / 空闲计时），不再承载记录展示。
 */
class TransHttpServer(
    context: Context,
    port: Int
) : NanoHTTPD(port) {

    private val appContext = context.applicationContext
    private val storage = UploadStorage(appContext)
    private val uploadRecords = UploadRecordRepository(appContext)
    private val mediaRepository = MediaRepository(appContext)
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 临时文件放在应用外部私有目录，与 /sdcard 同卷，移动零拷贝
        val tempDir = File(
            appContext.getExternalFilesDir(null) ?: appContext.cacheDir, "upload_tmp"
        ).apply { mkdirs() }
        setTempFileManagerFactory { ExternalTempFileManager(tempDir) }
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.GET && (session.uri == "/" || session.uri == "/index.html") ->
                    serveIndexPage()

                session.method == Method.POST && session.uri == "/upload" ->
                    handleUpload(session)

                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found"
                )
            }
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Server Error: ${e.message}"
            )
        }
    }

    private fun serveIndexPage(): Response {
        val input = appContext.assets.open("web/index.html")
        return newChunkedResponse(Response.Status.OK, "text/html; charset=utf-8", input)
    }

    private fun handleUpload(session: IHTTPSession): Response {
        // 手机网页把分类/文件名/相对路径放 URL query（请求头阶段即可用——multipart 字段
        // 必须等 parseBody 接收完整个请求体后才会填充，此前读取只会拿到回退值）
        val query = session.parameters
        val category = when (query["category"]?.firstOrNull()) {
            "image" -> Category.IMAGE
            "other" -> Category.OTHER
            else -> Category.VIDEO
        }
        val displayName = UploadStorage.sanitizeFileName(
            query["filename"]?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "file"
        )
        val relPath = query["relativepath"]?.firstOrNull() ?: ""
        val contentLength = session.headers["content-length"]?.trim()?.toLongOrNull() ?: 0L

        // 1. 建立上传记录（上传中）。NanoHTTPD 工作线程上短阻塞可接受
        val recordId = runBlocking {
            uploadRecords.insert(displayName, contentLength, MediaType.fromCategory(category))
        }
        val busId = UploadBus.start(displayName, contentLength)

        // 2. 进度监视：计数流字节数 / Content-Length → 回写百分比（节流）
        val monitorJob = startProgressMonitor(recordId, contentLength)

        // 3. 流式解析 multipart（大文件在此阻塞接收）
        val files = HashMap<String, String>()
        val parseError: ResponseException? = try {
            // NanoHTTPD 对不带 charset 的 multipart 请求按 US-ASCII 解析 part 头，
            // 会把浏览器以 UTF-8 发送的中文文件名解码损坏（落盘即乱码）。
            session.headers["content-type"] = session.headers["content-type"]
                ?.let { if (it.contains("charset=", ignoreCase = true)) it else "$it; charset=UTF-8" }
            session.parseBody(files)
            null
        } catch (e: ResponseException) {
            e
        }

        monitorJob.cancel()
        if (parseError != null) {
            finishRecord(recordId, busId, UploadStateCode.FAILED, 0)
            return jsonError(parseError.status, parseError.message ?: "请求解析失败")
        }

        val tempPath = files["file"]
        if (tempPath == null) {
            finishRecord(recordId, busId, UploadStateCode.FAILED, 0)
            return jsonError(Response.Status.BAD_REQUEST, "缺少文件")
        }
        val tempFile = File(tempPath)
        if (!tempFile.isFile || tempFile.length() == 0L) {
            finishRecord(recordId, busId, UploadStateCode.FAILED, 0)
            return jsonError(Response.Status.BAD_REQUEST, "上传内容无效")
        }

        // 4. 落盘 + 更新记录 + 建媒体索引
        // 落盘文件名以 multipart 头为准（服务端可信源）；缺失时回退 URL query 的 filename
        val multipartName = session.parameters["file"]?.firstOrNull()?.takeIf { it.isNotBlank() }
        val result = storage.save(tempFile, multipartName ?: displayName, relPath, category)
        val savedFile = result.getOrNull()
        finishRecord(
            recordId, busId,
            if (result.isSuccess) UploadStateCode.SUCCESS else UploadStateCode.FAILED,
            if (result.isSuccess) 100 else 0
        )
        if (savedFile != null) {
            indexMediaAsync(savedFile)
        }

        return if (result.isSuccess) {
            val json = JSONObject()
                .put("status", "ok")
                .put("filename", savedFile?.name ?: "")
            newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
        } else {
            jsonError(
                Response.Status.INTERNAL_ERROR,
                result.exceptionOrNull()?.message ?: "保存失败"
            )
        }
    }

    /** 上传结束后统一收尾：更新 DB 记录状态 + UploadBus 事件（媒体库自动刷新/空闲计时依赖后者） */
    private fun finishRecord(recordId: Long, busId: Long, state: Int, progress: Int) {
        runBlocking { uploadRecords.updateState(recordId, state, progress) }
        UploadBus.finish(busId, state == UploadStateCode.SUCCESS)
    }

    /** 轮询当前请求计数流的已写字节数，换算百分比回写 DB（600ms 节流，仅百分比变化时写） */
    private fun startProgressMonitor(recordId: Long, contentLength: Long): Job {
        if (contentLength <= 0) return Job().also { it.complete() }
        val manager = ExternalTempFileManager.current.get() ?: return Job().also { it.complete() }
        return bgScope.launch {
            var lastPct = -1
            while (isActive) {
                delay(600)
                val written = manager.bytesWritten.get()
                val pct = ((written * 100) / contentLength).toInt().coerceIn(0, 99)
                if (pct != lastPct) {
                    lastPct = pct
                    runCatching { uploadRecords.updateState(recordId, UploadStateCode.RUNNING, pct) }
                }
            }
        }
    }

    /** 落盘成功后立即建媒体索引（视频时长后台提取，不阻塞响应） */
    private fun indexMediaAsync(file: File) {
        bgScope.launch {
            runCatching {
                val type = MediaType.fromFile(file)
                val duration = if (type == MediaType.VIDEO) FileUtils.extractVideoDuration(file) else 0L
                mediaRepository.upsert(
                    filePath = file.absolutePath,
                    fileName = file.name,
                    mediaType = type,
                    parentFolder = file.parentFile?.absolutePath ?: "",
                    fileSize = file.length(),
                    lastModified = file.lastModified(),
                    duration = duration
                )
            }
        }
    }

    fun shutdown() {
        bgScope.cancel()
    }

    private fun jsonError(status: Response.IStatus, message: String): Response {
        val json = JSONObject().put("status", "error").put("message", message)
        return newFixedLengthResponse(status, "application/json", json.toString())
    }

    /** 把上传临时文件放到指定目录（外部存储），代替默认的系统临时目录。
     *  计数流：写入字节数累加到 bytesWritten，供进度监视换算百分比。 */
    private class ExternalTempFileManager(private val dir: File) : NanoHTTPD.TempFileManager {

        companion object {
            /** NanoHTTPD 每个会话在固定工作线程上处理，tempFileManager 创建与 handleUpload 同线程，
             *  用 ThreadLocal 把"当前会话的计数器"递给 handleUpload */
            val current = ThreadLocal<ExternalTempFileManager?>()
        }

        val bytesWritten = AtomicLong(0)
        private val created = mutableListOf<File>()

        init {
            current.set(this)
        }

        override fun createTempFile(filename: String?): NanoHTTPD.TempFile {
            val safe = (filename ?: "").replace(Regex("[^\\w.-]"), "_").takeLast(40)
            val file = File(dir, "upload_${System.nanoTime()}_$safe.tmp")
            created += file
            return object : NanoHTTPD.TempFile {
                private var stream: CountingOutputStream? = null
                override fun delete() { runCatching { stream?.close() }; file.delete() }
                override fun getName(): String = file.absolutePath
                override fun open(): OutputStream {
                    if (stream == null) {
                        stream = CountingOutputStream(
                            BufferedOutputStream(FileOutputStream(file), 128 * 1024), bytesWritten
                        )
                    }
                    return stream!!
                }
            }
        }

        override fun clear() {
            created.forEach { it.delete() }
            created.clear()
        }
    }

    /** 字节计数输出流 */
    private class CountingOutputStream(
        private val delegate: OutputStream,
        private val counter: AtomicLong
    ) : OutputStream() {
        override fun write(b: Int) {
            delegate.write(b)
            counter.incrementAndGet()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            counter.addAndGet(len.toLong())
        }

        override fun flush() { delegate.flush() }
        override fun close() { delegate.close() }
    }
}
