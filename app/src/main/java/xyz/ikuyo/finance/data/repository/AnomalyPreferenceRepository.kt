package xyz.ikuyo.finance.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import xyz.ikuyo.finance.domain.usecase.AnomalyDetector

private val Context.anomalyDataStore: DataStore<Preferences> by preferencesDataStore("ikuyo_anomaly")

/** 异常提醒的「别再提醒我」偏好：按单条标记正常，或整类忽略。 */
data class AnomalyPreference(
    val ignoredIds: Set<String> = emptySet(),
    val mutedKinds: Set<String> = emptySet(),
) {
    fun shouldShow(candidate: AnomalyDetector.Candidate): Boolean =
        candidate.id !in ignoredIds && candidate.kind.name !in mutedKinds
}

/**
 * 本地持久化，不联网。
 * 只记录「哪些提醒用户看过并认为正常」，不记录任何账单内容，删掉偏好也不会影响账单。
 */
class AnomalyPreferenceRepository(private val context: Context) {

    val preference: Flow<AnomalyPreference> = context.anomalyDataStore.data.map { prefs ->
        AnomalyPreference(
            ignoredIds = prefs[KEY_IGNORED].orEmpty(),
            mutedKinds = prefs[KEY_MUTED].orEmpty(),
        )
    }

    suspend fun markNormal(candidateId: String) {
        context.anomalyDataStore.edit { prefs ->
            prefs[KEY_IGNORED] = prefs[KEY_IGNORED].orEmpty() + candidateId
        }
    }

    suspend fun muteKind(kind: AnomalyDetector.Kind) {
        context.anomalyDataStore.edit { prefs ->
            prefs[KEY_MUTED] = prefs[KEY_MUTED].orEmpty() + kind.name
        }
    }

    /** 恢复全部提醒（设置里「重新开启异常提醒」用）。 */
    suspend fun reset() {
        context.anomalyDataStore.edit { prefs ->
            prefs.remove(KEY_IGNORED)
            prefs.remove(KEY_MUTED)
        }
    }

    companion object {
        private val KEY_IGNORED = stringSetPreferencesKey("ignored_candidates")
        private val KEY_MUTED = stringSetPreferencesKey("muted_kinds")
    }
}