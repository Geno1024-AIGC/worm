package com.geno1024.ai.worm.android.site

/** One crawler exposed in the UI. [subject] is a QQ number, Zhihu url_token, Weibo uid or Facebook id. */
enum class Source(
    val label: String,
    val site: Site,
    val subjectHint: String,
) {
    QZONE("qzone", Site.QQ, "QQ 号（留空用登录账号）"),
    QZONE_ALBUM("qzone/album", Site.QQ, "QQ 号（留空用登录账号）"),
    ZHIHU("知乎用户", Site.ZHIHU, "知乎 url_token 或主页 URL"),
    WEIBO("微博用户", Site.WEIBO, "微博数字 uid 或主页 URL"),
    FACEBOOK("Facebook 用户", Site.FACEBOOK, "用户名、数字 ID 或主页 URL"),
}
