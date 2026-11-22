package net.hwyz.iov.vehicle.ivi.acc.data.cloud

import kotlinx.coroutines.test.runTest
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.codec.ActivationPayloadCodec
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.codec.UnconfiguredPayloadCodec
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.CloudOperation
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.OkHttpSgwTransport
import net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.SgwRouteConfig
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginMaterialOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.LoginMaterialRequestInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.RecordReportInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.RecordReportOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionQueryInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionQueryOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionRequestInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SessionRequestOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SkipRequestInput
import net.hwyz.iov.vehicle.ivi.acc.domain.gateway.SkipSubmitOutcome
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementReference
import net.hwyz.iov.vehicle.ivi.acc.domain.model.AgreementType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.LoginMaterial
import net.hwyz.iov.vehicle.ivi.acc.domain.model.PendingRecord
import net.hwyz.iov.vehicle.ivi.acc.domain.model.RecordPayload
import net.hwyz.iov.vehicle.ivi.acc.domain.model.RecordType
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionObservation
import net.hwyz.iov.vehicle.ivi.acc.domain.model.SessionRef
import net.hwyz.iov.vehicle.ivi.acc.domain.model.VehicleRef
import net.hwyz.iov.vehicle.ivi.acc.domain.state.ErrorCategory
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract level tests of the SGW adapter.
 *
 * The payload codec is test only: it proves that timeouts, duplicates and out of order responses
 * are classified and decoded without assuming an unconfirmed cloud schema.
 */
class SgwActivationCloudPortTest {

    private lateinit var server: MockWebServer
    private lateinit var port: SgwActivationCloudPort

    private val vehicle = VehicleRef("VIN1")
    private val session = SessionRef("session-1", "qr-secret", 0L, Long.MAX_VALUE)

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        val routes = SgwRouteConfig(
            baseUrl = server.url("/").toString(),
            paths = mapOf(
                CloudOperation.REQUEST_SESSION to "/v1/activation/session",
                CloudOperation.QUERY_SESSION to "/v1/activation/session/query",
                CloudOperation.SUBMIT_SKIP to "/v1/activation/skip",
                CloudOperation.REQUEST_LOGIN_MATERIAL to "/v1/activation/material",
                CloudOperation.REPORT_RECORD to "/v1/activation/record",
            ),
        )
        val client = OkHttpClient.Builder()
            .connectTimeout(200, TimeUnit.MILLISECONDS)
            .readTimeout(200, TimeUnit.MILLISECONDS)
            .build()
        port = SgwActivationCloudPort(OkHttpSgwTransport(routes, client), TestPayloadCodec())
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun sessionInput() = SessionRequestInput(
        vehicle = vehicle,
        agreements = listOf(AgreementReference(AgreementType.PRIVACY_POLICY, "1.0")),
        flowGeneration = 4L,
        requestId = "request-1",
    )

    @Test
    fun `session creation decodes the returned session`() = runTest {
        server.enqueue(MockResponse().setBody("session=s-9;qr=payload-9;expires=12345"))
        val outcome = port.requestSession(sessionInput())
        assertTrue(outcome is SessionRequestOutcome.Created)
        val created = (outcome as SessionRequestOutcome.Created).session
        assertEquals("s-9", created.sessionId)
        assertEquals(12345L, created.expiresAtEpochMillis)
        val recorded = server.takeRequest()
        assertEquals("request-1", recorded.getHeader("Idempotency-Key"))
    }

