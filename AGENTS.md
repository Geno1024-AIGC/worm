# AGENTS.md — worm

上级规则见 `../AGENTS.md` 与 `../RULES/`，本文件只补充本仓库约定。

## 模块

- `:worm:core` 只放跨爬虫共享能力：接口、数据模型、HTTP、cookie、媒体落盘。
- 每个目标站/功能一个模块，放在 `worm/<name>/`，只依赖 `:worm:core`，爬虫之间互不依赖。
- 同一站点多个功能共享的解析/登录态可放在 `:worm:<site>:common` 库模块（如 `:worm:qzone:common`），由该功能下的爬虫依赖。
- 解析函数必须接收 `String` / `JSONObject` 等纯输入并返回模型，离线 fixture 单测覆盖；测试不访问网络。
- 所有实现都运行在 JVM 与 Android 上：只用 `HttpURLConnection` / `java.io` / jsoup / org.json，
  不用 `java.net.http`、不使用桌面浏览器 API。Android 专属的登录与存储经 `CrawlSession` / `MediaSink` 注入。
- 第一阶段的产物只有媒体（`CollectedItem.Media`）；文本与 HTML 导出走 `CollectedItem.Text` 与后续 exporter，
  不要在各爬虫里私造导出格式。

## 版本与打包

- 版本由 `buildSrc` 的 `Versioning` 从 Git + build-count 服务推导，五段形状，不硬编码。
- **没有 `count.pack`**：`pack` 只来自服务响应，配置期 POST，成败都算；不开启 configuration cache。
- `BUILD_COUNT_URL` / `BUILD_COUNT_TOKEN`、keystore 条目只在环境变量或 gitignored 的 `local.properties`。

## 提交

遵守 `../RULES/COMMIT.md`：一项一个 commit，格式 `<bra><action><ket> <summary>`，提交后推送 `origin/master`。
