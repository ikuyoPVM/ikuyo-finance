package xyz.ikuyo.finance.core

/**
 * 极小 JSON 实现。
 *
 * 为什么不用 org.json：JVM 单元测试里 android.jar 的 org.json 只有空实现（调用即
 * "not mocked"），而我们希望解析逻辑（模型返回、请求体拼装）能在纯 JVM 单测里跑。
 * 也不用 kotlinx.serialization：需要额外编译器插件与依赖，收益不成比例。
 *
 * 只做两件事：解析（含全部转义）与序列化。没有反射、没有 schema、没有流式 API。
 */
sealed interface JsonValue {

    data class Obj(val fields: LinkedHashMap<String, JsonValue> = LinkedHashMap()) : JsonValue {
        operator fun get(key: String): JsonValue? = fields[key]
        fun str(key: String): String? = (fields[key] as? Str)?.value
        fun num(key: String): Double? = (fields[key] as? Num)?.value
        fun int(key: String): Int? = num(key)?.toInt()
        fun long(key: String): Long? = num(key)?.toLong()
        fun bool(key: String): Boolean? = (fields[key] as? Bool)?.value
        fun obj(key: String): Obj? = fields[key] as? Obj
        fun arr(key: String): Arr? = fields[key] as? Arr
        fun put(key: String, value: JsonValue?): Obj {
            if (value == null) fields.remove(key) else fields[key] = value
            return this
        }
        fun put(key: String, value: String?): Obj =
            if (value == null) this else put(key, Str(value))
    }

    data class Arr(val items: List<JsonValue> = emptyList()) : JsonValue {
        val size: Int get() = items.size
        operator fun get(index: Int): JsonValue? = items.getOrNull(index)
    }

    data class Str(val value: String) : JsonValue {
        override fun toString(): String = value
    }

    /** [raw] 保留原始数字文本，避免 100 变成 100.0 影响可读性与快照稳定性。 */
    data class Num(val value: Double, val raw: String) : JsonValue {
        override fun toString(): String = raw
    }

    data class Bool(val value: Boolean) : JsonValue {
        override fun toString(): String = value.toString()
    }

    data object Null : JsonValue {
        override fun toString(): String = "null"
    }
}

/** JSON 语法错误。消息只描述位置，不回显原文（原文可能是用户账单内容）。 */
class JsonParseException(message: String) : IllegalArgumentException(message)

object Json {

    fun parseOrNull(text: String?): JsonValue? =
        try {
            if (text == null) null else Parser(text).parseDocument()
        } catch (_: JsonParseException) {
            null
        }

    fun obj(vararg pairs: Pair<String, JsonValue?>): JsonValue.Obj {
        val map = LinkedHashMap<String, JsonValue>()
        pairs.forEach { (key, value) -> if (value != null) map[key] = value }
        return JsonValue.Obj(map)
    }

    fun arr(values: List<JsonValue>): JsonValue.Arr = JsonValue.Arr(values)

    fun str(value: String): JsonValue.Str = JsonValue.Str(value)

    fun num(value: Long): JsonValue.Num = JsonValue.Num(value.toDouble(), value.toString())

    fun num(value: Double): JsonValue.Num = JsonValue.Num(value, formatNumber(value))

    fun bool(value: Boolean): JsonValue.Bool = JsonValue.Bool(value)

    fun dumps(value: JsonValue): String = StringBuilder().also { write(value, it) }.toString()

    /** 从模型回复里抽出 JSON 片段：允许 ```json 围栏、前后解释文字、外层数组或对象。 */
    fun extractJson(text: String?): JsonValue? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()
        parseOrNull(trimmed)?.let { return it }

        val fenceStart = trimmed.indexOf("```")
        if (fenceStart >= 0) {
            val afterFence = trimmed.indexOf('\n', fenceStart).let { if (it < 0) -1 else it + 1 }
            val fenceEnd = if (afterFence > 0) trimmed.indexOf("```", afterFence) else -1
            if (afterFence > 0 && fenceEnd > afterFence) {
                parseOrNull(trimmed.substring(afterFence, fenceEnd).trim())?.let { return it }
            }
            if (afterFence > 0) parseOrNull(trimmed.substring(afterFence).trim())?.let { return it }
        }

        val candidates = listOf('{', '[')
        for (open in candidates) {
            val start = trimmed.indexOf(open)
            if (start < 0) continue
            val close = if (open == '{') '}' else ']'
            val end = trimmed.lastIndexOf(close)
            if (end > start) parseOrNull(trimmed.substring(start, end + 1))?.let { return it }
        }
        return null
    }

    private fun formatNumber(value: Double): String =
        if (value == value.toLong().toDouble() && kotlin.math.abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            value.toString()
        }

    private fun write(value: JsonValue, out: StringBuilder) {
        when (value) {
            is JsonValue.Obj -> {
                out.append('{')
                var first = true
                value.fields.forEach { (key, item) ->
                    if (!first) out.append(',')
                    first = false
                    writeString(key, out)
                    out.append(':')
                    write(item, out)
                }
                out.append('}')
            }
            is JsonValue.Arr -> {
                out.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
            is JsonValue.Str -> writeString(value.value, out)
            is JsonValue.Num -> out.append(value.raw)
            is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
            JsonValue.Null -> out.append("null")
        }
    }

    private fun writeString(text: String, out: StringBuilder) {
        out.append('"')
        for (ch in text) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else ->
                    if (ch < ' ') {
                        out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        out.append(ch)
                    }
            }
        }
        out.append('"')
    }

    private class Parser(private val text: String) {

        private var pos = 0

        fun parseDocument(): JsonValue {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            if (pos != text.length) throw JsonParseException("trailing content at $pos")
            return value
        }

        private fun parseValue(): JsonValue {
            skipWhitespace()
            if (pos >= text.length) throw JsonParseException("unexpected end")
            return when (val ch = text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else ->
                    if (ch == '-' || ch in '0'..'9') parseNumber()
                    else throw JsonParseException("unexpected char at $pos")
            }
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, pos)) throw JsonParseException("bad literal at $pos")
            pos += word.length
            return value
        }

        private fun parseObject(): JsonValue.Obj {
            expect('{')
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                fields[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JsonValue.Obj(fields)
                    }
                    else -> throw JsonParseException("bad object at $pos")
                }
            }
        }

        private fun parseArray(): JsonValue.Arr {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return JsonValue.Arr(items)
            }
            while (true) {
                items += parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonValue.Arr(items)
                    }
                    else -> throw JsonParseException("bad array at $pos")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) throw JsonParseException("unterminated string")
                when (val ch = text[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= text.length) throw JsonParseException("bad escape")
                        when (val esc = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw JsonParseException("bad unicode escape")
                                val hex = text.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw JsonParseException("bad unicode escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw JsonParseException("bad escape \\$esc")
                        }
                    }
                    else -> sb.append(ch)
                }
            }
        }

        private fun parseNumber(): JsonValue.Num {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && text[pos].isDigit()) pos++
            if (pos < text.length && text[pos] == '.') {
                pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            val raw = text.substring(start, pos)
            val value = raw.toDoubleOrNull() ?: throw JsonParseException("bad number at $start")
            return JsonValue.Num(value, raw)
        }

        private fun peek(): Char = if (pos < text.length) text[pos] else '\u0000'

        private fun expect(ch: Char) {
            if (pos >= text.length || text[pos] != ch) throw JsonParseException("expected '$ch' at $pos")
            pos++
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }
}