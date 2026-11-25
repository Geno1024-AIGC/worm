package com.geno1024.ai.worm.weibo

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
import kotlinx.coroutines.runBlocking
import java.io.File

private val USAGE = """
    用法: weibo [选项] <微博主页URL或数字uid>

      --cookie HEADER      直接传 Cookie 头
      --cookies-file FILE  Netscape cookies.txt
      --out DIR            输出目录，默认 build/weibo
      --pages N            最多 N 页（每页 20 条），默认 1000
      --delay MS           请求间隔，默认 800
      --include-text       同时记录正文文字
      -h, --help           显示本帮助

    微博接口在触发风控时可能返回验证码或空列表，爬虫会给出可读警告。
""".trimIndent()

fun main(args: Array<String>) {
    val cli = ArgParser(args)
    if (cli.flag("help")) {
        println(USAGE)
        return
    }
    val subject = cli.string("user") ?: cli.positionals.firstOrNull()
    if (subject.isNullOrBlank()) {
        System.err.println(USAGE)
        error("微博 uid 或主页 URL 不能为空")
    }

    val cookies = when {
        cli.string("cookie") != null -> Cookies.fromHeader(cli.string("cookie")!!)
        cli.string("cookies-file") != null -> Cookies.fromNetscape(File(cli.string("cookies-file")!!).readText())
        else -> emptyMap()
    }
    val out = File(cli.string("out", "build/weibo"))
    val mediaDir = File(out, "media")
    val sink = DirectoryMediaSink(mediaDir)
    val queue = ResumeQueue(File(out, "resume-queue.json"))
    val sinkRef = ResumeQueue.SinkRef(ResumeQueue.SinkRef.DIRECTORY, mediaDir.absolutePath)
    val http = JdkHttpPortal(delayMs = cli.long("delay", 800))
    val crawler = WeiboCrawler(http, File(out, "cache"), maxPages = cli.int("pages", 1000))
    val session = CrawlSession(cookies)

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
            session, sink, listener,
        )
    }
    JsonOutput.write(File(out, "report.json"), reportMap(report))
    println("user=${report.subject} items=${report.items.size} saved=${report.saved.size} warnings=${report.warnings.size}")
    println("report: ${File(out, "report.json")}")
}

private fun consoleListener(): CrawlListener = object : CrawlListener {
    override fun onProgress(progress: CrawlProgress) {
        if (progress.stage != "媒体") println("[${progress.stage}] ${progress.detail}")
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
