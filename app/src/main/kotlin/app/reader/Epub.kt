package app.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em

/** What a chapter is made of after its XHTML has been read. */
sealed class Block {
    enum class Kind { Body, Heading, Quote, Pre, ListItem, Caption }

    class Text(
        val text: AnnotatedString,
        val kind: Kind,
        val level: Int = 0,
        val align: TextAlign? = null,
        val anchors: List<String> = emptyList(),
        /** From the book's stylesheet, in em; null = the reader's own spacing. */
        val textIndent: Float? = null,
        val marginTop: Float? = null,
        val marginBottom: Float? = null,
    ) : Block()

    class Picture(val path: String, val alt: String, val anchors: List<String> = emptyList()) : Block()
    object Rule : Block()
}

class Chapter(val path: String, val title: String, val blocks: List<Block>, val linear: Boolean)

class TocEntry(val title: String, val path: String, val fragment: String?, val level: Int)

class EpubBook(
    val title: String,
    val author: String,
    val language: String,
    val files: Map<String, ByteArray>,
    val chapters: List<Chapter>,
    val toc: List<TocEntry>,
    val coverPath: String?,
    val rightToLeft: Boolean,
    val fixedLayout: Boolean,
    val footnotes: Map<String, String>,
) {
    fun chapterIndexFor(path: String): Int = chapters.indexOfFirst { it.path == path }
}

class EpubException(message: String) : Exception(message)

/**
 * Reads an EPUB in the order the format lays it out:
 * mimetype → META-INF/container.xml → OPF (metadata, manifest, spine) → nav/NCX table of contents.
 */
/** What the library shows for a book, read without parsing any chapter. */
class BookSummary(val title: String, val author: String, val coverPath: String?)

object EpubParser {
    /** The package (OPF) parts both [summary] and [parse] need. */
    private class Pkg(
        val title: String, val author: String, val language: String, val coverPath: String?, val fixedLayout: Boolean,
        val rtl: Boolean, val spine: List<Pair<Item, Boolean>>, val manifest: Map<String, Item>, val spineToc: String?,
    )
    private class Item(val id: String, val path: String, val mediaType: String, val properties: List<String>)

    /**
     * Title, author and cover path only: reads container.xml and the OPF through [read] and nothing
     * else, so a whole library can be listed without unpacking any book.
     */
    fun summary(read: (String) -> ByteArray?, names: Collection<String>, fallbackTitle: String = "Untitled"): BookSummary {
        val p = pkg(read, names, fallbackTitle)
        return BookSummary(p.title, p.author, p.coverPath)
    }

    /** The whole book, from its bytes. */
    fun parse(bytes: ByteArray, fallbackTitle: String = "Untitled"): EpubBook {
        val files = try { unzipEntries(bytes) } catch (e: Exception) { throw EpubException("This file isn't a readable EPUB (not a ZIP archive).") }
        return parse(files, fallbackTitle)
    }

