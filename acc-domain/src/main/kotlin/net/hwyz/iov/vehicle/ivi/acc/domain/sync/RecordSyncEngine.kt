package net.hwyz.iov.vehicle.ivi.acc.domain.sync

import kotlinx.coroutines.delay
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.ActivationCloudPort
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.RecordReportInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.RecordReportOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.log.DiagnosticsLogger
import net.hwyz.iov.vehicle.ivi.acc.domain.model.RecordType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.retry.RetryPolicy
import net.hwyz.iov.vehicle.ivi.acc.domain.state.AgreementSyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacySyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.isRetryable
import net.hwyz.iov.vehicle.ivi.acc.domain.store.AgreementAcceptanceStore
import net.hwyz.iov.vehicle.ivi.acc.domain.store.PendingRecordStore
import net.hwyz.iov.vehicle.ivi.acc.domain.store.PrivacyDecisionStore
import net.hwyz.iov.vehicle.ivi.acc.domain.time.WallClock

/**
 * Uploads pending records with bounded backoff.
 *
 * Only retryable network/temporary categories are retried. Expired, rejected or authentication
 * failures are marked terminal so the flow prompts for recovery instead of retrying forever.
 * Acknowledged records also flip the matching local sync state, keeping "selection" and
 * "cloud acknowledged" separately observable.
 */
class RecordSyncEngine(
    private val recordStore: PendingRecordStore,
    private val cloud: ActivationCloudPort,
    private val agreementStore: AgreementAcceptanceStore,
    private val privacyStore: PrivacyDecisionStore,
    private val policy: RetryPolicy,
    private val wallClock: WallClock,
    private val logger: DiagnosticsLogger,
) {

    /** Uploads one batch of eligible records; returns how many were acknowledged. */
    suspend fun flush(limit: Int = 20): Int {
        var acknowledged = 0
        val batch = recordStore.pending(limit).filter {
            it.type == RecordType.LOGIN_RESULT || it.type == RecordType.PRIVACY_DECISION
        }
        for (record in batch) {
            if (record.nextEligibleAtEpochMillis > wallClock.nowEpochMillis()) continue
            var totalAttempts = record.attempts
            var attemptsInPass = 0
            while (attemptsInPass < policy.maxAttempts) {
                attemptsInPass++
                totalAttempts++
                when (val outcome = cloud.reportRecord(RecordReportInput(record))) {
                    RecordReportOutcome.Acknowledged -> {
                        recordStore.markAcknowledged(record.id)
                        onAcknowledged(record.id, record.type, record.scopeKey)
                        logger.event(
                            "record.acknowledged",
                            "type" to record.type.name,
                            "attempts" to totalAttempts.toString(),
                        )
                        acknowledged++
                        break
                    }

                    is RecordReportOutcome.Failed -> {
                        if (!outcome.category.isRetryable) {
                            recordStore.markFailed(record.id, totalAttempts)
                            logger.event(
                                "record.rejected",
                                "type" to record.type.name,
                                "category" to outcome.category.name,
                            )
                            break
                        }
                        // Retryable: keep the record pending with a bounded backoff so a later
                        // pass (foreground, network recovery) can try again with the same id.
                        val nextEligible = wallClock.nowEpochMillis() + policy.backoffMillis(totalAttempts)
                        recordStore.markAttempt(record.id, totalAttempts, nextEligible)
                        logger.event(
                            "record.retry",
                            "type" to record.type.name,
                            "attempts" to totalAttempts.toString(),
                            "category" to outcome.category.name,
                        )
                        if (attemptsInPass < policy.maxAttempts) {
                            delay(policy.backoffMillis(totalAttempts))
                        }
                    }
                }
            }
        }
        return acknowledged
    }

    private suspend fun onAcknowledged(id: String, type: RecordType, scopeKey: String) {
        when (type) {
            RecordType.AGREEMENT_CONSENT -> {
                val remaining = recordStore
                    .pending(policy.maxAttempts * 8)
                    .filter { it.scopeKey == scopeKey && it.type == RecordType.AGREEMENT_CONSENT }
                if (remaining.none { it.syncState == SyncState.PENDING }) {
                    agreementStore.markSync(scopeKey, AgreementSyncState.ACKNOWLEDGED)
                }
            }

            RecordType.PRIVACY_DECISION -> privacyStore.markSync(scopeKey, PrivacySyncState.ACKNOWLEDGED)

            RecordType.LOGIN_RESULT -> {
                // Agreement consent evidence rides along with the login result: acknowledge it too.
                recordStore.pending(policy.maxAttempts * 8)
                    .filter { it.scopeKey == scopeKey && it.type == RecordType.AGREEMENT_CONSENT }
                    .forEach { recordStore.markAcknowledged(it.id) }
                val remaining = recordStore
                    .pending(policy.maxAttempts * 8)
                    .filter { it.scopeKey == scopeKey && it.type == RecordType.AGREEMENT_CONSENT }
                if (remaining.none { it.syncState == SyncState.PENDING }) {
                    agreementStore.markSync(scopeKey, AgreementSyncState.ACKNOWLEDGED)
                }
            }

            RecordType.SKIP_ACTIVATION -> Unit
        }
        logger.event("record.sync_state", "type" to type.name, "id" to id)
    }
}
