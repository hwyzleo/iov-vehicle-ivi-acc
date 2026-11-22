package net.hwyz.iov.vehicle.ivi.acc.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.hwyz.iov.vehicle.ivi.acc.data.local.dto.AgreementAcceptanceDto
import net.hwyz.iov.vehicle.ivi.acc.data.local.dto.FlowContextDto
import net.hwyz.iov.vehicle.ivi.acc.data.local.dto.PendingRecordDto
import net.hwyz.iov.vehicle.ivi.acc.data.local.dto.PrivacyDecisionDto
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementAcceptance
import net.hwyz.iov.vehicle.ivi.acc.domain.model.PendingRecord
import net.hwyz.iov.vehicle.ivi.acc.domain.model.PrivacyDecision
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.AgreementSyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacySyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.store.AgreementAcceptanceStore
import net.hwyz.iov.vehicle.ivi.acc.domain.store.FlowContext
import net.hwyz.iov.vehicle.ivi.acc.domain.store.LocalFlowStore
import net.hwyz.iov.vehicle.ivi.acc.domain.store.PendingRecordStore
import net.hwyz.iov.vehicle.ivi.acc.domain.store.PrivacyDecisionStore

/**
 * Lightweight local persistence for the activation flow.
 *
 * All four record kinds live in one preferences DataStore on purpose: a privacy decision, its
 * pending upload record and the first interaction flag then fit into a single atomic `edit`
 * transaction (design D-006 / D-007).
 */