    @Test
    fun `a timeout means an unknown result, not a failure`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val outcome = port.querySession(SessionQueryInput(vehicle, session, 1L))
        assertEquals(
            SessionQueryOutcome.Failed(ErrorCategory.RESULT_UNKNOWN),
            outcome,
        )
    }

    @Test
    fun `server errors are retryable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(
            SessionRequestOutcome.Failed(ErrorCategory.RETRYABLE),
            port.requestSession(sessionInput()),
        )
    }

    @Test
    fun `authentication failures are not retried blindly`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(
            SkipSubmitOutcome.Failed(ErrorCategory.AUTH_REJECTED),
            port.submitSkip(SkipRequestInput(vehicle, "skip-1", emptyList(), session.sessionId)),
        )
    }

    @Test
    fun `conflict responses are contract rejections`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409))
        assertEquals(
            SkipSubmitOutcome.Failed(ErrorCategory.CONTRACT_REJECTED),
            port.submitSkip(SkipRequestInput(vehicle, "skip-1", emptyList(), session.sessionId)),
        )
    }

    @Test
    fun `query decodes bound confirmed`() = runTest {
        server.enqueue(MockResponse().setBody("BOUND:binding-7"))
        val outcome = port.querySession(SessionQueryInput(vehicle, session, 1L))
        val observation = (outcome as SessionQueryOutcome.Observed).observation
        assertEquals(SessionObservation.BoundConfirmed("binding-7"), observation)
    }

    @Test
    fun `an undecodable body is reported as unknown instead of success`() = runTest {
        server.enqueue(MockResponse().setBody("something unexpected"))
        assertEquals(
            SessionQueryOutcome.Failed(ErrorCategory.RESULT_UNKNOWN),
            port.querySession(SessionQueryInput(vehicle, session, 1L)),
        )
    }

    @Test
    fun `login material is decoded with its expiry`() = runTest {
        server.enqueue(MockResponse().setBody("handle=h-1;expires=9999"))
        val outcome = port.requestLoginMaterial(
            LoginMaterialRequestInput(vehicle, session, "binding-1"),
        )
        val material = (outcome as LoginMaterialOutcome.Material).material
        assertEquals("h-1", material.handle)
        assertEquals(9999L, material.expiresAtEpochMillis)
    }

    @Test
    fun `record report uses the dedupe id as idempotency key`() = runTest {
        server.enqueue(MockResponse().setBody("ACK"))
        val record = PendingRecord.create(
            id = "dedupe-1",
            type = RecordType.LOGIN_RESULT,
            scopeKey = "AV:account-1|VIN1",
            payload = RecordPayload.LoginResult("VIN1", "account-1", "session-1", null, 1L),
            createdAtEpochMillis = 1L,
            userActionAtEpochMillis = 1L,
        )
        assertEquals(RecordReportOutcome.Acknowledged, port.reportRecord(RecordReportInput(record)))
        assertEquals("dedupe-1", server.takeRequest().getHeader("Idempotency-Key"))
    }

    @Test
    fun `an unconfigured codec fails fast without calling the cloud`() = runTest {
        val unconfigured = SgwActivationCloudPort(
            OkHttpSgwTransport(SgwRouteConfig(server.url("/").toString(), emptyMap())),
            UnconfiguredPayloadCodec(),
        )
        assertEquals(
            SessionRequestOutcome.Failed(ErrorCategory.CONTRACT_REJECTED),
            unconfigured.requestSession(sessionInput()),
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a missing route is a retryable transport problem`() = runTest {
        val misconfigured = SgwActivationCloudPort(
            OkHttpSgwTransport(SgwRouteConfig(server.url("/").toString(), emptyMap())),
            TestPayloadCodec(),
        )
        assertEquals(
            SessionQueryOutcome.Failed(ErrorCategory.RETRYABLE),
            misconfigured.querySession(SessionQueryInput(vehicle, session, 1L)),
        )
    }

    @Test
    fun `repeated responses are decoded independently`() = runTest {
        server.enqueue(MockResponse().setBody("ACTIVE"))
        server.enqueue(MockResponse().setBody("BOUND:binding-1"))
        server.enqueue(MockResponse().setBody("BOUND:binding-1"))

        val first = port.querySession(SessionQueryInput(vehicle, session, 1L))
        val second = port.querySession(SessionQueryInput(vehicle, session, 1L))
        val third = port.querySession(SessionQueryInput(vehicle, session, 1L))

        assertEquals(SessionObservation.Active, (first as SessionQueryOutcome.Observed).observation)
        assertEquals(
            SessionObservation.BoundConfirmed("binding-1"),
            (second as SessionQueryOutcome.Observed).observation,
        )
        // A duplicated success answer is still a success; the coordinator guarantees a single login.
        assertEquals(
            SessionObservation.BoundConfirmed("binding-1"),
            (third as SessionQueryOutcome.Observed).observation,
        )
        assertNotNull(server.url("/"))
    }

    @Test
    fun `classifier maps transport failures deterministically`() {
        assertNull(SgwOutcomeClassifier.classify(transportOk()))
        assertEquals(
            ErrorCategory.RESULT_UNKNOWN,
            SgwOutcomeClassifier.classify(
                net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.TransportResult(
                    statusCode = 0,
                    failure = net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.TransportFailure.TIMEOUT,
                ),
            ),
        )
        assertEquals(
            ErrorCategory.RETRYABLE,
            SgwOutcomeClassifier.classify(
                net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.TransportResult(
                    statusCode = 0,
                    failure = net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.TransportFailure.IO_ERROR,
                ),
            ),
        )
        assertEquals(
            ErrorCategory.RETRYABLE,
            SgwOutcomeClassifier.classify(
                net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.TransportResult(429),
            ),
        )
        assertEquals(
            ErrorCategory.CONTRACT_REJECTED,
            SgwOutcomeClassifier.classify(
                net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.TransportResult(400),
            ),
        )
    }

    private fun transportOk() = net.hwyz.iov.vehicle.ivi.acc.data.cloud.transport.TransportResult(200, "ok")

    private fun material(handle: String, expires: Long) = LoginMaterial(handle, expires)
}

