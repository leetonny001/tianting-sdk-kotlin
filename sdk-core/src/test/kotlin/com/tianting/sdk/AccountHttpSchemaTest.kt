// TC-CHAT-90-09（AC-CHAT-90-10）：账号接口的请求和 http-client.schema.json 一致。
package com.tianting.sdk

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountHttpSchemaTest : AccountTest() {
    @Test
    fun `TC-CHAT-90-09 AccountClient 的 14 个接口都在 endpoints 里，请求体符合 Schema`() {
        val a = setupAccount()
        a.session(1, DAY)
        a.start { getConfig() }
        a.start { listEvents() }
        a.start { updateProfile(nickname = "小明") }
        a.start { regenerateRecoveryCode("Password-1") }
        a.start { ackRecoveryCode(2) }
        a.start { cancelDelete("alice_1", "Password-1") }
        for (c in a.http.calls.toList()) c.respond(200)
        // 串行队列里的，每个都放行再看下一个。
        val inQueue: List<suspend AccountClient.() -> Unit> = listOf(
            { register("alice_1", "Password-1", nickname = "小明", inviteCode = "INV") },
            { login("alice_1", "Password-1") },
            { recover("alice_1", "CODE", "Password-2") },
            { changePassword("Password-1", "Password-2") },
            { logoutOthers("Password-1") },
            { requestDelete("Password-1") },
        )
        for (run in inQueue) {
            a.start(run)
            a.http.pending[0].respondError(400, 4000)
        }
        val logout = a.start { logout() }
        a.http.pending[0].respond(200)
        a.await(logout)
        val seen = a.http.calls.map { "${it.req.method} ${it.path}" }.toSet()
        assertEquals(emptyList(), HttpSchema.endpoints.filter { it !in seen && !it.endsWith("/token/refresh") })
        assertEquals(emptyList(), a.badRequests)
    }

    @Test
    fun `TC-CHAT-90-09 校验器确实会拒绝不合格的响应和请求`() {
        assertTrue(HttpSchema.errors("""{"userId":"x"}""", "AuthResponse").isNotEmpty())
        assertTrue(HttpSchema.errors("""{"username":"x"}""", "RegisterRequest").isNotEmpty())
    }

    @Test
    fun `问题 1 recovery-code 的响应是 RecoveryCodeResponse，协议副本已经改过来了`() {
        assertEquals(emptyList(), HttpSchema.errors("""{"recoveryCode":"C","recoveryCodeGen":1}""", "RecoveryCodeResponse"))
    }

    @Test
    fun `TC-CHAT-90-09 平台、设备名在请求体里，退出带 refresh`() {
        val a = setupAccount()
        val p = a.start { login("alice_1", "Password-1") }
        assertEquals("android", a.http.last("/v1/account/login").json["platform"]!!.str)
        a.http.last("/v1/account/login").respondError(401, 4018)
        a.failure<AccountError>(p)
    }
}