    fun parse(files: Map<String, ByteArray>, fallbackTitle: String = "Untitled"): EpubBook {
        if (files.isEmpty()) throw EpubException("This EPUB is empty.")
        val p = pkg({ files[it] }, files.keys, fallbackTitle)
        val title = p.title
        val manifest = p.manifest
        val spine = p.spine

        // 6. table of contents: EPUB 3 nav first, else NCX
        val toc = mutableListOf<TocEntry>()
        manifest.values.firstOrNull { "nav" in it.properties }?.let { nav ->
            files[nav.path]?.let { readNav(Xml.parse(it), nav.path.substringBeforeLast('/', ""), toc) }
        }
        if (toc.isEmpty()) {
            val ncxId = p.spineToc
            val ncx = (ncxId?.let { manifest[it] } ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" })
            ncx?.let { item -> files[item.path]?.let { readNcx(Xml.parse(it), item.path.substringBeforeLast('/', ""), toc) } }
        }

        // chapters
        val footnotes = LinkedHashMap<String, String>()
        val cssCache = HashMap<String, List<CssRule>>()
        val chapters = spine.mapIndexedNotNull { idx, (item, linear) ->
            val data = files[item.path] ?: return@mapIndexedNotNull null
            if (!item.mediaType.contains("html") && !item.path.endsWith("html", true) && !item.path.endsWith(".htm", true)) return@mapIndexedNotNull null
            val doc = Xml.parse(data)
            val chapterDir = item.path.substringBeforeLast('/', "")
            val rules = stylesheetRules(doc, chapterDir, files, cssCache)
            val blocks = ContentReader(chapterDir, item.path, footnotes, rules).read(doc)
            val tocTitle = toc.firstOrNull { it.path == item.path }?.title
            val heading = blocks.firstOrNull { it is Block.Text && it.kind == Block.Kind.Heading } as Block.Text?
            val docTitle = doc.first("title")?.text()?.trim()?.takeIf { it.isNotBlank() && it != title }
            Chapter(item.path, tocTitle ?: heading?.text?.text?.trim()?.take(60) ?: docTitle ?: "Section ${idx + 1}", blocks, linear)
        }.filter { it.linear } // reading order uses linear documents only
        if (chapters.isEmpty()) throw EpubException("This EPUB has no readable chapters.")

        return EpubBook(title, p.author, p.language, files, chapters, toc, p.coverPath, p.rtl, p.fixedLayout, footnotes)
    }

    private fun pkg(read: (String) -> ByteArray?, names: Collection<String>, fallbackTitle: String): Pkg {
        if (names.isEmpty()) throw EpubException("This EPUB is empty.")
        read("META-INF/encryption.xml")?.let { bytes ->
            val enc = Xml.parse(bytes)
            val encrypted = enc.descendants().any { it.localName == "encrypteddata" &&
                it.descendants().none { a -> a.localName == "encryptionmethod" && (a.attr("Algorithm") ?: "").contains("obfuscation", true) } }
            if (encrypted) throw EpubException("This book is protected with DRM and can't be opened.")
        }

        // 1-2. container.xml → rootfile full-path
        val container = read("META-INF/container.xml")?.let { Xml.parse(it) }
        val opfPath = container?.descendants()
            ?.filter { it.localName == "rootfile" }
            ?.sortedByDescending { it.attr("media-type") == "application/oebps-package+xml" }
            ?.firstOrNull()?.attr("full-path")
            ?: names.firstOrNull { it.endsWith(".opf", true) }
            ?: throw EpubException("This EPUB has no package (.opf) file.")
        val opfBytes = read(opfPath) ?: throw EpubException("The package file $opfPath is missing.")
        val opf = Xml.parse(opfBytes)
        val baseDir = opfPath.substringBeforeLast('/', "")

        // 3. metadata
        val metadata = opf.first("metadata")
        val title = metadata?.descendants()?.firstOrNull { it.localName == "title" }?.text()?.trim()?.ifBlank { null } ?: fallbackTitle
        val author = metadata?.descendants()?.firstOrNull { it.localName == "creator" }?.text()?.trim() ?: ""
        val language = metadata?.descendants()?.firstOrNull { it.localName == "language" }?.text()?.trim() ?: "en"
        val coverId = metadata?.descendants()?.firstOrNull { it.localName == "meta" && it.attr("name") == "cover" }?.attr("content")
        val fixedLayout = metadata?.descendants()?.any {
            it.localName == "meta" && it.attr("property") == "rendition:layout" && it.text().trim() == "pre-paginated"
        } == true || read("META-INF/com.apple.ibooks.display-options.xml")?.let { d ->
            Xml.parse(d).descendants().any { it.localName == "option" && it.attr("name") == "fixed-layout" && it.text().trim() == "true" }
        } == true

        // 4. manifest
        val manifest = LinkedHashMap<String, Item>()
        opf.first("manifest")?.elements("item")?.forEach { e ->
            val id = e.attr("id") ?: return@forEach
            val href = e.attr("href") ?: return@forEach
            manifest[id] = Item(id, resolve(baseDir, href).first, e.attr("media-type") ?: "", (e.attr("properties") ?: "").split(' '))
        }
        val coverPath = manifest.values.firstOrNull { "cover-image" in it.properties }?.path
            ?: coverId?.let { manifest[it]?.path }

        // 5. spine
        val spineEl = opf.first("spine")
        val rtl = spineEl?.attr("page-progression-direction") == "rtl"
        val spine = spineEl?.elements("itemref")?.mapNotNull { ir ->
            val item = manifest[ir.attr("idref") ?: return@mapNotNull null] ?: return@mapNotNull null
            item to (ir.attr("linear") != "no")
        } ?: emptyList()
        if (spine.isEmpty()) throw EpubException("This EPUB has no reading order (empty spine).")
        return Pkg(title, author, language, coverPath, fixedLayout, rtl, spine, manifest, spineEl?.attr("toc"))
    }

    /** The book's own CSS for one chapter: linked stylesheets, then <style> blocks, in document order. */
    private fun stylesheetRules(doc: XElement, dir: String, files: Map<String, ByteArray>, cache: HashMap<String, List<CssRule>>): List<CssRule> {
        val rules = mutableListOf<CssRule>()
        val head = doc.first("head") ?: return rules
        for (e in head.descendants()) {
            if (e.localName == "link" && (e.attr("rel") ?: "").contains("stylesheet", true)) {
                val path = resolve(dir, e.attr("href") ?: continue).first
                val parsed = cache.getOrPut(path) { files[path]?.let { Css.parse(Xml.decode(it)) } ?: emptyList() }
                parsed.forEach { r -> rules += CssRule(r.tag, r.cls, r.decl, rules.size, r.ancestors) }
            } else if (e.localName == "style") {
                Css.parse(e.text()).forEach { r -> rules += CssRule(r.tag, r.cls, r.decl, rules.size, r.ancestors) }
            }
        }
        return rules
    }

    private fun readNav(doc: XElement, dir: String, out: MutableList<TocEntry>) {
        val nav = doc.descendants().firstOrNull { it.localName == "nav" && "toc" in it.epubTypes() }
            ?: doc.descendants().firstOrNull { it.localName == "nav" } ?: return
        fun walk(ol: XElement, level: Int) {
            for (li in ol.elements("li")) {
                val a = li.elements().firstOrNull { it.localName == "a" || it.localName == "span" }
                val href = a?.attr("href")
                if (a != null && href != null) {
                    val (p, frag) = resolve(dir, href)
                    out += TocEntry(a.text().replace(Regex("\\s+"), " ").trim(), p, frag, level)
                }
                li.child("ol")?.let { walk(it, level + 1) }
            }
        }
        nav.first("ol")?.let { walk(it, 0) }
    }

    private fun readNcx(doc: XElement, dir: String, out: MutableList<TocEntry>) {
        fun walk(parent: XElement, level: Int) {
            for (np in parent.elements("navpoint")) {
                val label = np.child("navlabel")?.text()?.replace(Regex("\\s+"), " ")?.trim() ?: ""
                val src = np.child("content")?.attr("src")
                if (src != null) { val (p, f) = resolve(dir, src); out += TocEntry(label, p, f, level) }
                walk(np, level + 1)
            }
        }
        doc.first("navmap")?.let { walk(it, 0) }
    }

    /** Resolves an href against a folder inside the archive; returns (path, fragment). */
    fun resolve(dir: String, href: String): Pair<String, String?> {
        val frag = href.substringAfter('#', "").ifEmpty { null }
        val raw = percentDecode(href.substringBefore('#'))
        if (raw.isEmpty()) return dir to frag
        val parts = ArrayList<String>()
        if (!raw.startsWith("/")) dir.split('/').filter { it.isNotEmpty() }.forEach { parts += it }
        for (seg in raw.split('/')) when (seg) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts += seg
        }
        return parts.joinToString("/") to frag
    }

    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) { bytes += v.toByte(); i += 3; continue }
            }
            s[i].toString().encodeToByteArray().forEach { bytes += it }; i++
        }
        return bytes.toByteArray().decodeToString()
    }
}

