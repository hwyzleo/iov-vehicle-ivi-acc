package net.hwyz.iov.vehicle.ivi.acc.domain.model

/** The two documents that gate the activation QR code. */
enum class AgreementType {
    PRIVACY_POLICY,
    USER_AGREEMENT,
}

/**
 * One published agreement document as delivered by the cloud adapter.
 *
 * [version] and [revisionId] identify what was actually displayed. They must be persisted with the
 * user's consent so a later publication cannot silently replace the agreed version.
 */
data class AgreementDocument(
    val type: AgreementType,
    val version: String,
    val revisionId: String? = null,
    val language: String? = null,
    val contentHash: String? = null,
    val body: String? = null,
) {
    val isUsable: Boolean
        get() = version.isNotBlank()
}

/** Version snapshot of a displayed document, used for consent records and cloud inputs. */
data class AgreementReference(
    val type: AgreementType,
    val version: String,
    val revisionId: String? = null,
    val contentHash: String? = null,
    val language: String? = null,
)

/** In-memory acceptance snapshot bound to the version that was displayed when the user agreed. */
data class AgreementAcceptanceSnapshot(
    val type: AgreementType,
    val version: String,
    val revisionId: String? = null,
    val contentHash: String? = null,
    val language: String? = null,
    val agreedAtEpochMillis: Long,
) {
    fun toReference(): AgreementReference =
        AgreementReference(type, version, revisionId, contentHash, language)
}

data class AgreementBundle(val documents: List<AgreementDocument>) {
    fun document(type: AgreementType): AgreementDocument? = documents.firstOrNull { it.type == type }

    /** Both documents are present and carry an identifiable version. */
    val isComplete: Boolean
        get() = AgreementType.entries.all { type -> document(type)?.isUsable == true }
}
