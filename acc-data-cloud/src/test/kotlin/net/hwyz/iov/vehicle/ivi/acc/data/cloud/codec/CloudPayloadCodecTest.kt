package net.hwyz.iov.vehicle.ivi.acc.data.cloud.codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.boolean
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.string
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyItemDecision
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.PrivacyItemState
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementReference
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.IviLoginFailureReason
import net.hwyz.iov.vehicle.ivi.acc.domain.model.PendingRecord
import net.hwyz.iov.vehicle.ivi.acc.domain.model.RecordPayload
import net.hwyz.iov.vehicle.ivi.acc.domain.model.RecordType
import net.hwyz.iov.vehicle.ivi.acc.domain.state.PrivacyChoice
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G-1 / D-013 (privacy report) and R-15 / BLK-11 (login receipt) wire contracts.
 *
 * The field set is asserted literally: adding, renaming or dropping a field here breaks the TSP
 * contract and must fail this test.
 */
class CloudPayloadCodecTest {

    private val codec = JsonCloudPayloadCodec()
    private val json = Json { ignoreUnknownKeys = true }

    private val agreements = listOf(
        AgreementReference(AgreementType.PRIVACY_POLICY, "1.0", "REV-P", "hash-p", "zh-CN"),
    )

    private fun privacyRecord(
        catalogVersion: String = "v1",
        items: List<PrivacyItemDecision> = listOf(
            PrivacyItemDecision("GPS_LOCATION", PrivacyItemState.GRANTED, 3),
            PrivacyItemDecision("PERSONALIZED_CONTENT", PrivacyItemState.NOT_GRANTED, 7),
        ),
    ): PendingRecord = PendingRecord.create(
        id = "privacy-report-1",
        type = RecordType.PRIVACY_DECISION,
        scopeKey = "AV:account-1|VIN1",
        payload = RecordPayload.PrivacyDecisionRecord(
            vin = "VIN1",
            accountId = "account-1",
            catalogVersion = catalogVersion,
            choice = PrivacyChoice.GRANTED,
            items = items,
            decidedAtEpochMillis = 1_700_000_000_500L,
        ),
        createdAtEpochMillis = 1_700_000_000_500L,
        userActionAtEpochMillis = 1_700_000_000_500L,
    )

    @Test
    fun `privacy report carries the exact G-1 field set`() {
        val body = assertNotNull(codec.encodePrivacyReport(privacyRecord()))
        val root = json.parseToJsonElement(body) as JsonObject
        assertEquals(
            setOf("reportId", "catalogVersion", "items", "occurredAt"),
            root.keys,
        )
        assertEquals("v1", root.string("catalogVersion"))
        assertEquals("privacy-report-1", root.string("reportId"))
        assertEquals("2023-11-14T22:13:20.500Z", root.string("occurredAt"))

        val items = json.parseToJsonElement(root["items"].toString()) as kotlinx.serialization.json.JsonArray
        assertEquals(2, items.size)
        val first = items[0] as JsonObject
        val second = items[1] as JsonObject
        // Sorted by authorizationItem: GPS_LOCATION then PERSONALIZED_CONTENT.
        assertEquals(setOf("authorizationItem", "state", "version"), first.keys)
        assertEquals("GPS_LOCATION", first.string("authorizationItem"))
        assertEquals("GRANTED", first.string("state"))
        assertEquals("3", first.string("version"))
        assertEquals("NOT_GRANTED", second.string("state"))
        assertEquals("7", second.string("version"))
        // The version is the local decision sequence, not a timestamp.
        assertFalse(body.contains("1700000000500"))
    }

    @Test
    fun `a privacy report without a catalogue version is not deliverable`() {
        assertNull(codec.encodePrivacyReport(privacyRecord(catalogVersion = "")))
        assertNull(codec.encodePrivacyReport(privacyRecord(items = emptyList())))
    }

    @Test
    fun `a failed login receipt always carries a failure reason`() {
        val record = PendingRecord.create(
            id = "login-result-1",
            type = RecordType.LOGIN_RESULT,
            scopeKey = "VS:VIN1|session-1",
            payload = RecordPayload.LoginResult(
                vin = "VIN1",
                accountId = null,
                sessionId = "session-1",
                bindingOperationId = null,
                loggedInAtEpochMillis = 1_700_000_000_000L,
                loginStatus = "FAILED",
                agreements = agreements,
                failureReason = IviLoginFailureReason.TOKEN_EXCHANGE_FAILED,
            ),
            createdAtEpochMillis = 1_700_000_000_000L,
            userActionAtEpochMillis = 1_700_000_000_000L,
        )
        val body = assertNotNull(codec.encodeLoginResult(record))
        val root = json.parseToJsonElement(body) as JsonObject
        assertEquals("FAILED", root.string("loginStatus"))
        assertEquals("TOKEN_EXCHANGE_FAILED", root.string("failureReason"))
        // BLK-11: the receipt is sent even without the binding operation id.
        assertNull(root.string("bindingOperationId"))
        assertTrue(body.contains("\"sessionId\":\"session-1\""))
        assertEquals("login-result-1", root.string("reportId"))
    }

    @Test
    fun `a failed receipt without a reason is not deliverable`() {
        val record = PendingRecord.create(
            id = "login-result-2",
            type = RecordType.LOGIN_RESULT,
            scopeKey = "VS:VIN1|session-1",
            payload = RecordPayload.LoginResult(
                vin = "VIN1",
                accountId = null,
                sessionId = "session-1",
                bindingOperationId = null,
                loggedInAtEpochMillis = 1_700_000_000_000L,
                loginStatus = "FAILED",
            ),
            createdAtEpochMillis = 1_700_000_000_000L,
            userActionAtEpochMillis = 1_700_000_000_000L,
        )
        assertNull(codec.encodeLoginResult(record))
    }

    @Test
    fun `privacy catalogue decode rejects a partially understood item`() {
        val ok = json.parseToJsonElement(
            """{"catalogVersion":"v1","items":[{"authorizationItem":"GPS_LOCATION","displayName":"位置信息","required":false}]}""",
        ) as JsonObject
        val decoded = assertNotNull(codec.decodePrivacyCatalog(ok))
        assertEquals("v1", decoded.catalogVersion)
        assertEquals("GPS_LOCATION", decoded.items.single().code)
        assertEquals(false, decoded.items.single().required)

        val partiallyBroken = json.parseToJsonElement(
            """{"catalogVersion":"v1","items":[
                {"authorizationItem":"GPS_LOCATION","displayName":"位置信息","required":false},
                {"authorizationItem":"PERSONALIZED_CONTENT","required":true}
            ]}""",
        ) as JsonObject
        // One broken item invalidates the whole catalogue: no silent partial list.
        assertNull(codec.decodePrivacyCatalog(partiallyBroken))
    }

    @Test
    fun `required must be a boolean, not a string`() {
        val objectWithStringBoolean = json.parseToJsonElement(
            """{"catalogVersion":"v1","items":[{"authorizationItem":"GPS_LOCATION","displayName":"位置信息","required":"false"}]}""",
        ) as JsonObject
        assertNull(codec.decodePrivacyCatalog(objectWithStringBoolean))
        assertEquals(null, objectWithStringBoolean.boolean("missing"))
    }
}
