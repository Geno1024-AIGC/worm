package com.geno1024.ai.worm.android.update

import android.content.Context
import androidx.core.content.edit

/** Only choices and notices are persisted; the installed version always comes from PackageManager. */
class UpdatePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("worm-update", Context.MODE_PRIVATE)

    var sourceId: String
        get() = prefs.getString(KEY_SOURCE, Updater.PRESETS.first().id) ?: Updater.PRESETS.first().id
        set(value) = prefs.edit { putString(KEY_SOURCE, value) }

    var customPrefix: String
        get() = prefs.getString(KEY_CUSTOM_PREFIX, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_CUSTOM_PREFIX, value.trim()) }

    var dismissedVersion: String
        get() = prefs.getString(KEY_DISMISSED, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_DISMISSED, value) }

    var lastCheckedAt: Long
        get() = prefs.getLong(KEY_LAST_CHECKED, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_CHECKED, value) }

    private companion object {
        const val KEY_SOURCE = "source"
        const val KEY_CUSTOM_PREFIX = "customPrefix"
        const val KEY_DISMISSED = "dismissedVersion"
        const val KEY_LAST_CHECKED = "lastCheckedAt"
    }
}
