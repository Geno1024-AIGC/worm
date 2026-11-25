package com.geno1024.ai.worm.android.ui.theme

import android.content.Context
import androidx.core.content.edit

/** How the light/dark choice is resolved; SYSTEM follows Android's setting. */
enum class ThemeMode(val id: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色");

    fun resolved(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun from(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

data class ThemeSettings(
    val preset: ThemePreset = ThemePreset.DEFAULT,
    val mode: ThemeMode = ThemeMode.SYSTEM,
)

/** Small, dependency-free store so both the app and the settings screen see one source of truth. */
class ThemePrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("worm-theme", Context.MODE_PRIVATE)

    fun snapshot(): ThemeSettings = ThemeSettings(
        preset = ThemePreset.entries.firstOrNull { it.id == prefs.getString(KEY_PRESET, null) }
            ?: ThemePreset.DEFAULT,
        mode = ThemeMode.from(prefs.getString(KEY_MODE, null)),
    )

    fun save(settings: ThemeSettings) {
        prefs.edit {
            putString(KEY_PRESET, settings.preset.id)
            putString(KEY_MODE, settings.mode.id)
        }
    }

    private companion object {
        const val KEY_PRESET = "preset"
        const val KEY_MODE = "mode"
    }
}
