package xyz.ikuyo.finance.core

/**
 * 极简「一层 JSON 对象」编解码器 —— 用于保存账单操作快照。
 *
 * 为什么不用 org.json：它在 JVM 单元测试里是空实现（调用即抛
 * "not mocked"），而操作历史的编解码必须在纯 Kotlin 单测里可验证。
 * 这里只支持 { "key": "value" | 数字 | true/false | null }，足够保存快照，
 * 且实现小到可以完全读一遍。
 */
object FlatJson {

    fun encode(values: Map<String, String?>): String {
        val body = values.entries.joinToString(",") { (key, value) ->
            val encodedKey = quote(key)
            if (value == null) "$encodedKey:null" else "$encodedKey:${quote(value)}"
        }
        return "{$body}"
    }

    /** 解析失败返回 null（坏数据不应让界面崩溃）。 */
    fun decode(text: String): Map<String, String?>? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val content = trimmed.substring(1, trimmed.length - 1)
        if (content.isBlank()) return emptyMap()

        val result = LinkedHashMap<String, String?>()
        var index = 0
        while (index < content.length) {
            while (index < content.length && (content[index] == ',' || content[index].isWhitespace())) index++
            if (index >= content.length) break
            val keyResult = readString(content, index) ?: return null
            index = keyResult.second
            while (index < content.length && (content[index].isWhitespace() || content[index] == ':')) index++
            if (index >= content.length) return null

            if (content[index] == '"') {
                val valueResult = readString(content, index) ?: return null
                result[keyResult.first] = valueResult.first
                index = valueResult.second
            } else {
                val start = index
                while (index < content.length && content[index] != ',') index++
                val raw = content.substring(start, index).trim()
                result[keyResult.first] = if (raw == "null") null else raw
            }
        }
        return result
    }

    private fun quote(value: String): String {
        val builder = StringBuilder(value.length + 2)
        builder.append('"')
        for (char in value) {
            when (char) {
                '\\' -> builder.append("\\\\")
                '"' -> builder.append("\\\"")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> builder.append(char)
            }
        }
        builder.append('"')
        return builder.toString()
    }

    /** 返回 (值, 结束位置)；[start] 必须指向开引号。 */
    private fun readString(text: String, start: Int): Pair<String, Int>? {
        if (start >= text.length || text[start] != '"') return null
        val builder = StringBuilder()
        var index = start + 1
        while (index < text.length) {
            when (val char = text[index]) {
                '"' -> return builder.toString() to (index + 1)
                '\\' -> {
                    index++
                    if (index >= text.length) return null
                    when (val escaped = text[index]) {
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        '\\' -> builder.append('\\')
                        '"' -> builder.append('"')
                        'u' -> {
                            if (index + 4 >= text.length) return null
                            val hex = text.substring(index + 1, index + 5)
                            builder.append(hex.toInt(16).toChar())
                            index += 4
                        }
                        else -> builder.append(escaped)
                    }
                    index++
                }
                else -> {
                    builder.append(char)
                    index++
                }
            }
        }
        return null
    }
}