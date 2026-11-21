package app.reader

/** A small, forgiving XML/XHTML reader: good enough for EPUB packages and chapter files. */
sealed class XNode

class XText(val text: String) : XNode()

class XElement(val name: String, val attrs: Map<String, String>) : XNode() {
    val children = mutableListOf<XNode>()
    val localName: String get() = name.substringAfter(':').lowercase()

    /** Attribute by full name, or by local name ignoring any namespace prefix. */
    fun attr(n: String): String? =
        attrs[n] ?: attrs.entries.firstOrNull { it.key.substringAfter(':').equals(n, true) }?.value

    fun elements(): List<XElement> = children.filterIsInstance<XElement>()
    fun elements(local: String): List<XElement> = elements().filter { it.localName == local }
    fun first(local: String): XElement? = descendants().firstOrNull { it.localName == local }
    fun child(local: String): XElement? = elements().firstOrNull { it.localName == local }

    fun descendants(): Sequence<XElement> = sequence {
        for (c in children) if (c is XElement) { yield(c); yieldAll(c.descendants()) }
    }

    fun text(): String = buildString { appendText(this@XElement, this) }

    private fun appendText(e: XElement, sb: StringBuilder) {
        for (c in e.children) when (c) {
            is XText -> sb.append(c.text)
            is XElement -> appendText(c, sb)
        }
    }

    /** epub:type values, space separated. */
    fun epubTypes(): List<String> =
        (attrs.entries.firstOrNull { it.key.endsWith(":type") || it.key == "epub:type" }?.value ?: "")
            .split(' ').filter { it.isNotBlank() }
}

object Xml {
    private val voidTags = setOf("br", "img", "hr", "meta", "link", "input", "col", "area", "base", "wbr", "source")

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return utf16(bytes, 2, bigEndian = true)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return utf16(bytes, 2, bigEndian = false)
        val s = bytes.decodeToString()
        return if (s.startsWith('﻿')) s.substring(1) else s
    }

    private fun utf16(b: ByteArray, start: Int, bigEndian: Boolean): String {
        val sb = StringBuilder()
        var i = start
        while (i + 1 < b.size) {
            val hi = b[i].toInt() and 0xFF; val lo = b[i + 1].toInt() and 0xFF
            sb.append((if (bigEndian) (hi shl 8) or lo else (lo shl 8) or hi).toChar())
            i += 2
        }
        return sb.toString()
    }

    fun parse(bytes: ByteArray): XElement = parse(decode(bytes))

    fun parse(src: String): XElement {
        val root = XElement("#document", emptyMap())
        val stack = ArrayList<XElement>().apply { add(root) }
        var i = 0
        val n = src.length
        val text = StringBuilder()

        fun flushText() {
            if (text.isNotEmpty()) { stack.last().children.add(XText(decodeEntities(text.toString()))); text.clear() }
        }

        while (i < n) {
            val c = src[i]
            if (c != '<') { text.append(c); i++; continue }
            when {
                src.startsWith("<!--", i) -> { i = src.indexOf("-->", i + 4).let { if (it < 0) n else it + 3 } }
                src.startsWith("<![CDATA[", i) -> {
                    val end = src.indexOf("]]>", i + 9).let { if (it < 0) n else it }
                    flushText(); stack.last().children.add(XText(src.substring(i + 9, end))); i = end + 3
                }
                src.startsWith("<?", i) -> { i = src.indexOf("?>", i + 2).let { if (it < 0) n else it + 2 } }
                src.startsWith("<!", i) -> {
                    var depth = 0; var j = i + 2
                    while (j < n) {
                        val cj = src[j]
                        if (cj == '[') depth++ else if (cj == ']') depth-- else if (cj == '>' && depth <= 0) break
                        j++
                    }
                    i = j + 1
                }
                i + 1 < n && src[i + 1] == '/' -> {
                    flushText()
                    val end = src.indexOf('>', i).let { if (it < 0) n else it }
                    val name = src.substring(i + 2, end).trim().lowercase()
                    val idx = stack.indexOfLast { it.name.lowercase() == name }
                    if (idx > 0) while (stack.size > idx) stack.removeAt(stack.size - 1)
                    i = end + 1
                }
                i + 1 < n && (src[i + 1].isLetter() || src[i + 1] == '_') -> {
                    flushText()
                    var j = i + 1
                    while (j < n && !src[j].isWhitespace() && src[j] != '>' && src[j] != '/') j++
                    val name = src.substring(i + 1, j)
                    val attrs = LinkedHashMap<String, String>()
                    var selfClose = false
                    while (j < n) {
                        while (j < n && src[j].isWhitespace()) j++
                        if (j >= n) break
                        if (src[j] == '>') { j++; break }
                        if (src[j] == '/') { selfClose = true; j++; continue }
                        val ks = j
                        while (j < n && !src[j].isWhitespace() && src[j] != '=' && src[j] != '>' && src[j] != '/') j++
                        val key = src.substring(ks, j)
                        while (j < n && src[j].isWhitespace()) j++
                        var value = ""
                        if (j < n && src[j] == '=') {
                            j++
                            while (j < n && src[j].isWhitespace()) j++
                            if (j < n && (src[j] == '"' || src[j] == '\'')) {
                                val q = src[j]; val vs = j + 1
                                val ve = src.indexOf(q, vs).let { if (it < 0) n else it }
                                value = src.substring(vs, ve); j = ve + 1
                            } else {
                                val vs = j
                                while (j < n && !src[j].isWhitespace() && src[j] != '>') j++
                                value = src.substring(vs, j)
                            }
                        }
                        if (key.isNotEmpty()) attrs[key] = decodeEntities(value)
                    }
                    val el = XElement(name, attrs)
                    stack.last().children.add(el)
                    val lname = name.lowercase()
                    if (!selfClose && lname !in voidTags) {
                        if (lname == "script" || lname == "style") {
                            val close = src.indexOf("</", j).let { if (it < 0) n else it }
                            el.children.add(XText(src.substring(j, close)))
                            j = close
                            val e2 = src.indexOf('>', j).let { if (it < 0) n else it }
                            j = e2 + 1
                        } else stack.add(el)
                    }
                    i = j
                }
                else -> { text.append(c); i++ }
            }
        }
        flushText()
        return root
    }

    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’",
        "ldquo" to "“", "rdquo" to "”", "copy" to "©", "reg" to "®", "trade" to "™",
        "laquo" to "«", "raquo" to "»", "bull" to "•", "middot" to "·", "shy" to "­",
        "eacute" to "é", "egrave" to "è", "agrave" to "à", "aacute" to "á", "ccedil" to "ç", "ouml" to "ö",
        "uuml" to "ü", "auml" to "ä", "szlig" to "ß", "iacute" to "í", "oacute" to "ó", "uacute" to "ú",
        "ntilde" to "ñ", "times" to "×", "deg" to "°", "para" to "¶", "sect" to "§", "thinsp" to " ",
        "ensp" to " ", "emsp" to " ", "zwnj" to "‌", "zwj" to "‍", "dagger" to "†",
    )

    fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i)
                if (semi in (i + 2)..(i + 12)) {
                    val ent = s.substring(i + 1, semi)
                    val rep: String? = when {
                        ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let { codePoint(it) }
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { codePoint(it) }
                        else -> named[ent]
                    }
                    if (rep != null) { sb.append(rep); i = semi + 1; continue }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    private fun codePoint(cp: Int): String =
        if (cp < 0x10000) cp.toChar().toString()
        else {
            val v = cp - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
}
