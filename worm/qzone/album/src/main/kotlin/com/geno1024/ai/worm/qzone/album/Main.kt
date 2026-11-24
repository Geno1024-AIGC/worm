package com.geno1024.ai.worm.qzone.album

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
import com.geno1024.ai.worm.core.qzone.defaultQzoneCredentialFile
import com.geno1024.ai.worm.core.qzone.loadQzoneCredentials
import com.geno1024.ai.worm.core.qzone.requireQzoneAuth
import kotlinx.coroutines.runBlocking
import java.io.File

private val USAGE = """
    用法: album [选项] [QQ号]

      --uin UIN            目标 QQ 号；留空则抓当前登录账号
      --album NAME         只抓名称/ID 匹配的相册（子串，忽略大小写）
      --cookie HEADER      直接传 Cookie 头（需含 p_skey 或 skey）
      --cookies-file FILE  Netscape cookies.txt
      --out DIR            输出目录，默认 build/qzone-album
      --pages N            每个相册最多 N 页（每页 100 张），默认 100
      --delay MS           请求间隔，默认 800
      -h, --help           显示本帮助

    视频只有拿到真实播放地址时才存为视频；只有封面的条目按图片保存并标记 isCover。
""".trimIndent()

fun main(args: Array<String>) {
    val cli = ArgParser(args, flagNames = setOf("help"))
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
    val subject = cli.string("uin") ?: cli.positionals.firstOrNull() ?: auth.loginUin ?: ""
    val out = File(cli.string("out", "build/qzone-album"))
    val crawler = AlbumCrawler(
        http = JdkHttpPortal(delayMs = cli.long("delay", 800)),
        cacheDir = File(out, "cache"),
        maxPagesPerAlbum = cli.int("pages", 100),
        albumFilter = cli.string("album"),
    )
    val listener = object : CrawlListener {
        override fun onProgress(progress: CrawlProgress) {
            if (progress.stage == "相册") println("[${progress.stage}] ${progress.detail}")
        }
        override fun onWarning(message: String) = println("[warn] $message")
    }

    val report = runBlocking {
        crawler.collect(
            CrawlRequest(subject),
            CrawlSession(cookies, auth.loginUin),
            DirectoryMediaSink(File(out, "media")),
            listener,
        )
    }
    JsonOutput.write(File(out, "report.json"), reportMap(report))
    println("target=${report.subject} items=${report.items.size} saved=${report.saved.size} warnings=${report.warnings.size}")
    println("report: ${File(out, "report.json")}")
}

private fun reportMap(report: CrawlReport): Map<String, Any?> = mapOf(
    "crawler" to report.crawlerId,
    "subject" to report.subject,
    "items" to report.items.map { item ->
        val asset = (item as? CollectedItem.Media)?.asset
        mapOf("kind" to asset?.kind?.name, "url" to asset?.url, "cover" to (asset?.isCover ?: false))
    },
    "saved" to report.saved.map { mapOf("url" to it.asset.url, "name" to it.name, "size" to it.size) },
    "warnings" to report.warnings,
)
