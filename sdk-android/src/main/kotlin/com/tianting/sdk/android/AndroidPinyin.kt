package com.tianting.sdk.android

import android.icu.text.Collator
import android.icu.util.ULocale
import com.tianting.sdk.PinyinCollator
import com.tianting.sdk.pinyinSupported

/**
 * 拼音排序器（FE-380）：android.icu.text.Collator（API 24 起有）。
 * 设备的 ICU 数据版本可能不同，分组以 protocol/vectors/pinyin-groups.json 为准。
 */
public object AndroidPinyin {
    /** 系统不支持拼音排序时返回 null：pinyinGroup 把汉字开头的归 #，页面不报错。 */
    public fun collator(): PinyinCollator? {
        val c = Collator.getInstance(ULocale.forLanguageTag("zh-Hans-CN-u-co-pinyin"))
        return PinyinCollator { a, b -> c.compare(a, b) }.takeIf { pinyinSupported(it) }
    }
}
