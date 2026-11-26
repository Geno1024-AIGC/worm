# worm

面向 QQ 空间、知乎、微博与 Facebook 的媒体收集工具集。Kotlin + Gradle Kotlin DSL：抓取逻辑放在共享 JVM 模块里，
JVM CLI 与 Android app 使用同一份实现。第一阶段只落盘图片与视频；文字/HTML 导出留到后续 exporter。

## 模块

路径按 `:worm:*` 命名（源码位于仓库内的 `worm/` 目录，见 `settings.gradle.kts`）：

| 路径 | 说明 |
| --- | --- |
| `:worm:core` | 爬虫接口、数据模型、HTTP/存储抽象（JVM + Android 共用） |
| `:worm:qzone:common` | Qzone 共享登录态、gtk、JSONP 与媒体解析（库） |
| `:worm:qzone:shuoshuo` | QQ 空间说说（抓取源 `qzone`） |
| `:worm:qzone:album` | QQ 空间相册（抓取源 `qzone/album`） |
| `:worm:zhihu` | 知乎用户回答 / 文章 / 动态 |
| `:worm:weibo` | 微博用户公开微博（抓取源 `weibo`） |
| `:worm:facebook` | Facebook 用户照片 / 视频（抓取源 `facebook`，Graph token 或网页 Cookie） |
| `:worm:android` | Jetpack Compose Android app |

爬虫只产出 `CollectedItem.Media`（`--include-text` 会额外产出 `CollectedItem.Text` 供后续 exporter 使用）。
媒体按内容分目录落盘，而不是全部堆在一个目录：

```
<输出目录>/media/
  qzone/<QQ号>/<图片>.jpg              # qzone 图片
  qzone/<QQ号>/<视频>.mp4              # qzone 视频，与图片同目录，按扩展名区分
  qzone/<QQ号>/<相册名>/<文件>.jpg      # qzone/album，每个相册一个子目录
  zhihu/<url_token>/image/<图片>.jpg   # 知乎图片
  zhihu/<url_token>/video/<视频>.mp4   # 知乎视频
  weibo/<uid>/image/<图片>.jpg         # 微博配图与视频封面
  weibo/<uid>/video/<视频>.mp4         # 微博视频
  facebook/<id>/image/<图片>.jpg       # Facebook 照片与视频封面
  facebook/<id>/video/<视频>.mp4       # Facebook 视频
```

图片与视频的扩展名按媒体类型白名单决定，URL 里的误导性后缀不会把封面 JPG 存成 MP4。
视频只有拿到真实播放地址才存为视频；只有封面时按图片保存并标记 `isCover`。Qzone 的播放地址在
`video[].url3` / `pic[].video_info.url3`，`url1` / `pic_url` 只是封面。所有下载先写 `.part` 再原子改名，
避免把半截文件当成品；拿到媒体发表时间时，落盘后会把文件的修改时间设为该时间（Unix 秒转毫秒），
没有时间则保留下载时间。

知乎正文对同一张图会同时给出 `_r.jpg` 缩略图和 `/50/<id>_720w.jpg` 大图（`src` 与 `data-original`），
`ZhihuMedia` 按 CDN 图片 ID 归组，只保留页面里实际给出的最大变体（原图 > 1440w > 1080w > 720w > 缩略图），
不凭空拼 URL 以免 404；因此每张图只落盘一份大图。
知乎源另有「只给缩略图时尝试大图」勾选：页面只有 `_r` 时会按 `1440w → 1080w → 720w` 顺序尝试同 host/path
的变体，任一成功就用大图，全部失败自动回退原链接；默认不开启。

## JVM CLI

每个爬虫模块都是可运行应用，也可打成自包含 jar：

```bash
./gradlew :worm:qzone:shuoshuo:run --args="--help"
./gradlew :worm:qzone:shuoshuo:distFatJar
java -jar worm/qzone/shuoshuo/build/libs/*-all.jar --uin 123456 --out build/qzone-shuoshuo
```

