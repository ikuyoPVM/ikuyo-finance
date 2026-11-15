package xyz.ikuyo.finance.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import xyz.ikuyo.finance.domain.model.HomeLayout
import xyz.ikuyo.finance.domain.model.HomeModule

private val Context.homeLayoutDataStore: DataStore<Preferences> by preferencesDataStore("ikuyo_home_layout")

/** 首页布局偏好：模块顺序、隐藏、折叠。全部本地持久化，重装前不会丢。 */
class HomeLayoutRepository(private val context: Context) {

    val layout: Flow<HomeLayout> = context.homeLayoutDataStore.data.map { prefs ->
        HomeLayout(
            order = HomeModule.normalize(prefs[KEY_ORDER]?.split(",").orEmpty()),
            hidden = prefs[KEY_HIDDEN].orEmpty().mapNotNull { HomeModule.fromKey(it) }.toSet(),
            collapsed = prefs[KEY_COLLAPSED].orEmpty().mapNotNull { HomeModule.fromKey(it) }.toSet(),
        )
    }

    suspend fun update(layout: HomeLayout) {
        context.homeLayoutDataStore.edit { prefs ->
            prefs[KEY_ORDER] = layout.order.joinToString(",") { it.key }
            prefs[KEY_HIDDEN] = layout.hidden.map { it.key }.toSet()
            prefs[KEY_COLLAPSED] = layout.collapsed.map { it.key }.toSet()
        }
    }

    /** 恢复默认顺序与显隐，只保留用户自定义的内容被清空。 */
    suspend fun reset() {
        context.homeLayoutDataStore.edit { prefs ->
            prefs.remove(KEY_ORDER)
            prefs.remove(KEY_HIDDEN)
            prefs.remove(KEY_COLLAPSED)
        }
    }

    companion object {
        private val KEY_ORDER = stringPreferencesKey("module_order")
        private val KEY_HIDDEN = stringSetPreferencesKey("hidden_modules")
        private val KEY_COLLAPSED = stringSetPreferencesKey("collapsed_modules")
    }
}