/** Turns one XHTML chapter into blocks of styled text and pictures. */
private class ContentReader(val dir: String, val path: String, val footnotes: MutableMap<String, String>, val rules: List<CssRule>) {
    private var indent: Float? = null
    private var marginTop: Float? = null
    private var marginBottom: Float? = null

    private val ancestry = ArrayList<Pair<String, List<String>>>()
    private fun classesOf(e: XElement) = (e.attr("class") ?: "").split(' ').filter { it.isNotBlank() }

    private fun cssFor(e: XElement): CssDecl =
        if (rules.isEmpty() && e.attr("style") == null) CssDecl()
        else Css.resolve(rules, e.localName, classesOf(e), e.attr("style"), ancestry)

    private val blocks = mutableListOf<Block>()
    private var builder = AnnotatedString.Builder()
    private var kind = Block.Kind.Body
    private var level = 0
    private var align: TextAlign? = null
    private val pendingAnchors = mutableListOf<String>()
    private var lastWasSpace = true
    private var inPre = false

    private val blockTags = setOf(
        "p", "div", "section", "article", "header", "footer", "main", "body", "blockquote", "li", "ul", "ol",
        "h1", "h2", "h3", "h4", "h5", "h6", "pre", "figure", "figcaption", "table", "tr", "dl", "dt", "dd",
        "nav", "address", "center", "hgroup", "caption",
    )
    private val skipTags = setOf("head", "script", "style", "title", "audio", "video", "object", "iframe", "noscript", "rt", "rp")