- 说说：`--uin` / 位置参数指定目标 QQ（留空则抓登录账号），`--pages`、`--all`、`--delay`、`--out`。
- 相册：`--uin`、`--album` 过滤相册名/ID，`--pages`（每相册）、`--delay`、`--out`。登录 Cookie 必须带
  `uin`/`p_uin`，因为相册接口要用登录者 UIN 而不是目标 UIN。
- 知乎：`zhihu <主页URL或url_token>`，可加 `--answers` / `--articles` / `--activities` 限定内容类型。
- 微博：`weibo <主页URL或数字uid>`，可加 `--include-text`。微博只认数字 uid（`weibo.com/u/<uid>` 会自动提取），
  自定义域名主页需先解析出 uid。
- Facebook：`facebook <用户名、数字ID或主页URL> --token <access_token>`，可加 `--include-text`；
  `--mode auto|graph|cookie` 控制取数方式，默认 `auto`（有 token 走 Graph API，失败自动回退网页 Cookie）。
  Graph 照片取 `images[]` 中分辨率最大的 `source`，视频取 `source` 并把 `picture` 作为封面按图片落盘。

抓取分两阶段：先翻完列表并记下全部媒体，再批量下载；下载阶段进度为确定的 `i/n`。
发现列表后会在输出目录写 `resume-queue.json`，每完成一个媒体就从队列移除。进程被中断后，
下次用同一个 `--out` 运行会先自动续传未完成的部分，再按本次参数执行新抓取。
队列只存媒体描述和目标目录，不存 Cookie；登录态仍由本次的 `--cookie` / `--cookies-file` /
默认凭据提供。

登录 Cookie 三种来源（按顺序选一个）：

1. `--cookie "p_skey=...; skey=...; uin=..."` 直接传 Cookie 头。
2. `--cookies-file cookies.txt` 读 Netscape cookies.txt（QQ 的 `#HttpOnly_` 行也支持）。
3. Qzone 默认读 `~/.config/worm/qzone/credentials.json`：

```json
{
  "loginUin": "123456",
  "savedAt": "2026-01-01T00:00:00Z",
  "cookies": { "p_skey": "...", "skey": "...", "uin": "o123456" }
}
```

Qzone 需要 `p_skey` 或 `skey`。知乎 CLI 可不带 Cookie 运行，但接口通常返回 401/403，此时警告会写进
`report.json` 与标准输出；当前不实现 `x-zse-96` 签名。解析失败、登录失效、下载失败都不会被静默当成
「没有内容」。

微博走 `m.weibo.cn` 的 `container/getIndex` 接口，Cookie 里的 `SUB` / `SUBP` 决定能看多少内容。`created_at`
混用绝对时间、相对时间与时间戳，`WeiboApi.parseWeiboTime` 统一成 Unix 秒；图片取 `pics[].large.url`，
视频取 `page_info.media_info.stream_url_hd`，`page_pic` 作为封面按图片落盘。接口 `ok=0` 或返回验证码页会
给出明确警告，不会当成「没有微博」。

Facebook 首选 Graph API：`--token`（或 Cookie 里的 `access_token`）提供用户/页面 access token，
`photos` / `videos` 两条边各自按 `paging.cursors.after` 翻页；token 过期或接口报错时，`auto` 模式清空
这次 Graph 的部分结果，改用 `m.facebook.com/<id>/photos` 页面里的 CDN 媒体和「下一页」链接继续爬，并在
警告里说明回退原因。`--mode graph` / `--mode cookie` 可锁定单条路径，不会静默退化；网页路径是
best-effort，页面改版可能失效，遇到登录页会明确报「Cookie 已失效」而不是「没有照片」。

Qzone 相册接口的 `hostUin` 是相册主人（抓取目标），`uin` 必须是登录者自己；两者写成同一个值会让
非本人相册一直返回非零 code。相册列表用 `mode=3` 加 `pageStart/pageNum` 翻页（否则只给最近的相册），
照片列表按 `totalInPage`/`nextPageStart` 翻页并带 `Referer`/`Origin`。`-3000`/`-4002` 才是登录态失效，
`-4009`/`-10805` 只表示没有权限访问该空间或相册，会作为警告而不是「登录有问题」上报。

## Android app

