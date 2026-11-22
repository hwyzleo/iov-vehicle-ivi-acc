package net.hwyz.iov.vehicle.ivi.acc.domain.model

import net.hwyz.iov.vehicle.ivi.acc.domain.state.AgreementSyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacyChoice
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacySyncState
import java.security.MessageDigest

/** Minimal local record kinds defined by design D-007. */
enum class RecordType {
    AGREEMENT_CONSENT,
    PRIVACY_DECISION,
    LOGIN_RESULT,
    SKIP_ACTIVATION,
}

enum class SyncState {
    PENDING,
    ACKNOWLEDGED,
    FAILED_TERMINAL,
}

/**
 * Minimal payload of an uploadable record.
 *
 * Field names follow the local record contract (agreement type, displayed version, user action
 * time, scope), not the cloud transport schema, which is still unconfirmed.
 */
sealed interface RecordPayload {
    val canonical: String

    data class AgreementConsent(
        val vin: String,
        val accountId: String?,
        val agreements: List<AgreementReference>,
        val sessionId: String?,
        val bindingOperationId: String?,
        val agreedAtEpochMillis: Long,
    ) : RecordPayload {
        override val canonical: String
            get() = buildString {
                append("type=AGREEMENT_CONSENT;vin=").append(vin)
                append(";account=").append(accountId ?: "-")
                append(";session=").append(sessionId ?: "-")
                append(";binding=").append(bindingOperationId ?: "-")
                append(";agreedAt=").append(agreedAtEpochMillis)
                agreements.sortedBy { it.type.name }.forEach { ref ->
                    append(";doc=").append(ref.type.name)
                    append(':').append(ref.version)
                    append(':').append(ref.revisionId ?: "-")
                    append(':').append(ref.contentHash ?: "-")
                    append(':').append(ref.language ?: "-")
                }
            }
    }

    data class PrivacyDecisionRecord(
        val vin: String,
        val accountId: String,
        val choice: PrivacyChoice,
        val items: List<String>,
        val decidedAtEpochMillis: Long,
    ) : RecordPayload {
        override val canonical: String
            get() = "type=PRIVACY_DECISION;vin=$vin;account=$accountId;choice=${choice.name}" +
                ";items=${items.sorted().joinToString(",")};decidedAt=$decidedAtEpochMillis"
    }

    data class LoginResult(
        val vin: String,
        val accountId: String,
        val sessionId: String,
        val bindingOperationId: String?,
        val loggedInAtEpochMillis: Long,
        val loginStatus: String = "SUCCEEDED",
        val agreements: List<AgreementReference> = emptyList(),
    ) : RecordPayload {
        override val canonical: String
            get() = buildString {
                append("type=LOGIN_RESULT;vin=").append(vin)
                append(";account=").append(accountId)
                append(";session=").append(sessionId)
                append(";binding=").append(bindingOperationId ?: "-")
                append(";status=").append(loginStatus)
                append(";loggedInAt=").append(loggedInAtEpochMillis)
                agreements.sortedBy { it.type.name }.forEach { ref ->
                    append(";doc=").append(ref.type.name)
                    append(':').append(ref.version)
                    append(':').append(ref.revisionId ?: "-")
                }
            }
    }

    data class SkipActivation(
        val vin: String,
        val agreements: List<AgreementReference>,
        val sessionId: String?,
        val requestedAtEpochMillis: Long,
    ) : RecordPayload {
        override val canonical: String
            get() = buildString {
                append("type=SKIP_ACTIVATION;vin=").append(vin)
                append(";session=").append(sessionId ?: "-")
                append(";requestedAt=").append(requestedAtEpochMillis)
                agreements.sortedBy { it.type.name }.forEach { ref ->
                    append(";doc=").append(ref.type.name)
                    append(':').append(ref.version)
                    append(':').append(ref.revisionId ?: "-")
                }
            }
    }
}

/**
 * Pending upload record.
 *
 * [id] is the stable dedupe id: retries reuse it, so a repeated report cannot create a second
 * business fact. [payloadDigest] lets the cloud detect "same id, different content" conflicts.
 */
data class PendingRecord(
    val id: String,
    val type: RecordType,
    val scopeKey: String,
    val payload: RecordPayload,
    val payloadDigest: String,
    val createdAtEpochMillis: Long,
    val userActionAtEpochMillis: Long,
    val attempts: Int = 0,
    val nextEligibleAtEpochMillis: Long = 0L,
    val syncState: SyncState = SyncState.PENDING,
) {
    companion object {
        fun create(
            id: String,
            type: RecordType,
            scopeKey: String,
            payload: RecordPayload,
            createdAtEpochMillis: Long,
            userActionAtEpochMillis: Long,
        ): PendingRecord = PendingRecord(
            id = id,
            type = type,
            scopeKey = scopeKey,
            payload = payload,
            payloadDigest = sha256(payload.canonical),
            createdAtEpochMillis = createdAtEpochMillis,
            userActionAtEpochMillis = userActionAtEpochMillis,
        )

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}

/** Persisted local agreement consent fact. */
data class AgreementAcceptance(
    val scopeKey: String,
    val type: AgreementType,
    val version: String,
    val revisionId: String?,
    val contentHash: String?,
    val language: String?,
    val agreedAtEpochMillis: Long,
    val dedupeId: String,
    val syncState: AgreementSyncState,
) {
    fun toReference(): AgreementReference =
        AgreementReference(type, version, revisionId, contentHash, language)
}

/** Persisted local privacy decision fact (selection and cloud acknowledgement are separate). */
data class PrivacyDecision(
    val scopeKey: String,
    val accountId: String,
    val vin: String,
    val choice: PrivacyChoice,
    val items: List<String>,
    val decidedAtEpochMillis: Long,
    val dedupeId: String,
    val syncState: PrivacySyncState,
    val firstInteractionCompleted: Boolean,
)
