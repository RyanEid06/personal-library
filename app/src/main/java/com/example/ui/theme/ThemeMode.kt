package com.example.ui.theme

enum class ThemeMode(val label: String) {
    LIGHT("Light"), DARK("Dark"), SYSTEM("System");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        LIGHT -> false
        DARK -> true
        SYSTEM -> systemDark
    }

    companion object {
        fun fromStored(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}
