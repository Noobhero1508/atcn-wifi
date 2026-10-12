package io.github.noobhero1508.atcnwifi.core

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class PortalForm(
    val id: String,
    val name: String,
    val action: String,
    val method: String,
    val fields: LinkedHashMap<String, String>,
)

/** Pure parsing helpers kept independent from Android so local JVM tests exercise real behavior. */
internal object PortalParsers {
    private val wifiAssignment = Regex(
        """\b(?:const|let|var)\s+wifiInfo\s*=\s*""",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val propertyPattern = Regex(
        """(?<key>"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[A-Za-z_$][\w$-]*)\s*:\s*(?<value>"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|true|false|null|-?\d+(?:\.\d+)?)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val serialPattern = Regex(
        """serial(?:=|["']?\s*:\s*["']?)\s*((?:[0-9A-F]{2}:){5}[0-9A-F]{2})""",
        RegexOption.IGNORE_CASE,
    )
    private val exactSerialPattern = Regex("""(?:[0-9A-F]{2}:){5}[0-9A-F]{2}""", RegexOption.IGNORE_CASE)

    fun extractWifiInfo(html: String): Map<String, Any?>? {
        val assignment = wifiAssignment.find(html) ?: return null
        val brace = html.indexOf('{', assignment.range.last + 1)
        if (brace < 0) return null
        val objectText = extractBalancedObject(html, brace).takeIf { it.isNotEmpty() } ?: return null

        val result = linkedMapOf<String, Any?>()
        for (match in propertyPattern.findAll(objectText)) {
            val rawKey = match.groups["key"]?.value ?: continue
            val rawValue = match.groups["value"]?.value ?: continue
            val key = normalizeKey(
                if (rawKey.startsWith('"') || rawKey.startsWith('\'')) decodeJsString(rawKey) else rawKey,
            )
            val value: Any? = when {
                rawValue.startsWith('"') || rawValue.startsWith('\'') -> decodeJsString(rawValue)
                rawValue.equals("true", ignoreCase = true) -> true
                rawValue.equals("false", ignoreCase = true) -> false
                rawValue.equals("null", ignoreCase = true) -> null
                else -> rawValue
            }
            result[key] = value
        }
        return result.takeIf { it.isNotEmpty() }
    }

    fun decodeJsString(token: String): String {
        if (token.length < 2 || token.first() !in charArrayOf('\'', '"')) return token
        val text = token.substring(1, token.length - 1)
        val output = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char != '\\') {
                output.append(char)
                index += 1
                continue
            }
            index += 1
            if (index >= text.length) {
                output.append('\\')
                break
            }
            val escaped = text[index]
            when (escaped) {
                '\\', '"', '\'' -> {
                    output.append(escaped)
                    index += 1
                }
                '/' -> {
                    output.append('/')
                    index += 1
                }
                'b' -> { output.append('\b'); index += 1 }
                'f' -> { output.append('\u000C'); index += 1 }
                'n' -> { output.append('\n'); index += 1 }
                'r' -> { output.append('\r'); index += 1 }
                't' -> { output.append('\t'); index += 1 }
                'v' -> { output.append('\u000B'); index += 1 }
                'x' -> {
                    val digits = text.safeSlice(index + 1, index + 3)
                    val decoded = digits.takeIf { it.length == 2 }?.toIntOrNull(16)
                    if (decoded == null) {
                        output.append('x')
                        index += 1
                    } else {
                        output.append(decoded.toChar())
                        index += 3
                    }
                }
                'u' -> {
                    val digits = text.safeSlice(index + 1, index + 5)
                    val decoded = digits.takeIf { it.length == 4 }?.toIntOrNull(16)
                    if (decoded == null) {
                        output.append('u')
                        index += 1
                    } else {
                        output.append(decoded.toChar())
                        index += 5
                    }
                }
                in '0'..'7' -> {
                    var end = index + 1
                    while (end < text.length && end < index + 3 && text[end] in '0'..'7') end += 1
                    output.append(text.substring(index, end).toInt(8).toChar())
                    index = end
                }
                '\r', '\n' -> {
                    if (escaped == '\r' && index + 1 < text.length && text[index + 1] == '\n') index += 1
                    index += 1
                }
                else -> {
                    // JavaScript treats an unknown string escape as the escaped character.
                    output.append(escaped)
                    index += 1
                }
            }
        }
        return output.toString()
    }

    fun infoValue(info: Map<String, Any?>, vararg names: String): Any? {
        for (name in names) {
            val value = info[normalizeKey(name)]
            if (value != null && value.toString().isNotEmpty()) return value
        }
        return null
    }

    fun isLoggedIn(info: Map<String, Any?>): Boolean {
        val value = infoValue(info, "logged_in", "logged-in", "authenticated")
            ?.toString()?.lowercase(Locale.ROOT)
        return value in setOf("yes", "true", "1", "logged in", "connected")
    }

    fun discoverSerial(html: String, info: Map<String, Any?>?, fallback: String): String {
        val fromInfo = info?.let { infoValue(it, "serial", "router_serial", "gateway_serial") }
            ?.toString().orEmpty()
        if (exactSerialPattern.matches(fromInfo)) return fromInfo.uppercase(Locale.ROOT)

        val decoded = runCatching { URLDecoder.decode(html, StandardCharsets.UTF_8.name()) }.getOrDefault(html)
        return serialPattern.find(decoded)?.groupValues?.get(1)?.uppercase(Locale.ROOT) ?: fallback
    }

    fun toUrlOctal(value: Any?): String = buildString {
        value?.toString()?.forEach { char ->
            append('\\')
            append(char.code.toString(8).padStart(3, '0'))
        }
    }

    fun chooseLoginForm(html: String, baseUrl: String): PortalForm? {
        val forms = parseForms(html)
        val candidates = forms.filter {
            it.method.equals("post", ignoreCase = true) &&
                it.action.isNotBlank() &&
                it.fields.containsKey("username") &&
                it.fields.containsKey("password")
        }
        val selected = candidates.firstOrNull {
            it.id.equals("frmlogin", ignoreCase = true) || it.name.equals("mikrotik", ignoreCase = true)
        } ?: candidates.firstOrNull() ?: return null
        val resolved = runCatching { URI(baseUrl).resolve(selected.action).toString() }.getOrNull() ?: return null
        return selected.copy(action = resolved)
    }

    fun isAllowedFormAction(action: String, allowedHosts: Set<String>): Boolean {
        val uri = runCatching { URI(action) }.getOrNull() ?: return false
        return uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            allowedHosts.any { it.equals(uri.host, ignoreCase = true) }
    }

    fun maskSerial(serial: String?): String? {
        if (serial == null || !exactSerialPattern.matches(serial)) return null
        val parts = serial.split(':').toMutableList()
        parts[2] = "**"
        parts[3] = "**"
        return parts.joinToString(":")
    }

    private fun extractBalancedObject(text: String, braceIndex: Int): String {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (index in braceIndex until text.length) {
            val char = text[index]
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == quote -> quote = null
                }
                continue
            }
            when (char) {
                '\'', '"', '`' -> quote = char
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(braceIndex, index + 1)
                }
            }
        }
        return ""
    }

    private fun parseForms(html: String): List<PortalForm> {
        val forms = mutableListOf<PortalForm>()
        var current: MutableForm? = null
        for (tag in scanTags(html)) {
            when {
                tag.closing && tag.name.equals("form", ignoreCase = true) -> {
                    current?.let { forms += it.freeze() }
                    current = null
                }
                !tag.closing && tag.name.equals("form", ignoreCase = true) -> {
                    current?.let { forms += it.freeze() }
                    val attrs = parseAttributes(tag.attributes)
                    current = MutableForm(
                        id = attrs["id"].orEmpty(),
                        name = attrs["name"].orEmpty(),
                        action = attrs["action"].orEmpty(),
                        method = attrs["method"]?.lowercase(Locale.ROOT) ?: "get",
                    )
                }
                !tag.closing && tag.name.equals("input", ignoreCase = true) && current != null -> {
                    val attrs = parseAttributes(tag.attributes)
                    val name = attrs["name"]
                    if (!name.isNullOrBlank()) current.fields[name] = attrs["value"].orEmpty()
                }
            }
        }
        current?.let { forms += it.freeze() }
        return forms
    }

    private data class HtmlTag(val name: String, val attributes: String, val closing: Boolean)

    private fun scanTags(html: String): Sequence<HtmlTag> = sequence {
        var cursor = 0
        while (cursor < html.length) {
            val start = html.indexOf('<', cursor)
            if (start < 0) break
            if (html.startsWith("<!--", start)) {
                val commentEnd = html.indexOf("-->", start + 4)
                cursor = if (commentEnd < 0) html.length else commentEnd + 3
                continue
            }
            var end = start + 1
            var quote: Char? = null
            while (end < html.length) {
                val char = html[end]
                if (quote == null && char in charArrayOf('\'', '"')) quote = char
                else if (quote == char) quote = null
                else if (quote == null && char == '>') break
                end += 1
            }
            if (end >= html.length) break
            val inside = html.substring(start + 1, end).trim()
            val closing = inside.startsWith('/')
            val content = inside.removePrefix("/").trimStart()
            val nameEnd = content.indexOfFirst { it.isWhitespace() || it == '/' }.let {
                if (it < 0) content.length else it
            }
            if (nameEnd > 0) {
                yield(HtmlTag(content.substring(0, nameEnd), content.substring(nameEnd), closing))
            }
            cursor = end + 1
        }
    }

    private fun parseAttributes(source: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < source.length) {
            while (index < source.length && (source[index].isWhitespace() || source[index] == '/')) index += 1
            if (index >= source.length) break
            val nameStart = index
            while (index < source.length && !source[index].isWhitespace() && source[index] !in charArrayOf('=', '/', '>')) index += 1
            val name = source.substring(nameStart, index).lowercase(Locale.ROOT)
            while (index < source.length && source[index].isWhitespace()) index += 1
            var value = ""
            if (index < source.length && source[index] == '=') {
                index += 1
                while (index < source.length && source[index].isWhitespace()) index += 1
                if (index < source.length && source[index] in charArrayOf('\'', '"')) {
                    val quote = source[index++]
                    val valueStart = index
                    while (index < source.length && source[index] != quote) index += 1
                    value = source.substring(valueStart, index.coerceAtMost(source.length))
                    if (index < source.length) index += 1
                } else {
                    val valueStart = index
                    while (index < source.length && !source[index].isWhitespace() && source[index] != '>') index += 1
                    value = source.substring(valueStart, index)
                }
            }
            if (name.isNotEmpty()) result[name] = decodeHtmlEntities(value)
        }
        return result
    }

    private fun decodeHtmlEntities(value: String): String = Regex("&(?:#x[0-9a-fA-F]+|#\\d+|amp|quot|apos|lt|gt);")
        .replace(value) { match ->
            when (val token = match.value.substring(1, match.value.length - 1)) {
                "amp" -> "&"
                "quot" -> "\""
                "apos" -> "'"
                "lt" -> "<"
                "gt" -> ">"
                else -> {
                    val radix = if (token.startsWith("#x", ignoreCase = true)) 16 else 10
                    val digits = if (radix == 16) token.substring(2) else token.substring(1)
                    digits.toIntOrNull(radix)?.let { String(Character.toChars(it)) } ?: match.value
                }
            }
        }

    private fun normalizeKey(value: String): String = value.trim().lowercase(Locale.ROOT).replace('-', '_')

    private fun String.safeSlice(start: Int, endExclusive: Int): String {
        if (start !in 0..length || endExclusive < start) return ""
        return substring(start, endExclusive.coerceAtMost(length))
    }

    private data class MutableForm(
        val id: String,
        val name: String,
        val action: String,
        val method: String,
        val fields: LinkedHashMap<String, String> = linkedMapOf(),
    ) {
        fun freeze() = PortalForm(id, name, action, method, LinkedHashMap(fields))
    }
}

internal object PortalEncoding {
    fun formEncode(fields: Map<String, String>): ByteArray = fields.entries.joinToString("&") {
        "${formComponent(it.key)}=${formComponent(it.value)}"
    }.toByteArray(StandardCharsets.UTF_8)

    fun queryEncode(fields: Map<String, String>): String = fields.entries.joinToString("&") {
        "${percentEncode(it.key)}=${percentEncode(it.value)}"
    }

    fun percentEncode(value: String): String = buildString {
        for (byte in value.toByteArray(StandardCharsets.UTF_8)) {
            val unsigned = byte.toInt() and 0xff
            val char = unsigned.toChar()
            if (unsigned in 'a'.code..'z'.code ||
                unsigned in 'A'.code..'Z'.code ||
                unsigned in '0'.code..'9'.code ||
                char in charArrayOf('-', '.', '_', '~')
            ) {
                append(char)
            } else {
                append('%')
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0f])
            }
        }
    }

    private fun formComponent(value: String): String = percentEncode(value).replace("%20", "+")

    private const val HEX = "0123456789ABCDEF"
}