/** Test only codec: it never pretends to be the cloud schema. */
private class TestPayloadCodec : ActivationPayloadCodec {
    override fun encodeSessionRequest(input: SessionRequestInput): String =
        "vin=${input.vehicle.vin};generation=${input.flowGeneration};request=${input.requestId}"

    override fun decodeSessionCreated(body: String): SessionRef? {
        val fields = body.split(';').associate { it.substringBefore('=') to it.substringAfter('=') }
        val sessionId = fields["session"] ?: return null
        return SessionRef(
            sessionId = sessionId,
            qrPayload = fields["qr"].orEmpty(),
            issuedAtEpochMillis = 0L,
            expiresAtEpochMillis = fields["expires"]?.toLongOrNull() ?: return null,
        )
    }

    override fun encodeSessionQuery(input: SessionQueryInput): String =
        "session=${input.session.sessionId}"

    override fun decodeSessionObservation(body: String): SessionObservation? = when {
        body == "ACTIVE" -> SessionObservation.Active
        body == "EXPIRED" -> SessionObservation.Expired
        body == "UNKNOWN" -> SessionObservation.ResultUnknown
        body.startsWith("BOUND:") -> SessionObservation.BoundConfirmed(body.removePrefix("BOUND:"))
        else -> null
    }

    override fun encodeSkip(input: SkipRequestInput): String = "request=${input.requestId}"

    override fun decodeSkipAcknowledged(body: String): Boolean = body == "ACK"

    override fun encodeLoginMaterialRequest(input: LoginMaterialRequestInput): String =
        "session=${input.session.sessionId}"

    override fun decodeLoginMaterial(body: String): LoginMaterial? {
        val fields = body.split(';').associate { it.substringBefore('=') to it.substringAfter('=') }
        val handle = fields["handle"] ?: return null
        return LoginMaterial(handle, fields["expires"]?.toLongOrNull() ?: return null)
    }

    override fun encodeRecord(record: PendingRecord): String = "id=${record.id}"

    override fun decodeRecordAcknowledged(body: String): Boolean = body == "ACK"
}