Compose Material 3 单 Activity 应用，爬虫通过 `Crawler` 接口直接复用，没有第二条解析实现。

1. 选择抓取源（`facebook` / `qzone` / `qzone/album` / `weibo` / `zhihu`，抽屉里按字母序排列），
   点对应「登录 …」在 WebView 里登录；
   Cookie 由 `CookieManager` 采集，退出登录页即刷新界面上的登录态。知乎的 `z_c0` 常在扫码后的
   页面内异步写入、不会再触发导航回调，所以登录页除了 WebView 回调外还每秒轮询一次 Cookie，
   识别到登录态后自动返回。知乎直接进 `https://www.zhihu.com/signin`，并保留设备真实移动 UA、
   只去掉 `; wv` 与 `Version/4.0` 这两个 WebView 标记——知乎靠它们识别内嵌浏览器，命中后会把登录表单
   换成「打开 App」页；伪装成普通手机 Chrome 才能拿到真正的登录页。桌面 UA 在这个 WebView 里会渲染成
   空白页，所以不用。WebView 内只拦 `zhihu://` 深链，其余 scheme 放行以免影响页面脚本。
   若仍登录不了，登录页右上角有「手动输入」兜底：从任意已登录的浏览器复制完整 Cookie 请求头粘贴进来
   （按 `Domain=.zhihu.com` / `.qq.com` / `.weibo.cn` / `.facebook.com` 写回 `CookieManager`），
   保存后立即识别登录态并返回。Facebook 推荐在这里填 `access_token=<token>` 走 Graph API；
   网页 `xs` Cookie 只能作为回退。微博 WebView 走 `passport.weibo.cn` 的手机登录页，`SUB` / `SUBP`
   决定可见内容。
2. 填目标（QQ 号、知乎 `url_token`、微博数字 uid 或 Facebook 用户名 / 数字 ID / 主页 URL）。app 里没有页数上限旋钮：全量抓取一直翻到接口
   返回「没有更多」（内部防跑飞上限 1000 页）；只有存在该目标的上次成功记录时才会按时间戳提前停。
   相册源另有「相册名过滤」，留空抓所有可访问相册。Qzone 接口会返回
   `total` / `albumsInUser` / `totalInAlbum`，列表阶段据此显示总页数；知乎只给 `next`，没有总数。
   服务端不支持按时间过滤：说说用 `pos`/`num`、相册用 `pageStart`/`pageNum` 翻页，停止条件由
   「没有更多」或增量水位线决定。JVM CLI 仍保留 `--pages` 参数供调试截断。
3. 可选「选择保存目录」用 SAF 授权一个目录；未选择时写到应用私有目录 `filesDir/worm-media`。
   SAF 目录授权会被持久化，爬虫按上面的 `qzone/<QQ号>/…`、`zhihu/<token>/…`、
   `weibo/<uid>/…`、`facebook/<id>/…` 结构写入。
4. 点「开始抓取」，先把列表翻完再批量下载：翻页时每发现 20 个媒体就写一次续传队列，下载阶段显示
   「媒体 i/n」确定进度条，
   最后给出警告与保存统计。抓取跑在 `Dispatchers.IO`，不阻塞主线程。
   每次成功抓取会按 `crawlerId + 归一化目标` 把时间戳写到 app-private 的 `worm/crawl-journal.json`；
   没有记录时爬全部；有记录时取 `上次成功时间` 与接口报告的最新内容时间中较早者，减 6 小时作为水位，
   遇到整页内容都早于该水位就停止翻页。journal 另存最近 300 个已见条目 ID，重叠区里的旧条目不会重复下载；
   增量抓取时列表进度会标注「增量」，命中水位时进度直接显示「命中上次抓取时间，提前结束」，警告里还会
   说明停在第几页，以及接口报告的总页数只是估算。
   勾选「强制爬全部」可忽略时间戳重翻所有页；已存在的文件仍然跳过（需要重新下载请先删除）。
   切换抓取源会清空目标、相册过滤和上次结果，恢复默认值。
