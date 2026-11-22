package net.hwyz.iov.vehicle.ivi.acc.data.local.dto

import kotlinx.serialization.Serializable
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementAcceptance
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementReference
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.PendingRecord
import net.hwyz.iov.vehicle.ivi.acc.domain.model.PrivacyDecision
import net.hwyz.iov.vehicle.ivi.acc.domain.model.RecordPayload
import net.hwyz.iov.vehicle.ivi.acc.domain.model.RecordType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.AgreementSyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.GuideState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.LoginState
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacyChoice
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacySyncState
import net.hwyz.iov.vehicle.ivi.acc.domain.store.FlowContext

/** Persistence DTOs. They are private to the data layer and never cross into the domain. */
@Serializable
data class FlowContextDto(
    val flowGeneration: Long,
    val guide: String,
    val login: String,
    val sessionId: String? = null,
    val sessionExpiresAtEpochMillis: Long? = null,
    val bindingOperationId: String? = null,
    val bindingConfirmed: Boolean = false,
    val accountId: String? = null,
    val updatedAtEpochMillis: Long = 0L,
) {
    fun toDomain(): FlowContext = FlowContext(
        flowGeneration = flowGeneration,
        guide = GuideState.valueOf(guide),
        login = LoginState.valueOf(login),
        sessionId = sessionId,
        sessionExpiresAtEpochMillis = sessionExpiresAtEpochMillis,
        bindingOperationId = bindingOperationId,
        bindingConfirmed = bindingConfirmed,
        accountId = accountId,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    companion object {
        fun from(context: FlowContext): FlowContextDto = FlowContextDto(
            flowGeneration = context.flowGeneration,
            guide = context.guide.name,
            login = context.login.name,
            sessionId = context.sessionId,
            sessionExpiresAtEpochMillis = context.sessionExpiresAtEpochMillis,
            bindingOperationId = context.bindingOperationId,
            bindingConfirmed = context.bindingConfirmed,
            accountId = context.accountId,
            updatedAtEpochMillis = context.updatedAtEpochMillis,
        )
    }
}

@Serializable
data class AgreementAcceptanceDto(
    val scopeKey: String,
    val type: String,
    val version: String,
    val revisionId: String? = null,
    val contentHash: String? = null,
    val language: String? = null,
    val agreedAtEpochMillis: Long,
    val dedupeId: String,
    val syncState: String,
) {
    fun toDomain(): AgreementAcceptance = AgreementAcceptance(
        scopeKey = scopeKey,
        type = AgreementType.valueOf(type),
        version = version,
        revisionId = revisionId,
        contentHash = contentHash,
        language = language,
        agreedAtEpochMillis = agreedAtEpochMillis,
        dedupeId = dedupeId,
        syncState = AgreementSyncState.valueOf(syncState),
    )

    companion object {
        fun from(record: AgreementAcceptance): AgreementAcceptanceDto = AgreementAcceptanceDto(
            scopeKey = record.scopeKey,
            type = record.type.name,
            version = record.version,
            revisionId = record.revisionId,
            contentHash = record.contentHash,
            language = record.language,
            agreedAtEpochMillis = record.agreedAtEpochMillis,
            dedupeId = record.dedupeId,
            syncState = record.syncState.name,
        )
    }
}

@Serializable
data class PrivacyDecisionDto(
    val scopeKey: String,
    val accountId: String,
    val vin: String,
    val choice: String,
    val items: List<String> = emptyList(),
    val decidedAtEpochMillis: Long,
    val dedupeId: String,
    val syncState: String,
    val firstInteractionCompleted: Boolean,
) {
    fun toDomain(): PrivacyDecision = PrivacyDecision(
        scopeKey = scopeKey,
        accountId = accountId,
        vin = vin,
        choice = PrivacyChoice.valueOf(choice),
        items = items,
        decidedAtEpochMillis = decidedAtEpochMillis,
        dedupeId = dedupeId,
        syncState = PrivacySyncState.valueOf(syncState),
        firstInteractionCompleted = firstInteractionCompleted,
    )

    companion object {
        fun from(decision: PrivacyDecision): PrivacyDecisionDto = PrivacyDecisionDto(
            scopeKey = decision.scopeKey,
            accountId = decision.accountId,
            vin = decision.vin,
            choice = decision.choice.name,
            items = decision.items,
            decidedAtEpochMillis = decision.decidedAtEpochMillis,
            dedupeId = decision.dedupeId,
            syncState = decision.syncState.name,
            firstInteractionCompleted = decision.firstInteractionCompleted,
        )
    }
}

@Serializable
data class AgreementRefDto(
    val type: String,
    val version: String,
    val revisionId: String? = null,
    val contentHash: String? = null,
    val language: String? = null,
) {
    fun toDomain(): AgreementReference = AgreementReference(
        type = AgreementType.valueOf(type),
        version = version,
        revisionId = revisionId,
        contentHash = contentHash,
        language = language,
    )

    companion object {
        fun from(reference: AgreementReference): AgreementRefDto = AgreementRefDto(
            type = reference.type.name,
            version = reference.version,
            revisionId = reference.revisionId,
            contentHash = reference.contentHash,
            language = reference.language,
        )
    }
}

@Serializable
data class RecordPayloadDto(
    val kind: String,
    val vin: String,
    val accountId: String? = null,
    val sessionId: String? = null,
    val bindingOperationId: String? = null,
    val agreements: List<AgreementRefDto> = emptyList(),
    val choice: String? = null,
    val items: List<String> = emptyList(),
    val at: Long = 0L,
)

@Serializable
data class PendingRecordDto(
    val id: String,
    val type: String,
    val scopeKey: String,
    val payloadDigest: String,
    val createdAtEpochMillis: Long,
    val userActionAtEpochMillis: Long,
    val attempts: Int = 0,
    val nextEligibleAtEpochMillis: Long = 0L,
    val syncState: String = SyncState.PENDING.name,
    val payload: RecordPayloadDto,
) {
    fun toDomain(): PendingRecord = PendingRecord(
        id = id,
        type = RecordType.valueOf(type),
        scopeKey = scopeKey,
        payload = payload.toDomainPayload(),
        payloadDigest = payloadDigest,
        createdAtEpochMillis = createdAtEpochMillis,
        userActionAtEpochMillis = userActionAtEpochMillis,
        attempts = attempts,
        nextEligibleAtEpochMillis = nextEligibleAtEpochMillis,
        syncState = SyncState.valueOf(syncState),
    )

    companion object {
        fun from(record: PendingRecord): PendingRecordDto = PendingRecordDto(
            id = record.id,
            type = record.type.name,
            scopeKey = record.scopeKey,
            payloadDigest = record.payloadDigest,
            createdAtEpochMillis = record.createdAtEpochMillis,
            userActionAtEpochMillis = record.userActionAtEpochMillis,
            attempts = record.attempts,
            nextEligibleAtEpochMillis = record.nextEligibleAtEpochMillis,
            syncState = record.syncState.name,
            payload = fromPayload(record.payload),
        )
    }
}

fun RecordPayloadDto.toDomainPayload(): RecordPayload = when (kind) {
    KIND_AGREEMENT_CONSENT -> RecordPayload.AgreementConsent(
        vin = vin,
        accountId = accountId,
        agreements = agreements.map { it.toDomain() },
        sessionId = sessionId,
        bindingOperationId = bindingOperationId,
        agreedAtEpochMillis = at,
    )

    KIND_PRIVACY_DECISION -> RecordPayload.PrivacyDecisionRecord(
        vin = vin,
        accountId = accountId.orEmpty(),
        choice = PrivacyChoice.valueOf(choice ?: PrivacyChoice.NOT_GRANTED.name),
        items = items,
        decidedAtEpochMillis = at,
    )

    KIND_LOGIN_RESULT -> RecordPayload.LoginResult(
        vin = vin,
        accountId = accountId.orEmpty(),
        sessionId = sessionId.orEmpty(),
        bindingOperationId = bindingOperationId,
        loggedInAtEpochMillis = at,
    )

    KIND_SKIP_ACTIVATION -> RecordPayload.SkipActivation(
        vin = vin,
        agreements = agreements.map { it.toDomain() },
        sessionId = sessionId,
        requestedAtEpochMillis = at,
    )

    else -> error("Unknown record payload kind: $kind")
}

fun fromPayload(payload: RecordPayload): RecordPayloadDto = when (payload) {
    is RecordPayload.AgreementConsent -> RecordPayloadDto(
        kind = KIND_AGREEMENT_CONSENT,
        vin = payload.vin,
        accountId = payload.accountId,
        sessionId = payload.sessionId,
        bindingOperationId = payload.bindingOperationId,
        agreements = payload.agreements.map { AgreementRefDto.from(it) },
        at = payload.agreedAtEpochMillis,
    )

    is RecordPayload.PrivacyDecisionRecord -> RecordPayloadDto(
        kind = KIND_PRIVACY_DECISION,
        vin = payload.vin,
        accountId = payload.accountId,
        choice = payload.choice.name,
        items = payload.items,
        at = payload.decidedAtEpochMillis,
    )

    is RecordPayload.LoginResult -> RecordPayloadDto(
        kind = KIND_LOGIN_RESULT,
        vin = payload.vin,
        accountId = payload.accountId,
        sessionId = payload.sessionId,
        bindingOperationId = payload.bindingOperationId,
        at = payload.loggedInAtEpochMillis,
    )

    is RecordPayload.SkipActivation -> RecordPayloadDto(
        kind = KIND_SKIP_ACTIVATION,
        vin = payload.vin,
        sessionId = payload.sessionId,
        agreements = payload.agreements.map { AgreementRefDto.from(it) },
        at = payload.requestedAtEpochMillis,
    )
}

private const val KIND_AGREEMENT_CONSENT = "AGREEMENT_CONSENT"
private const val KIND_PRIVACY_DECISION = "PRIVACY_DECISION"
private const val KIND_LOGIN_RESULT = "LOGIN_RESULT"
private const val KIND_SKIP_ACTIVATION = "SKIP_ACTIVATION"