    fun read(doc: XElement): List<Block> {
        val body = doc.first("body") ?: doc
        walk(body, Block.Kind.Body, 0, SpanStyle())
        flush()
        return blocks
    }

    private fun flush() {
        val raw = builder.toAnnotatedString()
        val text = raw.text
        var start = 0; var end = text.length
        while (start < end && text[start].isWhitespace() && text[start] != ' ') start++
        while (end > start && text[end - 1].isWhitespace() && text[end - 1] != ' ') end--
        if (end > start) {
            blocks += Block.Text(raw.subSequence(start, end), kind, level, align, pendingAnchors.toList(), indent, marginTop, marginBottom)
            pendingAnchors.clear()
        }
        builder = AnnotatedString.Builder()
        lastWasSpace = true
    }

    private fun startBlock(k: Block.Kind, lvl: Int, a: TextAlign?, css: CssDecl = CssDecl()) {
        flush(); kind = k; level = lvl; align = a
        indent = css.textIndent; marginTop = css.marginTop; marginBottom = css.marginBottom
    }

    private fun appendText(s: String, style: SpanStyle) {
        val out = StringBuilder()
        if (inPre) out.append(s) else for (ch in s) {
            if (ch.isWhitespace() && ch != ' ') {
                if (!lastWasSpace) { out.append(' '); lastWasSpace = true }
            } else { out.append(ch); lastWasSpace = false }
        }
        if (out.isEmpty()) return
        val startIdx = builder.length
        builder.append(out.toString())
        if (style != SpanStyle()) builder.addStyle(style, startIdx, builder.length)
    }

    private fun alignOf(e: XElement): TextAlign? {
        val st = (e.attr("style") ?: "").lowercase()
        val a = (e.attr("align") ?: Regex("text-align\\s*:\\s*(\\w+)").find(st)?.groupValues?.get(1) ?: "").lowercase()
        val cls = (e.attr("class") ?: "").lowercase()
        return when {
            a == "center" || e.localName == "center" || cls.split(' ').any { it == "center" || it == "centered" } -> TextAlign.Center
            a == "right" -> TextAlign.End
            else -> null
        }
    }