5. 抽屉里的「浏览」按 `qzone/<QQ号>/<相册名>/…`、`zhihu/<token>/…`、`weibo/<uid>/…`、
   `facebook/<id>/…` 目录浏览已保存的媒体：
   每个媒体缩略图下方显示文件时间，图片预览左上角显示到秒的完整时间；时间取文件修改时间，爬虫
   拿到发布时间时会写入该时间，否则是下载时间。部分 SAF provider 不允许改 mtime，这时时间会从
   app-private 的 `worm/media-times.json` 读取（抓取时同步记录，存的是 Unix 秒，浏览器读取时转成毫秒，
   否则会显示成 1970 年）；相册文件夹按内部最新媒体时间从新到旧
   排序并显示「最新：…」，
   目录内媒体同样按时间从新到旧排序，时间未知的排在后面。
   图片在应用内点开可缩放预览，视频通过 `FileProvider` 交给系统播放器；读取范围跟随当前保存目录
   （SAF 树或应用私有目录），只看本地文件，不访问网络。解码优先走 `ImageDecoder`（API 28+，
   强制软件位图以兼容 Compose），API 26/27 回退到重新打开流 + `BufferedInputStream` 的
   `BitmapFactory`；大图和 SAF 流不再因为两遍解码复用同一个不可回退的流而失败。
   缩略图（200 张）和大图预览（4 张）分别走有界 LRU 缓存，滑回目录不会反复解码。
   预览失败会把真实原因（例如「文件内容是 SVG/HTML 文本」）显示在界面上，不再只有一句「图片无法解码」。
   下载落盘前还会拦截以 `<` / `{` 开头的错误页，避免把 SVG/HTML/JSON 存成 `.jpg`。
6. 抽屉里的「设置」集中放外观、更新和下载源：明暗模式可选「跟随系统 / 浅色 / 深色」，配色提供
   16 套明/暗预设（移植自 opencode-inspire），选择后立即生效并写入 app-private 偏好。
系统返回键与顶栏箭头走同一条回退链：抽屉 → 图片预览 → 上层文件夹 → 浏览/设置 → 主界面；
只有停在主界面时返回才退出应用。

抓取用的 HTTP UA 是普通手机/桌面 Chrome 字符串，不带 `worm` / `worm-android` 之类的脚本标记。
知乎接口在触发风控时可能用 HTTP 200 返回验证码 SVG（而不是本该返回的 JSON），之前会让 `org.json`
抛出 `illegal character at 24` 这类原始错误。现在所有爬虫响应解析都走 `Jsonp.objectOrNull()`：
非 JSON 响应（SVG / XML / HTML / 空响应）不再抛原始异常，而是给出「收到 SVG 页面，通常是验证码或
风控拦截，请重新登录或稍后再试」之类可读警告，且不会中断整次抓取。
网络层会读满 `Content-Length`，短响应和读超时会重试；知乎列表解析再兜一层：即使接口用 chunked
导致半截 JSON 溜过网络层，也会自动重取一次。仍失败才明确报「响应被截断」，而不是笼统的
「不是合法 JSON」。

知乎 API 目前还带反爬签名要求：缺登录态或触发风控时 `api/v4` 会返回 HTTP 403 + `40353/unhuman`，
爬虫会明确提示「需要登录或触发风控」，不会假装抓到空结果。这类拦截需要在网页端完成验证后重试；
单纯换 UA 解决不了，也不要把这类响应当空列表吞掉。

正文 HTML 里的 `data:image/svg+xml;utf8,<svg …>` 是知乎的懒加载占位图（宽高随原图变化），不是可下载的
媒体。`HtmlMedia` 只接受 http/https 且能被 `URI` 解析的链接，跳过 `data:` / `blob:` 等内联 scheme；
畸形链接也不会再以 `Illegal character in opaque part at index 24` 中断整次抓取。

启动图标是矢量自适应图标（含单色 themed 变体），抽屉、登录、目录、抓取与设置按钮用 Material
Icons；两者都不依赖外部图片资源。

