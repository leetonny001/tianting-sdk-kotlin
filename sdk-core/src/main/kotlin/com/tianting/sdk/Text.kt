// 文本规则（PRD 3.1、C-26、C-27），与服务端一致：两边跑同一份 protocol/vectors/text.json。
package com.tianting.sdk

/** 文本消息的最大长度（去首尾空白后的码点数）。 */
public const val MAX_TEXT_LENGTH: Int = 5000

// Unicode White_Space 属性（25 个字符，都在 BMP）。不能用 Char.isWhitespace()：它不含 U+0085、U+00A0、U+2007、U+202F，
// 又多了 U+001C～U+001F。U+200B、U+FEFF 不是 White_Space。
private fun isWhiteSpace(c: Char): Boolean = when (c.code) {
    in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
    else -> false
}

/** 去掉首尾空白（含 \n、\t、全角空格 U+3000）。 */
public fun trimText(s: String): String = s.trim { isWhiteSpace(it) }

/** 按 Unicode 码点计数：emoji 算 1 个，ZWJ 组合 emoji 按组成的码点数计（C-27）。 */
public fun countCodePoints(s: String): Int = s.codePointCount(0, s.length)

public sealed interface TextCheck {
    public data class Ok(val text: String) : TextCheck
    public data class Rejected(val reason: TextRejectReason, val text: String, val over: Int) : TextCheck
}

public enum class TextRejectReason { EMPTY, TOO_LONG }

/** 校验待发送的文本：去空白后为空 → EMPTY；超过 5000 → TOO_LONG（over 为超出的字数，用于"已超出 N 字"提示，C-28）。 */
public fun checkText(raw: String): TextCheck {
    val text = trimText(raw)
    val n = countCodePoints(text)
    return when {
        n == 0 -> TextCheck.Rejected(TextRejectReason.EMPTY, text, 0)
        n > MAX_TEXT_LENGTH -> TextCheck.Rejected(TextRejectReason.TOO_LONG, text, n - MAX_TEXT_LENGTH)
        else -> TextCheck.Ok(text)
    }
}

/** 按前 max 个码点截断（不切开代理对）。 */
internal fun takeCodePoints(s: String, max: Int): String {
    if (countCodePoints(s) <= max) return s
    return s.substring(0, s.offsetByCodePoints(0, max))
}