class ActivationLocalStore(
    private val dataStore: DataStore<Preferences>,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : LocalFlowStore, AgreementAcceptanceStore, PrivacyDecisionStore, PendingRecordStore {

    // ------------------------------------------------------------------ flow context

    override suspend fun save(context: FlowContext) {
        dataStore.edit { preferences ->
            preferences[Keys.FLOW] = json.encodeToString(FlowContextDto.from(context))
        }
    }

    override suspend fun loadContext(): FlowContext? {
        val raw = dataStore.data.first()[Keys.FLOW] ?: return null
        return runCatching { json.decodeFromString<FlowContextDto>(raw).toDomain() }.getOrNull()
    }

    override suspend fun clear() {
        dataStore.edit { preferences -> preferences.remove(Keys.FLOW) }
    }

    // ------------------------------------------------------------------ agreement acceptances

    override suspend fun saveAll(records: List<AgreementAcceptance>) {
        if (records.isEmpty()) return
        dataStore.edit { preferences ->
            records.forEach { record ->
                preferences[Keys.agreement(record.scopeKey, record.type.name)] =
                    json.encodeToString(AgreementAcceptanceDto.from(record))
            }
        }
    }

    override suspend fun loadAcceptances(scopeKey: String): List<AgreementAcceptance> {
        val prefix = Keys.agreementPrefix(scopeKey)
        return dataStore.data.first().asMap()
            .filterKeys { it.name.startsWith(prefix) }
            .values
            .mapNotNull { value -> (value as? String) }
            .mapNotNull { raw ->
                runCatching { json.decodeFromString<AgreementAcceptanceDto>(raw).toDomain() }.getOrNull()
            }
            .sortedBy { it.type.name }
    }

    override suspend fun markSync(scopeKey: String, sync: AgreementSyncState) {
        val prefix = Keys.agreementPrefix(scopeKey)
        dataStore.edit { preferences ->
            val namesToUpdate = preferences.asMap().keys
                .map { it.name }
                .filter { it.startsWith(prefix) }
            namesToUpdate.forEach { name ->
                val key = stringPreferencesKey(name)
                val raw = preferences[key] ?: return@forEach
                val dto = runCatching {
                    json.decodeFromString<AgreementAcceptanceDto>(raw)
                }.getOrNull() ?: return@forEach
                preferences[key] = json.encodeToString(dto.copy(syncState = sync.name))
            }
        }
    }

    // ------------------------------------------------------------------ privacy decision

    override suspend fun loadDecision(scopeKey: String): PrivacyDecision? {
        val raw = dataStore.data.first()[Keys.privacy(scopeKey)] ?: return null
        return runCatching {
            json.decodeFromString<PrivacyDecisionDto>(raw).toDomain()
        }.getOrNull()
    }

    override suspend fun isFirstInteractionCompleted(scopeKey: String): Boolean =
        loadDecision(scopeKey)?.firstInteractionCompleted == true

    override suspend fun commitDecision(decision: PrivacyDecision, record: PendingRecord): Boolean =
        runCatching {
            dataStore.edit { preferences ->
                preferences[Keys.privacy(decision.scopeKey)] =
                    json.encodeToString(PrivacyDecisionDto.from(decision))
                preferences[Keys.record(record.id)] =
                    json.encodeToString(PendingRecordDto.from(record))
            }
        }.isSuccess

    override suspend fun markSync(scopeKey: String, sync: PrivacySyncState) {
        dataStore.edit { preferences ->
            val raw = preferences[Keys.privacy(scopeKey)] ?: return@edit
            val dto = runCatching {
                json.decodeFromString<PrivacyDecisionDto>(raw)
            }.getOrNull() ?: return@edit
            preferences[Keys.privacy(scopeKey)] =
                json.encodeToString(dto.copy(syncState = sync.name))
        }
    }

    // ------------------------------------------------------------------ pending records

    override suspend fun enqueue(record: PendingRecord) {
        dataStore.edit { preferences ->
            val key = Keys.record(record.id)
            val existing = preferences[key]
            if (existing == null) {
                preferences[key] = json.encodeToString(PendingRecordDto.from(record))
            }
        }
    }

    override suspend fun pending(limit: Int): List<PendingRecord> {
        val prefix = Keys.RECORD_PREFIX
        return dataStore.data.first().asMap()
            .filterKeys { it.name.startsWith(prefix) }
            .values
            .mapNotNull { value -> (value as? String) }
            .mapNotNull { raw ->
                runCatching {
                    json.decodeFromString<PendingRecordDto>(raw).toDomain()
                }.getOrNull()
            }
            .filter { it.syncState == SyncState.PENDING }
            .sortedBy { it.createdAtEpochMillis }
            .take(limit)
    }

    override suspend fun markAttempt(id: String, attempts: Int, nextEligibleAtEpochMillis: Long) {
        updateRecord(id) {
            it.copy(attempts = attempts, nextEligibleAtEpochMillis = nextEligibleAtEpochMillis)
        }
    }

    override suspend fun markAcknowledged(id: String) {
        updateRecord(id) { it.copy(syncState = SyncState.ACKNOWLEDGED) }
    }

    override suspend fun markFailed(id: String, attempts: Int) {
        updateRecord(id) { it.copy(syncState = SyncState.FAILED_TERMINAL, attempts = attempts) }
    }

    override suspend fun clearAll() {
        dataStore.edit { preferences ->
            val keys = preferences.asMap().keys.filter { it.name.startsWith(Keys.RECORD_PREFIX) }
            keys.forEach { preferences.remove(it) }
        }
    }

    private suspend fun updateRecord(id: String, transform: (PendingRecord) -> PendingRecord) {
        dataStore.edit { preferences ->
            val key = Keys.record(id)
            val raw = preferences[key] ?: return@edit
            val dto = runCatching {
                json.decodeFromString<PendingRecordDto>(raw)
            }.getOrNull() ?: return@edit
            preferences[key] = json.encodeToString(PendingRecordDto.from(transform(dto.toDomain())))
        }
    }

    private object Keys {
        const val RECORD_PREFIX = "record."
        val FLOW = stringPreferencesKey("flow_context")

        fun agreementPrefix(scopeKey: String): String = "agreement.$scopeKey."

        fun agreement(scopeKey: String, type: String): Preferences.Key<String> =
            stringPreferencesKey("${agreementPrefix(scopeKey)}$type")

        fun privacy(scopeKey: String): Preferences.Key<String> =
            stringPreferencesKey("privacy.$scopeKey")

        fun record(id: String): Preferences.Key<String> = stringPreferencesKey("$RECORD_PREFIX$id")
    }
}
