package com.geno1024.ai.worm.qzone.shuoshuo

import com.geno1024.ai.worm.core.ArgParser
import com.geno1024.ai.worm.core.CollectedItem
import com.geno1024.ai.worm.core.Cookies
import com.geno1024.ai.worm.core.CrawlListener
import com.geno1024.ai.worm.core.CrawlProgress
import com.geno1024.ai.worm.core.CrawlReport
import com.geno1024.ai.worm.core.CrawlRequest
import com.geno1024.ai.worm.core.CrawlSession
import com.geno1024.ai.worm.core.DirectoryMediaSink
import com.geno1024.ai.worm.core.JdkHttpPortal
import com.geno1024.ai.worm.core.JsonOutput
import com.geno1024.ai.worm.core.MediaAsset
import com.geno1024.ai.worm.core.ResumeQueue
import com.geno1024.ai.worm.core.ResumeRunner
import com.geno1024.ai.worm.core.qzone.defaultQzoneCredentialFile
import com.geno1024.ai.worm.core.qzone.loadQzoneCredentials
import com.geno1024.ai.worm.core.qzone.requireQzoneAuth
import kotlinx.coroutines.runBlocking
import java.io.File

private val USAGE = """
    用法: qzone [选项] [QQ号]

      --uin UIN            目标 QQ 号；留空则抓当前登录账号
      --cookie HEADER      直接传 Cookie 头（需含 p_skey 或 skey）
      --cookies-file FILE  Netscape cookies.txt（含 #HttpOnly_ 行）
      --out DIR            输出目录，默认 build/qzone-shuoshuo
      --pages N            最多抓 N 页（每页 20 条），默认 10
      --all                抓到没有更多为止
      --delay MS           请求间隔，默认 800
      --include-text       同时记录说说正文（第一阶段默认只收媒体）
      -h, --help           显示本帮助

    未指定 Cookie 来源时读取 ${defaultQzoneCredentialFile().path}。
""".trimIndent()

fun main(args: Array<String>) {
    val cli = ArgParser(args, flagNames = setOf("all", "include-text"))
    if (cli.flag("help")) {
        println(USAGE)
        return
    }

    val cookies = when {
        cli.string("cookie") != null -> Cookies.fromHeader(cli.string("cookie")!!)
        cli.string("cookies-file") != null -> Cookies.fromNetscape(File(cli.string("cookies-file")!!).readText())
        else -> loadQzoneCredentials(defaultQzoneCredentialFile())?.cookies
            ?: error("no Qzone login: pass --cookie, --cookies-file, or save credentials first")
    }
    val auth = requireQzoneAuth(CrawlSession(cookies))
    val session = CrawlSession(cookies, auth.loginUin)
    val subject = cli.string("uin") ?: cli.positionals.firstOrNull() ?: auth.loginUin ?: ""
    val out = File(cli.string("out", "build/qzone-shuoshuo"))
    val mediaDir = File(out, "media")
    val sink = DirectoryMediaSink(mediaDir)
    val queue = ResumeQueue(File(out, "resume-queue.json"))
    val sinkRef = ResumeQueue.SinkRef(ResumeQueue.SinkRef.DIRECTORY, mediaDir.absolutePath)
    val http = JdkHttpPortal(delayMs = cli.long("delay", 800))
    val crawler = ShuoshuoCrawler(
        http = http,
        cacheDir = File(out, "cache"),
        maxPages = if (cli.flag("all")) 1000 else cli.int("pages", 10),
    )
    val resumed = runBlocking {
        val pending = queue.load() ?: return@runBlocking null
        if (pending.assets.isEmpty()) {
            queue.clear()
            return@runBlocking null
        }
        if (pending.sink.kind != ResumeQueue.SinkRef.DIRECTORY) {
            println("[warn] 续传目标类型不支持：${pending.sink.kind}")
            return@runBlocking null
        }
        // Rebuild the session from the current CLI credentials; cookies are never stored.
        ResumeRunner(
            queue = queue,
            http = http,
            session = session,
            sink = DirectoryMediaSink(File(pending.sink.root)),
            cacheDir = File(out, "cache"),
            listener = consoleListener(),
            delayMs = cli.long("delay", 800),
        ).drain().size
    }
    resumed?.let { println("resumed=$it") }

    val listener = object : CrawlListener by consoleListener() {
        override fun onDiscovered(assets: List<MediaAsset>) {
            if (assets.isNotEmpty()) {
                queue.save(ResumeQueue.Batch(crawler.id, subject, sinkRef, assets.size, assets))
            }
        }
        override fun onAssetDone(asset: MediaAsset) = queue.remove(asset)
    }

    val report = runBlocking {
        crawler.collect(
            CrawlRequest(subject, includeText = cli.flag("include-text")),
            session,
            sink,
            listener,
        )
    }
    JsonOutput.write(File(out, "report.json"), reportMap(report))
    println("target=${report.subject} items=${report.items.size} saved=${report.saved.size} warnings=${report.warnings.size}")
    println("report: ${File(out, "report.json")}")
}

private fun consoleListener(): CrawlListener = object : CrawlListener {
    override fun onProgress(progress: CrawlProgress) {
        if (progress.stage == "说说列表" || progress.stage == "续传") {
            println("[${progress.stage}] ${progress.current}${progress.total?.let { "/$it" } ?: ""} ${progress.detail}")
        }
    }
    override fun onWarning(message: String) = println("[warn] $message")
}

private fun reportMap(report: CrawlReport): Map<String, Any?> = mapOf(
    "crawler" to report.crawlerId,
    "subject" to report.subject,
    "items" to report.items.map { item ->
        when (item) {
            is CollectedItem.Media -> mapOf(
                "kind" to item.asset.kind.name,
                "url" to item.asset.url,
                "id" to item.asset.id,
                "createdAt" to item.asset.createdAt,
                "isCover" to item.asset.isCover,
            )
            is CollectedItem.Text -> mapOf(
                "kind" to "TEXT",
                "url" to item.sourceUrl,
                "title" to item.title,
                "body" to item.body,
            )
        }
    },
    "saved" to report.saved.map { mapOf("url" to it.asset.url, "name" to it.name, "size" to it.size) },
    "warnings" to report.warnings,
)
