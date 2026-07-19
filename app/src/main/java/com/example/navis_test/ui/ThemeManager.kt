package com.example.navis_test.ui

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

// Chế độ sáng/tối lưu qua SharedPreferences — thuần chuyện hiển thị nên nằm ở
// tầng UI, không đi qua ViewModel/Repository.
object ThemeManager {
    private const val PREFS = "theme_prefs"
    private const val KEY_NIGHT_MODE = "night_mode"

    // Đọc chế độ đã lưu; mặc định lần đầu = theo hệ thống.
    private fun savedMode(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    // Áp chế độ đã lưu — gọi TRƯỚC super.onCreate để không bị recreate/nháy màn.
    fun applySaved(context: Context) {
        AppCompatDelegate.setDefaultNightMode(savedMode(context))
    }

    // Đảo giữa sáng và tối, lưu lại rồi áp ngay (activity tự recreate với theme mới).
    fun toggle(context: Context) {
        val isDark = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val newMode = if (isDark) {
            AppCompatDelegate.MODE_NIGHT_NO
        } else {
            AppCompatDelegate.MODE_NIGHT_YES
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_NIGHT_MODE, newMode).apply()
        AppCompatDelegate.setDefaultNightMode(newMode)
    }
}
