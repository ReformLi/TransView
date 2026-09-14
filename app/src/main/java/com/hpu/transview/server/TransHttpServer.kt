package com.hpu.transview.server

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.hpu.transview.data.MediaRepository
import com.hpu.transview.data.MediaType
import com.hpu.transview.data.UploadRecordRepository
import com.hpu.transview.data.UploadStateCode
import com.hpu.transview.model.Category
import com.hpu.transview.ui.settings.SettingsStore
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

/** 上传网页模板里的设备名占位符（assets/web/index.html） */
private const val DEVICE_NAME_PLACEHOLDER = "__DEVICE_NAME__"

/**
 * 内嵌 HTTP 服务器（NanoHTTPD）：
 * - GET  /         → 手机上传网页（assets/web/index.html，渲染时注入设备名称）
 * - GET  /verify   → 访问码校验（`?token=xxxxxx`），供「手动输入 IP」的网页在提交前先验一次
 * - POST /upload   → multipart 上传，字段：category / relativePath / file；需带 `X-Upload-Token` 头
 *
 * 端口与**访问码**由 ServerController 决定（端口可在设置页改；改端口 / 重启都会轮换访问码，
 * NanoHTTPD 端口构造时固定 → 改端口需停旧起新），两者都在构造时注入。
 *
 * 上传链路（数据库驱动）：
 * - 每个上传请求在 upload_records 表建立记录（上传中），经计数流实时回写百分比进度；
 * - 落盘成功/失败后更新记录状态，并将新文件立即写入 media_items 索引（视频后台提取时长）；
 * - UploadBus 仅保留为事件总线（媒体库自动刷新 / 空闲计时），不再承载记录展示。
 * - **压缩包自动解压**：视频 / 图片分类上传 `.zip` 且「设置 → 上传与解压 → 自动解压压缩包」开启时，
 *   改由 [ZipExtractor] 处理（暂存 → 空间校验 → 流式解压 → 按分类归位），解压结果经 JSON
 *   `message` 回给网页端并同时 Toast 到电视端；「其他」分类的 `.zip` 仍原样存入 Downloads。
 *
 * @param token 本次监听周期内的访问码（6 位，已是大写）。服务器实例持有的是构造时的那一份，
 *   ServerController 每次启动都新建实例并轮换，因此不存在「运行中换码」。
 *   详见 [checkToken]。
 */
