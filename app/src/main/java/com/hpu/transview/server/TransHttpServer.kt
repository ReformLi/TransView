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
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/** 上传网页模板里的设备名占位符（assets/web/index.html） */
private const val DEVICE_NAME_PLACEHOLDER = "__DEVICE_NAME__"

/**
 * 内嵌 HTTP 服务器（NanoHTTPD）：
 * - GET  /         → 手机上传网页（assets/web/index.html，渲染时注入设备名称）
 * - GET  /verify   → 访问码校验（`?token=xxxxxx`），供「手动输入 IP」的网页在提交前先验一次
 * - POST /upload   → multipart 上传，字段：category / relativePath / file；需带 `X-Upload-Token` 头
 *
 * 端口与**访问码**由 ServerController 决定（端口可在设置页改，NanoHTTPD 端口构造时固定
 * → 改端口需停旧起新；访问码**会话内固定**：进程内停启/改端口沿用同一码，进程重启才轮换），
 * 两者都在构造时注入。
 *
 * 上传链路（数据库驱动）：
 * - 每个上传请求在 upload_records 表建立记录（上传中），经计数流实时回写百分比进度；
 * - 落盘成功/失败后更新记录状态，并将新文件立即写入 media_items 索引（视频后台提取时长）；
 * - UploadBus 仅保留为事件总线（媒体库自动刷新 / 空闲计时），不再承载记录展示。
 * - **压缩包自动解压**：视频 / 图片分类上传 `.zip` 即改由 [ZipExtractor] 处理（固定行为，无开关——
 *   分类本身就是意图表达），暂存 → 空间校验 → 流式解压 → 按分类归位，解压结果经 JSON
 *   `message` 回给网页端并同时 Toast 到电视端；「其他」分类的 `.zip` 仍原样存入 Downloads。
 *
 * @param token 会话内固定的访问码（6 位，已是大写）。服务器实例持有的是构造时的那一份；
 *   ServerController 在进程内停启 / 改端口均沿用同一码，因此不存在「运行中换码」。
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

    /**
     * 「当前连接的输入流计数器」。**必须由计数流在 read 时绑到「正在读它的线程」**，
     * 不能在 [createClientHandler] 里 set —— createClientHandler 跑在 ServerRunnable 的
     * 接收连接线程上，而 handleUpload 跑在 asyncRunner 线程池的工作线程上（NanoHTTPD 2.3.1
     * 实测源码：`asyncRunner.exec(createClientHandler(...))`），两者不是同一个线程，
     * 在 createClientHandler 里 set 的话 handleUpload 里 get 恒为 null → 进度监视器直接
     * 跳过 → 进度恒 0%。流 read 只发生在该连接的工作线程上，由 read 绑定天然对齐；
     * 线程池复用也安全：下一条连接的流一 read 就会覆盖旧值。
     */
    private val inputCounterThreadLocal = ThreadLocal<AtomicLong>()

    init {
        // 临时文件放在应用外部私有目录，与 /sdcard 同卷，移动零拷贝
        val tempDir = tempDirOf(appContext).apply { mkdirs() }
        // 每次启动服务器顺手清一次遗留：进程被杀 / 断电时 NanoHTTPD 的
        // TempFileManager.clear() 没机会执行，半个上传会以 `upload_*.tmp` 永久留在
        // `Android/data/<包名>/files/upload_tmp/`（Android 11+ 用户连文件管理器都进不去）。
        // 带保护窗口：「停服务器 → 立刻再启动」的瞬间，上一实例的工作线程可能还在写自己的临时文件。
        runCatching { purgeOrphanUploadTemps(appContext) }
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
     * 包装每个连接的套接字输入流，累计「已读字节数」，这是获得真实上传进度的唯一可靠挂接点。
     *
     * 为什么必须在这里计数：NanoHTTPD 2.3.1 在 `parseBody` 阶段先把**整个请求体**读进内存 /
     * 暂存临时文件，再在 `decodeMultipartFormData → saveTmpFile` 里用
     * `new FileOutputStream(tempFile.getName())` 按**文件名**直接写盘——文件 part 从不经过
     * `TempFile.open()` 返回的流，故原先挂在 TempFile 上的 `bytesWritten` 计数器恒为 0，进度永远是 0%。
     * 只有从「输入流」这一层计数才准（请求头字节相对文件体可忽略）。
     */
    override fun createClientHandler(socket: Socket, inputStream: InputStream): ClientHandler {
        // 不在这里 set ThreadLocal：本方法跑在「接收连接线程」，handleUpload 在「工作线程」，
        // 跨线程取不到。绑定改由 CountingInputStream 在 read 时完成（见字段注释）。
        return ClientHandler(CountingInputStream(inputStream), socket)
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

        // 2. 进度监视：已接收字节数 / Content-Length → 回写百分比（节流）。
        //    计数来自「套接字输入流」包装（见 createClientHandler）——NanoHTTPD 2.3.1 把文件 part
        //    按文件名直接写盘，不经过 TempFile.open() 的流，故挂在那里的字节计数恒为 0。
        //    此处把计数器清零，保证 keep-alive 同连接多文件依次上传互不干扰。
        val counter = inputCounterThreadLocal.get()
        counter?.set(0)
        val monitorJob = startProgressMonitor(recordId, contentLength, counter)

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

        // 压缩包自动解压（固定行为，无设置开关）：视频 / 图片分类上传 .zip 即走
        // 「暂存 → 解压 → 按分类归位」，不把 .zip 原样落进分类目录——分类本身就是意图表达，
        // 想保留 zip 原样就选「其他」分类（「其他」的 .zip 仍然直接存 Downloads，不解压）
        if (category != Category.OTHER &&
            finalName.endsWith(".zip", ignoreCase = true)
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
        outcome.keptZip?.let { indexMediaAsync(it) }
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

    /** 轮询「已接收字节数」换算百分比回写 DB（600ms 节流，仅百分比变化时写）。
     *  [counter] 来自套接字输入流的计数包装（见 [createClientHandler]）；为 null 或 contentLength<=0
     *  时直接跳过中间进度，仅由收尾的 [finishRecord] 置 100%（与改造前一致）。
     *  用 updateProgressIfRunning：与收尾的 updateState 走不同协程，落库顺序不保证，
     *  无条件写会把已写入的「成功 100%」覆盖回「上传中 99%」，该记录将永远卡在上传中 */
    private fun startProgressMonitor(recordId: Long, contentLength: Long, counter: AtomicLong?): Job {
        if (contentLength <= 0 || counter == null) return Job().also { it.complete() }
        return bgScope.launch {
            var lastPct = -1
            while (isActive) {
                delay(600)
                val received = counter.get()
                val pct = ((received * 100) / contentLength).toInt().coerceIn(0, 99)
                if (pct != lastPct) {
                    lastPct = pct
                    runCatching { uploadRecords.updateProgressIfRunning(recordId, pct) }
                }
            }
        }
    }

    /** 落盘成功后立即建媒体索引（视频时长后台提取，不阻塞响应）。
     *  [saved] 来自 UploadStorage，`path` 恒为绝对路径，时长提取走 `setDataSource(path)`。 */
    private fun indexMediaAsync(saved: UploadStorage.Saved) {
        bgScope.launch {
            runCatching {
                val type = MediaType.fromFileName(saved.name)
                val duration = if (type == MediaType.VIDEO) {
                    FileUtils.extractVideoDuration(appContext, saved.path)
                } else 0L
                mediaRepository.upsert(
                    filePath = saved.path,
                    fileName = saved.name,
                    mediaType = type,
                    parentFolder = saved.parentPath,
                    fileSize = saved.size,
                    lastModified = saved.lastModified,
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
     *  进度计数已改到「套接字输入流」一层（见 [createClientHandler]）：NanoHTTPD 2.3.1 把文件 part
     *  按文件名直接写盘，不经过 open() 返回的流，TempFile 内部做字节计数毫无意义，这里只提供落盘位置。 */
    private class ExternalTempFileManager(private val dir: File) : NanoHTTPD.TempFileManager {
        private val created = mutableListOf<File>()
        init { dir.mkdirs() }
        override fun createTempFile(filename: String?): NanoHTTPD.TempFile {
            val safe = (filename ?: "").replace(Regex("[^\\w.-]"), "_").takeLast(40)
            val file = File(dir, "upload_${System.nanoTime()}_$safe.tmp")
            created += file
            return object : NanoHTTPD.TempFile {
                private var stream: OutputStream? = null
                override fun delete() { runCatching { stream?.close() }; file.delete() }
                override fun getName(): String = file.absolutePath
                override fun open(): OutputStream {
                    if (stream == null) {
                        stream = BufferedOutputStream(FileOutputStream(file), 128 * 1024)
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

    /** 字节计数输入流：每 read 一字节 / 一块就累加到 [counter]，用于上报真实上传进度。
     *  每次读取都把计数器绑到「当前正在读它的线程」（[inputCounterThreadLocal]）——
     *  这是对齐线程的关键：handleUpload 与流读取必在同一工作线程上（keep-alive 循环内），
     *  而 createClientHandler 跑在另一个线程上，靠它绑定永远对不上。inner 类：要写外部的 ThreadLocal。 */
    private inner class CountingInputStream(
        private val delegate: InputStream
    ) : InputStream() {
        private val counter = AtomicLong(0)

        private fun bindToCurrentThread() {
            inputCounterThreadLocal.set(counter)
        }

        override fun read(): Int {
            bindToCurrentThread()
            val b = delegate.read()
            if (b >= 0) counter.incrementAndGet()
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            bindToCurrentThread()
            val n = delegate.read(b, off, len)
            if (n > 0) counter.addAndGet(n.toLong())
            return n
        }
        override fun available(): Int = delegate.available()
        override fun skip(n: Long): Long = delegate.skip(n)
        override fun close() { delegate.close() }
    }

    companion object {

        /** 上传临时目录名（位于应用外部私有目录 `files/` 下，与 /sdcard 同卷） */
        private const val TEMP_DIR_NAME = "upload_tmp"

        /**
         * 遗留临时文件的保护窗口：最近该时长内仍被改动过的文件视为「可能正在上传」，跳过不删。
         *
         * 取 10 分钟与 [com.hpu.transview.data.sync.SyncManager] 保护在途解压工作区的窗口一致
         * —— 两者面对的是同一类竞态（一轮动作刚结束、下一轮就已经开始清扫）。
         */
        const val ORPHAN_TEMP_GRACE_MS = 10 * 60 * 1000L

        /** 上传临时文件目录（不存在时返回其应有路径，不创建） */
        fun tempDirOf(context: Context): File =
            File(context.applicationContext.getExternalFilesDir(null) ?: context.cacheDir, TEMP_DIR_NAME)

        /**
         * 清理**遗留**的上传临时文件，返回删除的文件数。
         *
         * ## 为什么必须清
         * NanoHTTPD 的临时文件由 `TempFileManager.clear()` 在每次连接收尾时删除，正常路径不会残留；
         * 但**进程被杀 / 断电 / 系统回收**时 `clear()` 根本没机会跑，半个上传就以
         * `upload_*.tmp` 的形式永久留在 `Android/data/<包名>/files/upload_tmp/`。这个目录：
         * - 不在媒体沙盒（`TransView/`）内 → 对账的 `.temp_unzip` 清理与媒体库都碰不到它；
         * - Android 11+ 起 `Android/data/` 对文件管理器不可见 → 用户**没有任何**手动清理途径；
         * - 一次中断的大文件上传就能留下几百 MB ~ 数 GB 的不可见占用。
         *
         * ## 调用点与保护窗口
         * - [com.hpu.transview.TransViewApp.onCreate]：传 `skipActiveWithinMs = 0`
         *   —— 进程刚起，本进程内不可能有在途上传（服务器尚未启动），遗留下来的必是死文件；
         * - 服务器启动（本类 `init`）与设置页「清理缓存」：用默认的 [ORPHAN_TEMP_GRACE_MS]，
         *   避开「停服务器 → 立刻重启」/「清理时正在上传」的竞态。
         *
         * 全程 `runCatching`：删不掉（被占用 / 权限）留在原地即可，绝不打断服务器启动。
         */
        fun purgeOrphanUploadTemps(
            context: Context,
            skipActiveWithinMs: Long = ORPHAN_TEMP_GRACE_MS
        ): Int {
            val dir = tempDirOf(context)
            if (!dir.isDirectory) return 0
            val cutoff = System.currentTimeMillis() - skipActiveWithinMs
            var removed = 0
            runCatching {
                dir.listFiles()?.forEach { f ->
                    if (skipActiveWithinMs > 0 && f.lastModified() >= cutoff) return@forEach
                    runCatching { if (f.delete()) removed++ }
                }
            }
            return removed
        }
    }
}
