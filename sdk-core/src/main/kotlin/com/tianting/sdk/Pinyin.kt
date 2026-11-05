// 通讯录拼音分组（PRD-ui F-UI-13 规则 4）：不引入拼音库，用系统的拼音排序 + 锚点汉字表。
// 锚点、期望结果见 protocol/vectors/pinyin-groups.json。排序器由平台提供：
// Android 用 android.icu.text.Collator（sdk-android 的 AndroidPinyin），JVM 用 ICU4J。
package com.tianting.sdk

import java.text.Normalizer

/** 平台提供的 zh-Hans-CN 拼音排序器。 */
public fun interface PinyinCollator {
    public fun compare(a: String, b: String): Int
}

private const val LETTERS = "ABCDEFGHJKLMNOPQRSTWXYZ"
private const val ANCHORS = "吖丷嚓咑妸发旮哈丌咔垃呣拏喔妑七呥仨他屲夕丫帀"

/** 这个排序器是不是真的按拼音排（锚点严格递增）。系统 ICU 没带中文排序数据时会退化成按码点，不能用。 */
public fun pinyinSupported(collator: PinyinCollator): Boolean =
    ANCHORS.indices.drop(1).all { collator.compare(ANCHORS[it - 1].toString(), ANCHORS[it].toString()) < 0 }

/** 显示名的分组字母：A～Z 或 #。collator 为 null（系统不支持拼音排序）时，汉字开头的一律归 #，页面不报错。 */
public fun pinyinGroup(name: String, collator: PinyinCollator?): String {
    if (name.isEmpty()) return "#"
    val cp = name.codePointAt(0)
    val first = String(Character.toChars(cp))
    val ascii = Normalizer.normalize(first, Normalizer.Form.NFKC)
    if (ascii.length == 1 && (ascii[0] in 'A'..'Z' || ascii[0] in 'a'..'z')) return ascii.uppercase()
    if (Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN || collator == null) return "#"
    var group = "#"
    for (i in LETTERS.indices) {
        if (collator.compare(first, ANCHORS[i].toString()) < 0) break
        group = LETTERS[i].toString()
    }
    return group
}

private fun startsWithHan(s: String) =
    s.isNotEmpty() && Character.UnicodeScript.of(s.codePointAt(0)) == Character.UnicodeScript.HAN

/** 比较显示名。中文排序规则默认把汉字排在拉丁字母前面，而 PRD 要求 "Alpha" 在 "周末球局" 前（AC-UI-32-1）：非汉字开头的先排。 */
public fun compareNames(collator: PinyinCollator?): Comparator<String> {
    if (collator == null) return Comparator { a, b -> a.compareTo(b) }
    return Comparator { a, b ->
        val ha = startsWithHan(a)
        if (ha == startsWithHan(b)) collator.compare(a, b) else if (ha) 1 else -1
    }
}

public data class PinyinGroup<T>(val group: String, val items: List<T>)

/** 按 A～Z 分组，"#"放最后；组内按显示名，相同时按 id 字节序。 */
public fun <T> groupByPinyin(
    items: List<T>,
    collator: PinyinCollator?,
    name: (T) -> String,
    id: (T) -> String,
): List<PinyinGroup<T>> {
    val cmp = compareNames(collator)
    return items.groupBy { pinyinGroup(name(it), collator) }.entries
        .sortedWith(compareBy<Map.Entry<String, List<T>>> { it.key == "#" }.thenBy { it.key })
        .map { (g, list) ->
            PinyinGroup(g, list.sortedWith { a, b -> cmp.compare(name(a), name(b)).takeIf { it != 0 } ?: id(a).compareTo(id(b)) })
        }
}
