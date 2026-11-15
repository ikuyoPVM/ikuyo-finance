package xyz.ikuyo.finance.core

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * App 界面语言。
 *
 * 为什么不用 DataStore：`attachBaseContext` 在 Activity 生命周期最早期同步执行，
 * DataStore 是异步的，这时读不到值。这里用 SharedPreferences 只存一个语言代码，
 * 属于同步可读的最小状态，其余设置仍走 DataStore。
 *
 * AI 回复语言是独立设置（见 AiSettings），不受这里影响。
 */
object AppLanguage {

    const val SYSTEM = "SYSTEM"
    const val CHINESE = "zh"
    const val JAPANESE = "ja"
    const val ENGLISH = "en"

    private const val PREFS = "ikuyo_language"
    private const val KEY_MODE = "language_mode"

    val all: List<String> = listOf(SYSTEM, CHINESE, JAPANESE, ENGLISH)

    fun stored(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, CHINESE)
            ?.takeIf { it in all }
            ?: CHINESE

    fun save(context: Context, mode: String) {
        val value = mode.takeIf { it in all } ?: SYSTEM
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, value)
            .apply()
    }

    /** 把语言应用到 Context；跟随系统时原样返回。 */
    fun wrap(context: Context): Context {
        val mode = stored(context)
        if (mode == SYSTEM) return context
        val locale = Locale.forLanguageTag(mode)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        return context.createConfigurationContext(config)
    }

    /** 设置项标题用的语言名（本身不翻译，方便用户在看不懂当前语言时找到自己的语言）。 */
    fun label(mode: String): String = when (mode) {
        CHINESE -> "简体中文"
        JAPANESE -> "日本語"
        ENGLISH -> "English"
        else -> "系统 / System"
    }
}