package net.hwyz.iov.vehicle.ivi.acc.domain.state

import net.hwyz.iov.vehicle.ivi.acc.domain.model.AccountRef
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementAcceptanceSnapshot
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementDocument
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionObservation
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionRef

/**
 * Aggregated, read only view of the activation flow.
 *
 * The five dimensions (guide, session, login, agreement sync, privacy) are intentionally separate:
 * one must never be used to infer another.
 */
data class ActivationState(
    val flowGeneration: Long = 0L,
    val guide: GuideState = GuideState.AGREEMENT_REQUIRED,
    val documents: List<AgreementDocument> = emptyList(),
    val documentsLoading: Boolean = false,
    val documentsFailure: ErrorCategory? = null,
    val acceptances: List<AgreementAcceptanceSnapshot> = emptyList(),
    val agreementSync: AgreementSyncState = AgreementSyncState.NOT_ACCEPTED,
    val session: SessionObservation = SessionObservation.None,
    val sessionRef: SessionRef? = null,
    val skip: SkipState = SkipState.IDLE,
    val login: LoginState = LoginState.SIGNED_OUT,
    val account: AccountRef? = null,
    val privacyChoice: PrivacyChoice = PrivacyChoice.NOT_DECIDED,
    val privacySync: PrivacySyncState = PrivacySyncState.NOT_SYNCED,
    val privacyFirstInteractionCompleted: Boolean = false,
    val privacyFrontSeatRequired: Boolean = false,
    val accountContinuationSuggested: Boolean = false,
    val foreground: Boolean = true,
    val error: ActivationError? = null,
) {
    fun acceptance(type: AgreementType): AgreementAcceptanceSnapshot? =
        acceptances.firstOrNull { it.type == type }

    fun document(type: AgreementType): AgreementDocument? = documents.firstOrNull { it.type == type }

    val documentsComplete: Boolean
        get() = documents.isNotEmpty() && AgreementType.entries.all { document(it)?.isUsable == true }

    /** Both documents accepted, and each acceptance still matches the displayed version. */
    val agreementsAccepted: Boolean
        get() = AgreementType.entries.all { type ->
            val acceptance = acceptance(type) ?: return@all false
            val document = document(type) ?: return@all false
            acceptance.version == document.version &&
                (acceptance.revisionId == null || document.revisionId == null ||
                    acceptance.revisionId == document.revisionId)
        }

    /** A previously accepted version is no longer the published one: re-consent is mandatory. */
    val agreementVersionDrift: Boolean
        get() = acceptances.isNotEmpty() && acceptances.any { acceptance ->
            document(acceptance.type)?.let { it.version != acceptance.version } ?: false
        }

    /** QR may be displayed: current session exists and both agreements were accepted. */
    val qrVisible: Boolean
        get() = guide == GuideState.QR_VISIBLE &&
            sessionRef != null &&
            agreementsAccepted

    val entertainmentEntryAllowed: Boolean
        get() = guide == GuideState.ENTERED_LOCAL || guide == GuideState.ENTERED_ACCOUNT

    val sessionBound: Boolean
        get() = session is SessionObservation.BoundConfirmed

    val skipInProgress: Boolean
        get() = skip == SkipState.SUBMITTING || skip == SkipState.RESULT_UNKNOWN
}