发现列表后队列写到 `filesDir/worm/resume-queue.json`，每完成一个媒体就更新一次。app 重启时若发现未完成
队列，会先按队列记录的抓取源和保存目录续传（进度从已完成数继续），再允许开始新抓取；若登录态缺失或
SAF 授权被撤销，队列会原样保留并在界面提示原因，重新登录/授权后重启即可继续。队列不保存 Cookie。

Android 与 CLI 的登录态是两套：app 的 Cookie 留在 app-private 的 WebView CookieJar 里，不会自动写入
CLI 的 `credentials.json`。首次安装需要分别登录。

### 应用内更新

侧边抽屉「设置」里的「应用更新」区块：进入设置时检查，30 分钟内不重复检查，也可手动点「检查更新」。
元数据只从 `api.github.com/repos/Geno1024-AIGC/worm/releases` 读取，只认带 APK 的
`prerelease=true` 滚动 canary；镜像只负责传 APK，绝不代理 API 响应。版本按
`<major>.<minor>.<env>.<pack>.<sha8>` 解析（base 不写死），排序只比 `env`：相等即同一构建不提示，
解析失败明确报「版本号无法识别」。已安装版本从 `PackageManager` 读；已忽略的版本仍可手动安装。

下载源可选 GitHub / ghfast.top / gh-proxy.com / ghproxy.net，也可填自定义 prefix；选中的源优先，
失败再依次回退其余源。APK 落到 `cacheDir/update/`，先写 `.part`、校验长度与 ZIP 魔数后原子改名，asset 名走白名单。
改完名还会用 `PackageManager.getPackageArchiveInfo()` 校验：文件能被系统解析、版本戳与本条 release 完全一致、
同包名的已安装应用签名一致。任何一步失败都当作该下载源失败并自动换下一个，避免把镜像返回的网页或损坏文件
交给系统安装器、只报一句「解析包时出现问题」。

安装交接本身也修过：同一个 APK 从文件管理器能装、应用内调起安装器却报「解析包时出现问题」，
通常是部分 OEM 安装器读不了第三方 `FileProvider` 的 content Uri。Android 10+ 先把 APK 以
`IS_PENDING` 写进 `MediaStore.Downloads/Download/worm/`，写完再置为可见，用系统自己的 content Uri
交给安装器；更老的系统或 MediaStore 不可用时回退到 `FileProvider`（authority `<applicationId>.updates`）。
两种 Uri 都带 `FLAG_GRANT_READ_URI_PERMISSION` 和同 URI 的 `ClipData`，兼容从 ClipData 取授权的安装器。
需要「安装未知来源应用」权限，并区分「设备上没有能打开 APK 的应用」与「安装器已返回结果」两种结局。

## 构建、版本与 CI

```bash
./gradlew test                       # 全部离线单测，不访问网络、不触发计数
./gradlew :worm:android:compileDebugKotlin
./gradlew :worm:android:lintRelease
```

打包任务（`:worm:android:assembleRelease`、`:worm:*:distFatJar` 等）会在配置期 POST build-count 服务，
因此本地打包需要 `BUILD_COUNT_URL` 与 `BUILD_COUNT_TOKEN`（环境变量或 gitignored 的 `local.properties`）；
没有 token 会直接失败而不是偷偷跳过计数。版本形状固定为 `<major>.<minor>.<env>.<pack>.<sha8>`：
`env` 是提交数且唯一参与排序，`pack` 只来自服务响应，**没有 `count.pack`**，也不开启 configuration cache。
Android `versionCode = env`。本地网络层连不上时计数写入 `.gradle/build-count/` 待补报并在下次成功后逐条重放；
CI 不做离线兜底。

Release 签名按 `RULES/KEYSTORE.md` 从环境变量或 `local.properties` 读取，仓库不含 keystore、密码或 token。
`.github/workflows/build.yml` 在 push 到 master 时：全量测试 + lint → 物化 org keystore 并用注入的
BUILD_COUNT 凭据签出 APK → `apksigner verify` → 从 APK 读版本名/版本码 → 发布滚动 `canary` pre-release。
发布元数据只从产物读，不在 workflow 里重算版本号。

版本、签名与提交规范遵循上级目录的 `AGENTS.md` / `RULES/`；提交格式为 `<bra><action><ket> <summary>`。
