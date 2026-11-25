package net.hwyz.iov.vehicle.ivi.acc.domain

import kotlin.test.assertEquals
import kotlin.test.assertNull
import net.hwyz.iov.vehicle.ivi.acc.domain.log.DIAGNOSTIC_MESSAGE_MAX_LENGTH
import net.hwyz.iov.vehicle.ivi.acc.domain.log.sanitizeDiagnosticMessage
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** DSN-CR-007 D-007: cloud supplied text may be logged only after collapsing and truncation. */
class RedactionTest {

    @Test
    @DisplayName("换行与控制字符被移除，连续空白折叠为单个空格")
    fun controlCharactersAndWhitespaceAreNormalised() {
        assertEquals(
            "绑定票据 换令牌 能力未启用",
            sanitizeDiagnosticMessage("  绑定票据\n换令牌\r\t能力未启用  "),
        )
    }

    @Test
    @DisplayName("空文案不入日志")
    fun blankMessageIsDropped() {
        assertNull(sanitizeDiagnosticMessage(null))
        assertNull(sanitizeDiagnosticMessage(""))
        assertNull(sanitizeDiagnosticMessage("   \n\t "))
    }

    @Test
    @DisplayName("超长文案截断到上限并给出省略标记")
    fun longMessageIsTruncated() {
        val long = "云".repeat(DIAGNOSTIC_MESSAGE_MAX_LENGTH + 20)
        val sanitized = sanitizeDiagnosticMessage(long)
        assertEquals(DIAGNOSTIC_MESSAGE_MAX_LENGTH + 1, sanitized?.length)
        assertEquals("…", sanitized?.last()?.toString())
    }
}
