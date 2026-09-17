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
import com.hpu.transview.util.AppLogger
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URLDecoder
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
        // 上传临时文件直接写在应用外部私有目录（与 /sdcard 同卷，落盘移动零拷贝）。
        // v1.30：改用手动流式 multipart 解析后，文件 part 由 [MultipartStreamParser]
        // 自建临时文件，不再借助 NanoHTTPD 的 TempFile 体系（那套是 parseBody 内部用的）——
        // 故这里不再 setTempFileManagerFactory。
        runCatching { tempDirOf(appContext).apply { mkdirs() } }
        // 每次启动服务器顺手清一次遗留：进程被杀 / 断电时没机会执行的清理，
        // 半个上传会以 `upload_*.tmp` 永久留在 `Android/data/<包名>/files/upload_tmp/`
        //（Android 11+ 用户连文件管理器都进不去）。带保护窗口：
        //「停服务器 → 立刻再启动」的瞬间，上一实例的工作线程可能还在写自己的临时文件。
        val purged = runCatching { purgeOrphanUploadTemps(appContext) }.getOrDefault(0)
        if (purged > 0) AppLogger.d(TAG, "服务器启动时清理遗留上传临时文件 $purged 个")
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
            // 此前这里只回 500、服务端一行不留：手机端看到「服务器错误」，电视端什么也不知道。
            // 记 method + uri 即可定位是哪条路由出的问题（无需记请求体，可能很大）
            AppLogger.e(TAG, "请求处理异常：${session.method} ${session.uri}", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Server Error: ${e.message}"
            )
        }
    }

    /**
     * 包装每个连接的套接字输入流，累计「已读字节数」，这是获得真实上传进度的唯一可靠挂接点。
     *
     * 为什么必须在这里计数：v1.30 起上传不再调用 `parseBody`，而是由
     * [receiveAndSave] 直接从 [NanoHTTPD.IHTTPSession.getInputStream]（即本方法包装后的
     * 这个流）手动读 multipart。只要新解析器也从这里读，计数就自动生效；请求头字节相对
     * 文件体可忽略。
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
            // 未授权的访问码尝试。「手机总是连不上」时，这条是区分
            // 「用户输错码」与「电视端显示的码与服务器认的不一致」的关键依据
            AppLogger.w(TAG, "访问码校验失败（/verify），来自 ${session.remoteIpAddress}")
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
            // 被拒的上传请求：同样不接收任何字节。记一条 W 便于发现「有人一直连不上」
            // 或「未授权设备在刷请求」
            AppLogger.w(TAG, "上传被拒（访问码无效），来自 ${session.remoteIpAddress}")
            return jsonError(Response.Status.FORBIDDEN, "认证失败")
        }

        // 手机网页把分类/文件名/相对路径放 URL query（请求头阶段即可用——文件随请求体流式传输，
        // 需接收完 body 才能拿到；分类在入队时即定好，放 query 能先建记录、立即显示进度）
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
        // 上传开始：文件名 / 大小 / 分类 / 来源 IP。**刻意不记进度** —— 进度是 600ms 一次的高频事件，
        // 记它会把有界队列打满并挤掉真正有用的日志（而丢弃是静默的，事后无从察觉）
        AppLogger.i(
            TAG,
            "上传开始：$displayName（$contentLength 字节，${category.name}），" +
                "来自 ${session.remoteIpAddress}"
        )

        // 2. 进度监视：已接收字节数 / Content-Length → 回写百分比（节流）。
        //    计数来自「套接字输入流」包装（见 createClientHandler）——v1.30 改手动流式解析后，
        //    receiveAndSave 从 session.getInputStream() 读的就是这个计数流。
        //    此处把计数器清零，保证 keep-alive 同连接多文件依次上传互不干扰。
        val counter = inputCounterThreadLocal.get()
        counter?.set(0)
        val monitorJob = startProgressMonitor(recordId, contentLength, counter)

        // 3. 接收 → 落盘 → 收尾。**必须兜底**：手机端「取消上传」= abort 连接，会让
        //    手动解析 / 流式写入抛出 IO 中断类异常；不在这里收尾的话，
        //    该条上传记录会永远停在「上传中」，异常还会冒泡到 serve() 变成 500。
        return try {
            receiveAndSave(session, recordId, busId, displayName, relPath, category)
        } catch (e: Exception) {
            // 手机端「取消上传」= abort 连接，会让流式解析抛出 IO 中断类异常。
            // 这是「文件传了一半就没了」最直接的现场记录，务必留下
            AppLogger.w(TAG, "上传中断：$displayName（$contentLength 字节）", e)
            finishRecord(recordId, busId, UploadStateCode.FAILED, 0)
            jsonError(Response.Status.INTERNAL_ERROR, e.message ?: "上传中断")
        } finally {
            monitorJob.cancel()
        }
    }

    /** 流式接收 multipart → 落盘 → 更新记录 → 建媒体索引（成功/失败路径都由本函数收尾）
     *
     * ## 为什么必须手工解析（v1.30，修复上传大文件 OOM）
     * 旧实现走 [NanoHTTPD.IHTTPSession.parseBody]，它内部用 `FileChannel.map()` 把请求体整体
     * 内存映射进**虚拟内存**——1.74GB 的 .mp4 在电视盒子上瞬间撑爆虚拟内存，报
     * `java.io.IOException: Map failed` / `OutOfMemoryError`。
     *
     * 这里改为从 [NanoHTTPD.IHTTPSession.getInputStream] 直接读（NanoHTTPD 的 `execute()`
     * 在调 `serve()` 前已把该流定位到请求体开头），按 Content-Type 里的 boundary 手动切分
     * multipart：文件 part 用 128KiB 缓冲「读一块、写一块」流式落到临时文件，绝不整包读进内存。
     * 全程堆内存占用 O(UPLOAD_BUFFER_SIZE)，与文件大小无关——1GB 与 1MB 一样不会 OOM。
     *
     * 进度计数仍生效：session.getInputStream() 就是 [createClientHandler] 包装过的
     * [CountingInputStream]，手动读取同样累积字节数。 */
    private fun receiveAndSave(
        session: IHTTPSession,
        recordId: Long,
        busId: Long,
        displayName: String,
        relPath: String,
        category: Category
    ): Response {
        val contentLength = session.headers["content-length"]?.trim()?.toLongOrNull() ?: 0L
        val boundary = parseBoundary(headerOf(session, "content-type"))
        if (boundary == null) {
            AppLogger.w(TAG, "上传失败：请求不是 multipart/form-data（$displayName）")
            finishRecord(recordId, busId, UploadStateCode.FAILED, 0)
            return jsonError(Response.Status.BAD_REQUEST, "缺少 multipart boundary")
        }

        val parser = MultipartStreamParser(session.getInputStream(), boundary)
        var output: BufferedOutputStream? = null
        var tempFile: File? = null
        var finalName = displayName
        // 临时文件是否已「移交存储」：成功落盘（改名到分类目录）或已交解压工作区后为 true。
        // finally 只删「未移交」的临时文件 —— 成功时它已不在原临时路径，delete 是 no-op；
        // 失败 / 中断 / OOM 时它仍是半截文件，必须物理删掉。
        var consumed = false
        try {
            // 跳过开头的 `--boundary` 行
            if (!parser.open()) {
                AppLogger.w(TAG, "上传失败：multipart 起始行无效（$displayName）")
                finishRecord(recordId, busId, UploadStateCode.FAILED, 0)
                return jsonError(Response.Status.BAD_REQUEST, "multipart 格式错误")
            }

            var finished = false
            while (!finished) {
                val header = parser.readPartHeader() ?: break
                if (header.isFile) {
                    // 文件 part：文件名以 multipart 头为准（服务端可信源），缺失/伪 blob 时回退 URL query
                    if (header.fileName.isNotBlank() && header.fileName != "blob") {
                        finalName = UploadStorage.sanitizeFileName(header.fileName)
                    }
                    tempFile = newUploadTempFile(finalName)
                    output = BufferedOutputStream(FileOutputStream(tempFile), UPLOAD_BUFFER_SIZE)
                    // 边读边写：读到边界返回 true，提前 EOF（中断/取消）返回 false
                    if (!parser.streamBody(output)) throw IOException("上传中断：文件 part 未读到结束边界")
                    output.flush()
                    finished = parser.atEnd()
                } else {
                    // 非文件 part（前端只发 file，这里兜底消费掉，避免污染后续解析）
                    parser.streamBody(NullOutputStream)
                    finished = parser.atEnd()
                }
                if (!finished) parser.consumePartSeparator() // 非最终边界后有一段 CRLF，消费掉再接下一 part
            }
            output?.close()
            output = null

            // 耗尽剩余请求体（final boundary 之后可能还有尾部 CRLF）→ keep-alive 连接保持干净
            parser.drainRemaining(contentLength)

            if (tempFile == null || !tempFile.isFile || tempFile.length() == 0L) {
                // 典型成因：手机端中断后仍发出了结束请求，或临时文件中途被清掉。
                // 半截临时文件交给 finally 删除
                AppLogger.w(TAG, "上传失败：没有收到有效的 file 字段（$displayName）")
                finishRecord(recordId, busId, UploadStateCode.FAILED, 0)
                return jsonError(Response.Status.BAD_REQUEST, "缺少文件")
            }

            // 压缩包自动解压（固定行为，无设置开关）：视频 / 图片分类上传 .zip 即走
            // 「暂存 → 解压 → 按分类归位」，不把 .zip 原样落进分类目录——分类本身就是意图表达，
            // 想保留 zip 原样就选「其他」分类（「其他」的 .zip 仍然直接存 Downloads，不解压）
            if (category != Category.OTHER && finalName.endsWith(".zip", ignoreCase = true)) {
                consumed = true // 临时文件已交由 ZipExtractor 移动归位，finally 不再删
                return receiveZipAndExtract(tempFile, recordId, busId, finalName, category)
            }

            val result = storage.save(tempFile, finalName, relPath, category)
            consumed = result.isSuccess // 成功->临时已改名到分类目录；失败->仍残留待 finally 删
            val savedFile = result.getOrNull()
            finishRecord(
                recordId, busId,
                if (result.isSuccess) UploadStateCode.SUCCESS else UploadStateCode.FAILED,
                if (result.isSuccess) 100 else 0
            )
            if (result.isSuccess) {
                AppLogger.i(TAG, "上传成功：${savedFile?.path ?: finalName}")
            } else {
                // 落盘这一步把「磁盘满 / 掉盘 / 权限不足 / 跨卷复制出半截」压成同一个异常。
                // 不记堆栈就永远分不清是哪一种 —— 这是「文件传了一半消失」的唯一现场
                AppLogger.e(
                    TAG, "上传落盘失败：$finalName（${category.name}）", result.exceptionOrNull()
                )
            }
            if (savedFile != null) indexMediaAsync(savedFile)

            return if (result.isSuccess) {
                val json = JSONObject()
                    .put("status", "ok")
                    .put("filename", savedFile?.name ?: "")
                newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
            } else {
                jsonError(Response.Status.INTERNAL_ERROR, result.exceptionOrNull()?.message ?: "保存失败")
            }
        } catch (e: Exception) {
            // 网络中断 / 客户端取消 / 解析失败：记现场并收尾（临时文件的物理删除在 finally，
            // 保证连 OutOfMemoryError 这种 Error 也逃不过 finally 清理，绝不残留半截大文件）
            AppLogger.w(TAG, "上传中断：$displayName（$contentLength 字节）", e)
            finishRecord(recordId, busId, UploadStateCode.FAILED, 0)
            return jsonError(Response.Status.INTERNAL_ERROR, e.message ?: "上传中断")
        } finally {
            // 关闭写流 + 物理删除「未移交」的临时文件。finally 对抛出的**任何** Throwable
            // （含 OutOfMemoryError）都会执行——这正是「一旦 IO 异常 / OOM / 断连就删临时文件」
            // 的兜底点。成功路径下临时文件已被改名/移走，此处 delete 是 no-op。
            runCatching { output?.close() }
            if (!consumed) runCatching { tempFile?.delete() }
        }
    }

    /** 从 `Content-Type` 里取 multipart boundary（形如 `multipart/form-data; boundary=----xxx`） */
    private fun parseBoundary(contentType: String?): String? =
        contentType?.let { ct ->
            Regex("""boundary\s*=\s*"?"?([^;"\s]+)""")
                .find(ct)?.groupValues?.get(1)
        }

    /** 为本次上传建一个唯一的临时文件（与 /sdcard 同卷 → 落盘 renameTo 零拷贝） */
    private fun newUploadTempFile(name: String): File {
        val safe = name.replace(Regex("[^\\w.-]"), "_").takeLast(60)
        val dir = tempDirOf(appContext).apply { mkdirs() }
        return File(dir, "upload_${System.nanoTime()}_$safe.tmp")
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
                    AppLogger.e(TAG, "解压异常：$zipName（${category.name}）", e)
                    ZipExtractor.Outcome(
                        ZipExtractor.Kind.FAILED, emptyList(), null,
                        "解压失败：${e.message ?: "未知错误"}，请重新上传"
                    )
                }
        }

        finishRecord(recordId, busId, UploadStateCode.SUCCESS, 100)
        // 解压结果：终态 + 归位文件数 + 是否保留了原包 + **Outcome.message 原文**。
        // 带上 message 是刻意的：ZipExtractor 有 8 条失败/降级分支（工作区建不出、暂存失败、
        // 无法解析、空间不足、无目标文件、运行时预算超限、归位失败、异常兜底），它们都压进
        // Kind.FAILED / NO_SPACE / NO_TARGET 三个值里 —— 只有 message 能区分究竟是哪一种。
        // 这样 ZipExtractor 内部就**不需要**再撒一遍日志，一处打点覆盖全部原因。
        AppLogger.i(
            TAG,
            "解压结束：$zipName → ${outcome.kind.name}，归位 ${outcome.movedFiles.size} 个" +
                (if (outcome.keptZip != null) "，原包已保留在 Downloads" else "") +
                "｜${outcome.message}"
        )
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
            }.onFailure { e ->
                // 「上传成功，但媒体库里没有」的唯一线索：索引写入或时长提取失败。
                // 此前整段 runCatching 静默 —— 网页端说成功、媒体库却是空的，两边都对不上
                AppLogger.w(TAG, "上传后建媒体索引失败：${saved.name}", e)
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

    /**
     * 手动 multipart/form-data 流式解析器（v1.30，替换 NanoHTTPD `parseBody` 的内存映射方案）。
     *
     * 直接从输入流读 multipart，用 boundary 分隔；文件 part 由调用方提供输出流，这里
     * 「读一块、写一块」。为在固定大小的窗口里发现分隔符，内部保留最多 `delim-1` 字节的
     * 「悬空尾」，绝不会把整个文件缓冲进内存——堆占用 O(UPLOAD_BUFFER_SIZE)，与文件大小无关。
     *
     * 输入流即 [CountingInputStream]（见 [createClientHandler]），本类所有 `read` 都经过它，
     * 上传进度自动累计。
     */
    private class MultipartStreamParser(
        private val ins: InputStream,
        private val boundary: String
    ) {
        /** 数据段的结束标记：`\r\n--boundary`（此 CRLF 是分隔符的一部分，不属于数据） */
        private val delim: ByteArray = ("\r\n--$boundary").toByteArray(Charsets.US_ASCII)
        private val keep: Int = delim.size - 1
        private val readBuf = ByteArray(UPLOAD_BUFFER_SIZE)
        private val pending = ByteArrayOutputStream(keep + 4096)
        var consumed: Long = 0
            private set

        /** 跳过开头的 `--boundary` 行；成功返回 true */
        fun open(): Boolean {
            val first = readLine() ?: return false
            return String(first, Charsets.US_ASCII) == "--$boundary"
        }

        /** 一个 part 的头部：字段名 + 文件名 */
        data class PartHeader(val fieldName: String?, val fileName: String) {
            val isFile: Boolean get() = fieldName == "file"
        }

        /** 读一个 part 的头块（到空行为止），返回字段名 / 文件名 */
        fun readPartHeader(): PartHeader? {
            var fieldName: String? = null
            var fileName = ""
            while (true) {
                val line = readLine() ?: return null
                if (line.isEmpty()) break // 空行 = 头部结束
                val s = String(line, Charsets.US_ASCII)
                if (s.startsWith("content-disposition", ignoreCase = true)) {
                    fieldName = dispositionValue(s, "name")
                    fileName = dispositionFileName(line)
                }
            }
            return PartHeader(fieldName, fileName)
        }

        /** 流式写当前 part 的数据体到 [out]，直到遇到结束标记；读到边界返回 true，
         *  提前 EOF（客户端中断 / 取消）返回 false。绝不会把 boundary 字节写进 [out]。 */
        fun streamBody(out: OutputStream): Boolean {
            while (true) {
                val b = pending.toByteArray()
                val i = indexOfDelim(b)
                if (i >= 0) {
                    if (i > 0) out.write(b, 0, i) // 分隔符之前都是数据
                    resetFrom(i + delim.size)       // 消费分隔符，留下其后的字节
                    return true
                }
                val emit = (b.size - keep).coerceAtLeast(0)
                if (emit > 0) out.write(b, 0, emit)  // 只保留可能切开分隔符的悬空尾
                resetFrom(emit)
                if (!pull()) return false // EOF，没等来结束边界
            }
        }

        /** 刚消费的边界之后是否就是最终边界（`--boundary--` 的开头 `--`） */
        fun atEnd(): Boolean {
            val b = pending.toByteArray()
            return b.size >= 2 && b[0] == '-'.code.toByte() && b[1] == '-'.code.toByte()
        }

        /** 非最终边界后紧接一段 CRLF（boundary 行的收尾），消费掉使下一个 part 从头读起 */
        fun consumePartSeparator() {
            val b = bytes()
            var off = 0
            if (b.size > off && b[off] == '\r'.code.toByte()) off++
            if (b.size > off && b[off] == '\n'.code.toByte()) off++
            resetFrom(off)
        }

        /** 耗尽剩余请求体（final boundary 后可能还有尾部 CRLF），让 keep-alive 连接保持干净 */
        fun drainRemaining(contentLength: Long) {
            if (contentLength <= 0) return
            while (consumed < contentLength) if (!pull()) break
        }

        // ---- 底层 ----

        private fun pull(): Boolean {
            val n = ins.read(readBuf, 0, readBuf.size)
            if (n > 0) { consumed += n; pending.write(readBuf, 0, n); return true }
            return false
        }

        private fun bytes(): ByteArray = pending.toByteArray()

        private fun resetFrom(idx: Int) {
            val b = bytes()
            pending.reset()
            if (idx < b.size) pending.write(b, idx, b.size - idx)
        }

        private fun readLine(): ByteArray? {
            while (true) {
                val b = bytes()
                var nl = -1
                for (i in b.indices) if (b[i] == '\n'.code.toByte()) { nl = i; break }
                if (nl >= 0) {
                    val line = b.copyOfRange(0, nl)
                    resetFrom(nl + 1)
                    return if (line.isNotEmpty() && line.last() == '\r'.code.toByte())
                        line.copyOf(line.size - 1) else line
                }
                if (!pull()) {
                    return if (b.isEmpty()) null else { val all = b; resetFrom(all.size); all }
                }
            }
        }

        private fun indexOfDelim(b: ByteArray): Int {
            if (b.size < delim.size) return -1
            var i = 0
            while (i <= b.size - delim.size) {
                var j = 0
                while (j < delim.size && b[i + j] == delim[j]) j++
                if (j == delim.size) return i
                i++
            }
            return -1
        }

        private fun dispositionValue(s: String, target: String): String? {
            Regex("""$target\s*=\s*"([^"]*)"""").find(s)?.let { return it.groupValues[1] }
            Regex("""$target\s*=\s*([^;\s]+)""").find(s)?.let { return it.groupValues[1] }
            return null
        }

        /** 从 Content-Disposition 取 UTF-8 文件名（兼容 `filename=` 与 RFC5987 `filename*=`） */
        private fun dispositionFileName(line: ByteArray): String {
            val s = String(line, Charsets.UTF_8)
            Regex("filename\\*\\s*=\\s*[^;]*['']([^;]+)").find(s)?.let { m ->
                return runCatching { URLDecoder.decode(m.groupValues[1], "UTF-8") }
                    .getOrDefault(m.groupValues[1])
            }
            Regex("""filename\s*=\s*"([^"]*)"""").find(s)?.let { m -> return m.groupValues[1] }
            Regex("filename\\s*=\\s*([^;]+)").find(s)?.let { m -> return m.groupValues[1].trim() }
            return ""
        }
    }

    /** 丢弃型输出流：非文件 part 直接消费掉，不落盘 */
    private object NullOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
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

        private const val TAG = "TransHttpServer"

        /** 上传 I/O 缓冲（128KiB）：读缓冲 / 写缓冲共用，单次读一块写一块，内存占用与文件大小无关 */
        private const val UPLOAD_BUFFER_SIZE = 128 * 1024

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