class TransHttpServer(
    context: Context,
    port: Int,
    private val token: String
) : NanoHTTPD(port) {

    private val appContext = context.applicationContext
    private val storage = UploadStorage(appContext)
    private val zipExtractor = ZipExtractor(appContext)
    private val uploadRecords = UploadRecordRepository(appContext)
    private val mediaRepository = MediaRepository(appContext)
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 电视端 Toast 需要主线程 Looper */
    private val mainHandler = Handler(Looper.getMainLooper())

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
                // 根路径**不校验访问码**：网页本身得先加载出来，才有地方显示「输入访问码」界面
                session.method == Method.GET && (session.uri == "/" || session.uri == "/index.html") ->
                    serveIndexPage()

                // 访问码预校验（手动输入 IP 进入的手机网页在提交前先验一次，避免输错了要等上传才报错）
                session.method == Method.GET && session.uri == "/verify" ->
                    handleVerify(session)

                // 网页图标（头部标识 + 浏览器标签页 favicon），与 assets/web/icon.* 同源
                session.method == Method.GET && session.uri == "/icon.svg" ->
                    serveAsset("web/icon.svg", "image/svg+xml")

                session.method == Method.GET && session.uri == "/icon.png" ->
                    serveAsset("web/icon.png", "image/png")

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

    /**
     * `GET /verify?token=xxxxxx` —— 访问码校验。
     *
     * 匹配返回 200（`{"status":"ok"}`），缺失或不匹配返回 403。
     * 大小写不敏感（统一按大写比较），与手机端输入框自动转大写的行为对齐。
     */
    private fun handleVerify(session: IHTTPSession): Response {
        if (!checkToken(session.parameters["token"]?.firstOrNull())) {
            return jsonError(Response.Status.FORBIDDEN, "认证失败")
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"ok"}""")
    }

    /**
     * 访问码比对：统一 trim + 大写后按全等比较（服务器生成的码本身就是大写）。
     * 传入 null / 空串一律不通过。
     */
    private fun checkToken(candidate: String?): Boolean {
        val normalized = candidate?.trim()?.uppercase().orEmpty()
        return normalized.isNotEmpty() && normalized == token.uppercase()
    }

    /**
     * 取请求头。NanoHTTPD 把请求头名统一转成小写存表，这里再兜一层原名，
     * 免得将来换实现 / 自造请求时大小写不一致取不到。
     */
    private fun headerOf(session: IHTTPSession, name: String): String? =
        session.headers[name.lowercase()] ?: session.headers[name]

    /**
     * 手机上传页。每次请求都重新渲染：把 assets 里的模板占位符替换为当前设备名称
     * （设置 → 服务器与网络 → 设备名称），用户改完设备名刷新手机页面即生效，无需重启服务器。
     */
    private fun serveIndexPage(): Response {
        val deviceName = SettingsStore.deviceName.ifBlank { SettingsStore.DEFAULT_DEVICE_NAME }
        val html = runCatching {
            appContext.assets.open("web/index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
        if (html == null) {
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "upload page missing"
            )
        }
        val rendered = html.replace(DEVICE_NAME_PLACEHOLDER, escapeHtml(deviceName))
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", rendered)
    }

    /** 占位符替换的值来自用户设置，必须转义，避免设备名里的 `<` `&` 破坏页面结构 */
    private fun escapeHtml(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    /**
     * 提供 assets 里的静态资源（图标）。
     * 每次请求都重新读取，换图后手机端刷新即生效，无需重启服务器。
     */
    private fun serveAsset(assetPath: String, mime: String): Response {
        val bytes = runCatching {
            appContext.assets.open(assetPath).use { it.readBytes() }
        }.getOrNull() ?: return newFixedLengthResponse(
            Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found"
        )
        return newFixedLengthResponse(
            Response.Status.OK, mime, bytes.inputStream(), bytes.size.toLong()
        )
    }

    private fun handleUpload(session: IHTTPSession): Response {
        // 认证门槛：缺 Header 或访问码不匹配 → 403，且**不接收任何文件**。
        // 必须放在函数最前面：此刻还没建上传记录、也没调 parseBody —— 请求体一个字节都不会落盘，
        // 不会在电视端记录列表里留下任何痕迹（否则未授权设备能靠刷请求塞满上传记录）。
        if (!checkToken(headerOf(session, "X-Upload-Token"))) {
            return jsonError(Response.Status.FORBIDDEN, "认证失败")
        }

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

        // 3. 接收 → 落盘 → 收尾。**必须兜底**：手机端「取消上传」= abort 连接，会让
        //    parseBody 抛出 ResponseException 之外的异常（IO 中断）；不在这里收尾的话，
        //    该条上传记录会永远停在「上传中」，异常还会冒泡到 serve() 变成 500。
        return try {
            receiveAndSave(session, recordId, busId, displayName, relPath, category)
        } catch (e: Exception) {
            finishRecord(recordId, busId, UploadStateCode.FAILED, 0)
            jsonError(Response.Status.INTERNAL_ERROR, e.message ?: "上传中断")
        } finally {
            monitorJob.cancel()
        }
    }

    /** 流式接收 multipart → 落盘 → 更新记录 → 建媒体索引（成功/失败路径都由本函数收尾） */
    private fun receiveAndSave(
        session: IHTTPSession,
        recordId: Long,
        busId: Long,
        displayName: String,
        relPath: String,
        category: Category
    ): Response {
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
        val finalName = multipartName ?: displayName

        // 压缩包自动解压：视频 / 图片分类上传 .zip 且设置开启时，改走「暂存 → 解压 → 按分类归位」，
        // 不把 .zip 原样落进分类目录（「其他」分类的 .zip 仍然直接存 Downloads，不解压）
        if (category != Category.OTHER &&
            finalName.endsWith(".zip", ignoreCase = true) &&
            SettingsStore.autoUnzipZip
        ) {
            return receiveZipAndExtract(tempFile, recordId, busId, finalName, category)
        }

        val result = storage.save(tempFile, finalName, relPath, category)
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

    /**
     * 压缩包自动解压分支的收尾：解压与归位全部在 IO 线程完成，回包时把结果 JSON 交给网页端提示。
     *
     * 上传记录一律记为「成功」—— 压缩包本身已经安全收下；解压是后处理，
     * 其结果（已解压 N 个 / 未找到目标文件 / 空间不足）通过 [ZipExtractor.Outcome.message]
     * 同时提示给网页端（JSON `message`）与电视端（Toast）。
     */
    private fun receiveZipAndExtract(
        tempFile: File,
        recordId: Long,
        busId: Long,
        zipName: String,
        category: Category
    ): Response {
        val outcome = runBlocking(Dispatchers.IO) {
            runCatching { zipExtractor.process(tempFile, zipName, category) }
                .getOrElse { e ->
                    ZipExtractor.Outcome(
                        ZipExtractor.Kind.FAILED, emptyList(), null,
                        "解压失败：${e.message ?: "未知错误"}，请重新上传"
                    )
                }
        }

        finishRecord(recordId, busId, UploadStateCode.SUCCESS, 100)
        // 归位后的文件立即建媒体索引（视频后台提取时长）
        outcome.movedFiles.forEach { indexMediaAsync(it) }
        // 解压失败 / 空间不足 / 未找到目标文件时保留到 Downloads 的原压缩包也要立即入库，
        // 否则「其他」页要等下次对账才看得到（媒体库由 Room 驱动，不读目录）
        outcome.keptZipPath?.let { path -> indexMediaAsync(File(path)) }
        toastOnTv(outcome.message)

        val json = JSONObject()
            .put("status", "ok")
            .put("filename", zipName)
            .put("unzip", outcome.kind.name.lowercase())
            .put("extracted", outcome.movedFiles.size)
            .put("message", outcome.message)
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    /**
     * 电视端 Toast 提示（解压结果需在人能看到电视时告知）。
     * 服务器跑在 NanoHTTPD 工作线程，Toast 必须回主线程；应用不在前台时静默失败即可。
     */
    private fun toastOnTv(message: String) {
        mainHandler.post {
            runCatching { Toast.makeText(appContext, message, Toast.LENGTH_LONG).show() }
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