    private fun inlineStyle(e: XElement, base: SpanStyle): SpanStyle {
        var s = base
        val st = (e.attr("style") ?: "").lowercase()
        when (e.localName) {
            "b", "strong", "th" -> s = s.merge(SpanStyle(fontWeight = FontWeight.Bold))
            "i", "em", "cite", "var", "dfn" -> s = s.merge(SpanStyle(fontStyle = FontStyle.Italic))
            "u", "ins" -> s = s.merge(SpanStyle(textDecoration = TextDecoration.Underline))
            "s", "strike", "del" -> s = s.merge(SpanStyle(textDecoration = TextDecoration.LineThrough))
            "code", "tt", "kbd", "samp" -> s = s.merge(SpanStyle(fontFamily = FontFamily.Monospace))
            "sup" -> s = s.merge(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.7.em))
            "sub" -> s = s.merge(SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.7.em))
            "small" -> s = s.merge(SpanStyle(fontSize = 0.85.em))
        }
        val css = cssFor(e)
        if (css.italic == true) s = s.merge(SpanStyle(fontStyle = FontStyle.Italic))
        if (css.bold == true) s = s.merge(SpanStyle(fontWeight = FontWeight.Bold))
        if ("italic" in st) s = s.merge(SpanStyle(fontStyle = FontStyle.Italic))
        if (Regex("font-weight\\s*:\\s*(bold|[6-9]00)").containsMatchIn(st)) s = s.merge(SpanStyle(fontWeight = FontWeight.Bold))
        if ("small-caps" in st) s = s.merge(SpanStyle(fontFeatureSettings = "smcp"))
        return s
    }

    private fun walk(e: XElement, k: Block.Kind, lvl: Int, style: SpanStyle) {
        walkInner(e, k, lvl, style)
    }

    private fun child(c: XNode, k: Block.Kind, lvl: Int, style: SpanStyle, parent: XElement) {
        ancestry += parent.localName to classesOf(parent)
        try { child(c, k, lvl, style) } finally { ancestry.removeAt(ancestry.size - 1) }
    }

    private fun walkInner(e: XElement, k: Block.Kind, lvl: Int, style: SpanStyle) {
        val name = e.localName
        if (name in skipTags) return
        // Footnotes are hidden from the flow and kept for the popover.
        val types = e.epubTypes()
        if (name == "aside" && types.any { it == "footnote" || it == "endnote" || it == "rearnote" }) {
            e.attr("id")?.let { footnotes["$path#$it"] = e.text().replace(Regex("\\s+"), " ").trim() }
            return
        }
        e.attr("id")?.let { pendingAnchors += it }

        if (name == "img" || name == "image") {
            val src = e.attr("src") ?: e.attr("href")
            if (src != null) {
                flush()
                blocks += Block.Picture(EpubParser.resolve(dir, src).first, e.attr("alt") ?: "", pendingAnchors.toList())
                pendingAnchors.clear()
            }
            return
        }
        if (name == "hr") { flush(); blocks += Block.Rule; return }
        if (name == "br") { builder.append('\n'); lastWasSpace = true; return }
        if (name == "svg") { e.descendants().filter { it.localName == "image" }.forEach { walk(it, k, lvl, style) }; return }

        if (name in blockTags) {
            val (nk, nl) = when (name) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> Block.Kind.Heading to name[1].digitToInt()
                "blockquote" -> Block.Kind.Quote to lvl
                "pre" -> Block.Kind.Pre to lvl
                "li", "dd" -> Block.Kind.ListItem to lvl
                "figcaption", "caption" -> Block.Kind.Caption to lvl
                "ul", "ol", "dl" -> k to (lvl + 1)
                else -> k to lvl
            }
            val css = cssFor(e)
            if (css.hidden == true) return
            startBlock(nk, nl, alignOf(e) ?: css.align ?: if (nk == Block.Kind.Heading || nk == Block.Kind.Caption) TextAlign.Center else null, css)
            if (name == "li") { builder.append("\u2022\u2002"); lastWasSpace = true }
            val wasPre = inPre
            if (name == "pre") inPre = true
            val childStyle = inlineStyle(e, style)
            if (name == "ol") {
                var n = (e.attr("start")?.toIntOrNull() ?: 1)
                for (c in e.children) {
                    if (c is XElement && c.localName == "li") {
                        startBlock(Block.Kind.ListItem, nl, null)
                        builder.append("$n. "); lastWasSpace = true; n++
                        c.attr("id")?.let { pendingAnchors += it }
                        for (cc in c.children) child(cc, Block.Kind.ListItem, nl, childStyle, c)
                        flush()
                    } else child(c, nk, nl, childStyle, e)
                }
            } else {
                for (c in e.children) child(c, nk, nl, childStyle, e)
            }
            inPre = wasPre
            startBlock(k, lvl, null)
            return
        }

        // inline element
        val s = inlineStyle(e, style)
        val linkStart = builder.length
        for (c in e.children) child(c, k, lvl, s, e)
        if (name == "a") {
            val href = e.attr("href")
            if (href != null && builder.length > linkStart) {
                val tag = if ("noteref" in types) "noteref" else "link"
                val target = EpubParser.resolve(dir, href).let { (p, f) -> if (f != null) "$p#$f" else p }
                builder.addStringAnnotation(tag, target, linkStart, builder.length)
            }
        }
    }

    private fun child(c: XNode, k: Block.Kind, lvl: Int, style: SpanStyle) {
        when (c) {
            is XText -> appendText(c.text, style)
            is XElement -> walk(c, k, lvl, style)
        }
    }
}
