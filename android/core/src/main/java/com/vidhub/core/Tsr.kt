package com.vidhub.core

/**
 * 網頁裡內嵌的頁面資料（框架把伺服器端讀好的資料寫成一段 JS：$_TSR.router=($R=>$R[0]={…})(…)）。
 * 不執行網站的程式碼，自己解析這種格式：物件、陣列、字串、數字、!0/!1、null，
 * 以及「$R[n]=值」（定義）和「$R[n]」（重複出現時的參照）。電腦版的 tsr.js 是同一套規則。
 *
 * 結果：物件 → Map<String, Any?>，陣列 → List<Any?>，數字 → Double，其餘照原樣。
 */
class Tsr private constructor(private val s: String, private var i: Int) {
    private val refs = HashMap<Int, Any?>()

    private fun fail(msg: String): Nothing = throw IllegalStateException("頁面資料格式不認得（$msg，位置 $i）")

    private fun ws() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun eat(str: String): Boolean {
        ws()
        if (s.startsWith(str, i)) {
            i += str.length
            return true
        }
        return false
    }

    private fun value(): Any? {
        ws()
        if (i >= s.length) fail("資料沒有結束")
        if (s.startsWith("\$R[", i)) {
            i += 3
            val end = s.indexOf(']', i)
            val n = s.substring(i, end).toInt()
            i = end + 1
            // 「$R[n]=」是定義，不是「==」
            if (i < s.length && s[i] == '=' && s.getOrNull(i + 1) != '=') {
                i++
                val v = value()
                refs[n] = v
                return v
            }
            return refs[n]
        }
        when (s[i]) {
            '{' -> return obj()
            '[' -> return arr()
            '"', '\'' -> return str()
        }
        if (eat("!0")) return true
        if (eat("!1")) return false
        if (eat("null")) return null
        if (eat("void 0")) return null
        if (eat("true")) return true
        if (eat("false")) return false
        NUMBER.find(s, i)?.takeIf { it.range.first == i }?.let {
            i += it.value.length
            return it.value.toDouble()
        }
        if (eat("Infinity")) return Double.POSITIVE_INFINITY
        if (eat("NaN")) return Double.NaN
        fail("意外的字元 ${s.substring(i, minOf(s.length, i + 12))}")
    }

    private fun key(): String {
        ws()
        val c = s[i]
        if (c == '"' || c == '\'') return str()
        val m = KEY.find(s, i)?.takeIf { it.range.first == i } ?: fail("物件的鍵")
        i += m.value.length
        return m.value
    }

    private fun obj(): Map<String, Any?> {
        i++
        val o = LinkedHashMap<String, Any?>()
        if (eat("}")) return o
        while (true) {
            val k = key()
            if (!eat(":")) fail("缺少冒號")
            o[k] = value()
            if (eat(",")) continue
            if (eat("}")) return o
            fail("物件沒有結束")
        }
    }

    private fun arr(): List<Any?> {
        i++
        val a = ArrayList<Any?>()
        while (true) {
            ws()
            if (eat("]")) return a
            // 空位（[1,,2]）
            if (s[i] == ',') {
                i++
                a.add(null)
                continue
            }
            a.add(value())
            if (eat(",")) continue
            if (eat("]")) return a
            fail("陣列沒有結束")
        }
    }

    private fun str(): String {
        val q = s[i++]
        val out = StringBuilder()
        while (true) {
            if (i >= s.length) fail("字串沒有結束")
            val c = s[i++]
            if (c == q) return out.toString()
            if (c != '\\') {
                out.append(c)
                continue
            }
            when (val e = s[i++]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'v' -> out.append('\u000B')
                '0' -> out.append('\u0000')
                'x' -> {
                    out.append(s.substring(i, i + 2).toInt(16).toChar())
                    i += 2
                }
                'u' -> if (s[i] == '{') {
                    val end = s.indexOf('}', i)
                    out.appendCodePoint(s.substring(i + 1, end).toInt(16))
                    i = end + 1
                } else {
                    out.append(s.substring(i, i + 4).toInt(16).toChar())
                    i += 4
                }
                '\n' -> {} // 續行
                else -> out.append(e)
            }
        }
    }

    companion object {
        private const val MARK = "\$_TSR.router=(\$R=>"
        private val NUMBER = Regex("""-?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?""")
        private val KEY = Regex("""[A-Za-z_$][\w$]*|\d+""")

        /** 從頁面 HTML 取出這一頁的資料（最後一個路由的 loader 資料）；找不到回傳 null */
        @Suppress("UNCHECKED_CAST")
        fun pageData(html: String): Map<String, Any?>? {
            val at = html.indexOf(MARK)
            if (at < 0) return null
            val router = Tsr(html, at + MARK.length).value() as? Map<String, Any?> ?: return null
            val matches = router["matches"] as? List<Any?> ?: return emptyMap()
            for (m in matches.asReversed()) {
                val l = (m as? Map<String, Any?>)?.get("l") as? Map<String, Any?> ?: continue
                return l - "ads"
            }
            return emptyMap()
        }
    }
}
