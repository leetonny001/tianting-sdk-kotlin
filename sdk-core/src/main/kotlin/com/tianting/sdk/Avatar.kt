// 占位头像（PRD-ui F-UI-21）：各端用同一套规则，保证同一个人在每个端颜色和首字都一样。
// 用例 protocol/vectors/avatar.json（三套 SDK 和各端界面都跑）。
package com.tianting.sdk

/** FNV-1a 32 位哈希（对 UTF-8 字节），返回无符号值。 */
public fun fnv1a32(s: String): Long {
    var h = 0x811c9dc5u
    for (b in s.toByteArray(Charsets.UTF_8)) {
        h = h xor (b.toUInt() and 0xffu)
        h *= 0x01000193u
    }
    return h.toLong()
}

/** 调色板下标（0～7）：用户用 userId，群用 groupId。 */
public fun avatarColorIndex(id: String): Int = (fnv1a32(id) % 8).toInt()

/** 首字：显示名去首尾空白后的第一个码点，ASCII 小写字母转大写。 */
public fun avatarInitial(name: String): String {
    val t = name.trim { isJsTrimSpace(it) }
    if (t.isEmpty()) return "?"
    val first = String(Character.toChars(t.codePointAt(0)))
    return if (first.length == 1 && first[0] in 'a'..'z') first.uppercase() else first
}

// sdk-ts 用 String.prototype.trim：WhiteSpace + LineTerminator，比 White_Space 少 U+0085、多 U+FEFF。
private fun isJsTrimSpace(c: Char): Boolean = when (c.code) {
    in 0x09..0x0D, 0x20, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true
    else -> false
}
