package com.geno1024.ai.worm.android.site

/** One crawler exposed in the UI. [subject] means a QQ number or a Zhihu url_token. */
enum class Source(
    val label: String,
    val site: Site,
    val subjectHint: String,
) {
    QZONE_SHUOSHUO("QQ空间说说", Site.QQ, "QQ 号（留空用登录账号）"),
    QZONE_ALBUM("QQ空间相册", Site.QQ, "QQ 号（留空用登录账号）"),
    ZHIHU("知乎用户", Site.ZHIHU, "知乎 url_token 或主页 URL"),
}
