# worm

面向 QQ 空间与知乎的媒体收集工具集。Kotlin + Gradle Kotlin DSL：抓取逻辑放在共享 JVM 模块里，
JVM CLI 与 Android app 使用同一份实现。第一阶段只落盘图片与视频；文字/HTML 导出留到后续 exporter。

## 模块

路径按 `:worm:*` 命名（源码位于仓库内的 `worm/` 目录，见 `settings.gradle.kts`）：

| 路径 | 说明 |
| --- | --- |
| `:worm:core` | 爬虫接口、数据模型、HTTP/存储抽象（JVM + Android 共用） |
| `:worm:qzone:common` | Qzone 共享登录态、gtk、JSONP 与媒体解析（库） |
| `:worm:qzone:shuoshuo` | QQ 空间说说 |
| `:worm:qzone:album` | QQ 空间相册 |
| `:worm:zhihu` | 知乎用户回答 / 文章 / 动态 |
| `:worm:android` | Jetpack Compose Android app |

爬虫只产出 `CollectedItem.Media`（`--include-text` 会额外产出 `CollectedItem.Text` 供后续 exporter 使用）。
视频只有拿到真实播放地址才存到 `videos/`；只有封面时按图片存到 `images/` 并标记 `isCover`。
所有下载先写 `.part` 再原子改名，避免把半截文件当成品。

## JVM CLI

每个爬虫模块都是可运行应用，也可打成自包含 jar：

```bash
./gradlew :worm:qzone:shuoshuo:run --args="--help"
./gradlew :worm:qzone:shuoshuo:distFatJar
java -jar worm/qzone/shuoshuo/build/libs/*-all.jar --uin 123456 --out build/qzone-shuoshuo
```

- 说说：`--uin` / 位置参数指定目标 QQ（留空则抓登录账号），`--pages`、`--all`、`--delay`、`--out`。
- 相册：`--uin`、`--album` 过滤相册名/ID，`--pages`（每相册）、`--delay`、`--out`。
- 知乎：`zhihu <主页URL或url_token>`，可加 `--answers` / `--articles` / `--activities` 限定内容类型。

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

## Android app

Compose Material 3 单 Activity 应用，爬虫通过 `Crawler` 接口直接复用，没有第二条解析实现。

1. 选择抓取源（说说 / 相册 / 知乎用户），点「登录 QQ / 知乎」在 WebView 里登录；
   Cookie 由 `CookieManager` 采集，退出登录页即刷新界面上的登录态。
2. 填目标（QQ 号或知乎 `url_token` / 主页 URL）与最大条数。
3. 可选「选择保存目录」用 SAF 授权一个目录；未选择时写到应用私有目录 `filesDir/worm-media`。
   SAF 目录授权会被持久化，爬虫写入时按 `images/` / `videos/` 分子目录。
4. 点「开始抓取」，界面显示阶段进度、警告与保存统计。抓取跑在 `Dispatchers.IO`，不阻塞主线程。

Android 与 CLI 的登录态是两套：app 的 Cookie 留在 app-private 的 WebView CookieJar 里，不会自动写入
CLI 的 `credentials.json`。首次安装需要分别登录。

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
