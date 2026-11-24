# worm

爬虫与媒体收集工具集。Kotlin + Gradle Kotlin DSL；抓取逻辑放在共享 JVM 模块里，
CLI 与 Android app 使用同一份实现。

模块路径按 `:worm:*` 命名（源码位于仓库内的 `worm/` 目录，见 `settings.gradle.kts`）：

| 路径 | 说明 |
| --- | --- |
| `:worm:core` | 爬虫接口、数据模型、HTTP/存储抽象（JVM + Android 共用） |
| `:worm:qzone:common` | Qzone 共享登录态、JSONP 与媒体解析（库） |
| `:worm:qzone:shuoshuo` | QQ 空间说说 |
| `:worm:qzone:album` | QQ 空间相册 |
| `:worm:zhihu` | 知乎用户回答 / 文章 / 动态 |
| `:worm:android` | Jetpack Compose Android app |

版本、签名与提交规范遵循上级目录的 `AGENTS.md` / `RULES/`：`pack` 只来自
build-count 服务，不在这里写死，也没有 `count.pack`。
