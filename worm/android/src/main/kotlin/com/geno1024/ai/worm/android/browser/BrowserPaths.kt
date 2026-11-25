package com.geno1024.ai.worm.android.browser

/** Parent breadcrumb, or null at the root. */
fun parentFolder(folder: String): String? =
    folder.substringBeforeLast('/', "").takeIf { it != folder }

/** Append one path segment to a breadcrumb. */
fun childFolder(folder: String, name: String): String =
    if (folder.isEmpty()) name else "$folder/$name"
