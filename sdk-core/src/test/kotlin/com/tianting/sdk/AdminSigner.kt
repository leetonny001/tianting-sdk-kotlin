package com.tianting.sdk

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 集成测试调管理接口用的 HMAC 签名（protocol/ADMIN.md「鉴权」，用例 protocol/vectors/admin-sign.json）。 */
object AdminSigner {
    fun stringToSign(method: String, path: String, timestamp: String, nonce: String, body: String, operator: String, requestId: String, targetNode: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
        return listOf(method, path, timestamp, nonce, hash, operator, requestId, targetNode).joinToString("\n")
    }

    fun sign(secret: ByteArray, toSign: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(toSign.toByteArray()))
    }
}
