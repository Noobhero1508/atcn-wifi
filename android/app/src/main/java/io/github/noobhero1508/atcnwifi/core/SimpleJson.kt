package io.github.noobhero1508.atcnwifi.core

/** Small dependency-free JSON tree used for AWING DTO round-tripping in both Android and JVM tests. */
internal sealed class JsonValue {
    data class ObjectValue(val values: LinkedHashMap<String, JsonValue>) : JsonValue() {
        operator fun get(key: String): JsonValue? = values[key]
        operator fun set(key: String, value: JsonValue) { values[key] = value }
    }
    data class ArrayValue(val values: MutableList<JsonValue>) : JsonValue()
    data class StringValue(val value: String) : JsonValue()
    data class NumberValue(val raw: String) : JsonValue()
    data class BooleanValue(val value: Boolean) : JsonValue()
    data object NullValue : JsonValue()
}
internal object SimpleJson {
    fun parse(source: String): JsonValue {
        val parser = Parser(source.removePrefix("\uFEFF"))
        val value = parser.parseValue()
        parser.skipWhitespace()
        require(parser.atEnd()) { "Unexpected trailing JSON content" }
        return value
    }

    fun stringify(value: JsonValue): String = buildString { appendValue(value) }

    fun string(value: JsonValue?): String? = (value as? JsonValue.StringValue)?.value
    fun obj(value: JsonValue?): JsonValue.ObjectValue? = value as? JsonValue.ObjectValue

    private fun StringBuilder.appendValue(value: JsonValue) {
        when (value) {
            is JsonValue.ObjectValue -> {
                append('{')
                value.values.entries.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    appendQuoted(entry.key)
                    append(':')
                    appendValue(entry.value)
                }
                append('}')
            }
            is JsonValue.ArrayValue -> {
                append('[')
                value.values.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendValue(item)
                }
                append(']')
            }
            is JsonValue.StringValue -> appendQuoted(value.value)
            is JsonValue.NumberValue -> append(value.raw)
            is JsonValue.BooleanValue -> append(if (value.value) "true" else "false")
            JsonValue.NullValue -> append("null")
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else append(char)
            }
        }
        append('"')
    }

    private class Parser(private val source: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= source.length

        fun skipWhitespace() {
            while (index < source.length && source[index].isWhitespace()) index += 1
        }

        fun parseValue(): JsonValue {
            skipWhitespace()
            require(index < source.length) { "Unexpected end of JSON" }
            return when (source[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.StringValue(parseString())
                't' -> { expect("true"); JsonValue.BooleanValue(true) }
                'f' -> { expect("false"); JsonValue.BooleanValue(false) }
                'n' -> { expect("null"); JsonValue.NullValue }
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected JSON token")
            }
        }

        private fun parseObject(): JsonValue.ObjectValue {
            index += 1
            val result = linkedMapOf<String, JsonValue>()
            skipWhitespace()
            if (take('}')) return JsonValue.ObjectValue(result)
            while (true) {
                skipWhitespace()
                require(index < source.length && source[index] == '"') { "JSON object key must be a string" }
                val key = parseString()
                skipWhitespace()
                require(take(':')) { "Missing ':' in JSON object" }
                result[key] = parseValue()
                skipWhitespace()
                if (take('}')) break
                require(take(',')) { "Missing ',' in JSON object" }
            }
            return JsonValue.ObjectValue(result)
        }

        private fun parseArray(): JsonValue.ArrayValue {
            index += 1
            val result = mutableListOf<JsonValue>()
            skipWhitespace()
            if (take(']')) return JsonValue.ArrayValue(result)
            while (true) {
                result += parseValue()
                skipWhitespace()
                if (take(']')) break
                require(take(',')) { "Missing ',' in JSON array" }
            }
            return JsonValue.ArrayValue(result)
        }

        private fun parseString(): String {
            require(take('"')) { "Expected JSON string" }
            val output = StringBuilder()
            while (index < source.length) {
                val char = source[index++]
                if (char == '"') return output.toString()
                if (char != '\\') {
                    require(char.code >= 0x20) { "Unescaped control character in JSON string" }
                    output.append(char)
                    continue
                }
                require(index < source.length) { "Incomplete JSON escape" }
                when (val escaped = source[index++]) {
                    '"', '\\', '/' -> output.append(escaped)
                    'b' -> output.append('\b')
                    'f' -> output.append('\u000C')
                    'n' -> output.append('\n')
                    'r' -> output.append('\r')
                    't' -> output.append('\t')
                    'u' -> {
                        require(index + 4 <= source.length) { "Incomplete JSON unicode escape" }
                        val digits = source.substring(index, index + 4)
                        val decoded = digits.toIntOrNull(16)
                            ?: throw IllegalArgumentException("Invalid JSON unicode escape")
                        output.append(decoded.toChar())
                        index += 4
                    }
                    else -> throw IllegalArgumentException("Invalid JSON escape")
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }

        private fun parseNumber(): JsonValue.NumberValue {
            val start = index
            if (source[index] == '-') index += 1
            require(index < source.length) { "Invalid JSON number" }
            if (source[index] == '0') index += 1 else {
                require(source[index] in '1'..'9') { "Invalid JSON number" }
                while (index < source.length && source[index].isDigit()) index += 1
            }
            if (index < source.length && source[index] == '.') {
                index += 1
                require(index < source.length && source[index].isDigit()) { "Invalid JSON number" }
                while (index < source.length && source[index].isDigit()) index += 1
            }
            if (index < source.length && source[index] in charArrayOf('e', 'E')) {
                index += 1
                if (index < source.length && source[index] in charArrayOf('+', '-')) index += 1
                require(index < source.length && source[index].isDigit()) { "Invalid JSON exponent" }
                while (index < source.length && source[index].isDigit()) index += 1
            }
            return JsonValue.NumberValue(source.substring(start, index))
        }

        private fun expect(token: String) {
            require(source.regionMatches(index, token, 0, token.length)) { "Unexpected JSON token" }
            index += token.length
        }

        private fun take(expected: Char): Boolean {
            if (index >= source.length || source[index] != expected) return false
            index += 1
            return true
        }
    }
}
