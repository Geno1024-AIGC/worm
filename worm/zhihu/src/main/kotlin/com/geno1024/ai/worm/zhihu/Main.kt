package com.geno1024.ai.worm.zhihu

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
import kotlinx.coroutines.runBlocking
import java.io.File

private val USAGE = """
    用法: zhihu [选项] <用户主页URL或url_token>

      --cookie HEADER      直接传 Cookie 头
      --cookies-file FILE  Netscape cookies.txt
      --answers            只抓回答
      --articles           只抓文章
      --activities         只抓动态
      --out DIR            输出目录，默认 build/zhihu
      --pages N            每类最多 N 页（每页 20 条），默认 50
      --delay MS           请求间隔，默认 800
      --include-text       同时记录正文 HTML
      -h, --help           显示本帮助

    不给类型参数时三类全抓。知乎接口常要求登录 Cookie；未登录会明确报 401/403。
""".trimIndent()

fun main(args: Array<String>) {
    val cli = ArgParser(args, flagNames = setOf("answers", "articles", "activities"))
    if (cli.flag("help")) {
        println(USAGE)
        return
    }
    val subject = cli.string("user") ?: cli.positionals.firstOrNull()
    if (subject.isNullOrBlank()) {
        System.err.println(USAGE)
        error("Zhihu user URL or url_token is required")
    }

    val cookies = when {
        cli.string("cookie") != null -> Cookies.fromHeader(cli.string("cookie")!!)
        cli.string("cookies-file") != null -> Cookies.fromNetscape(File(cli.string("cookies-file")!!).readText())
        else -> emptyMap()
    }
    val kinds = buildSet {
        if (cli.flag("answers")) add(ZhihuKind.ANSWER)
        if (cli.flag("articles")) add(ZhihuKind.ARTICLE)
        if (cli.flag("activities")) add(ZhihuKind.ACTIVITY)
    }.ifEmpty { ZhihuKind.entries.toSet() }

    val out = File(cli.string("out", "build/zhihu"))
    val crawler = ZhihuCrawler(
        http = JdkHttpPortal(delayMs = cli.long("delay", 800)),
        cacheDir = File(out, "cache"),
        kinds = kinds,
        maxPagesPerKind = cli.int("pages", 50),
    )
    val listener = object : CrawlListener {
        override fun onProgress(progress: CrawlProgress) {
            if (progress.stage != "媒体") println("[${progress.stage}] ${progress.detail}")
        }
        override fun onWarning(message: String) = println("[warn] $message")
    }

    val report = runBlocking {
        crawler.collect(
            CrawlRequest(subject, includeText = cli.flag("include-text")),
            CrawlSession(cookies),
            DirectoryMediaSink(File(out, "media")),
            listener,
        )
    }
    JsonOutput.write(File(out, "report.json"), reportMap(report))
    println("user=${report.subject} items=${report.items.size} saved=${report.saved.size} warnings=${report.warnings.size}")
    println("report: ${File(out, "report.json")}")
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
