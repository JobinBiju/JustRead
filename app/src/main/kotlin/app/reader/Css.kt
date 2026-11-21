package app.reader

import androidx.compose.ui.text.style.TextAlign

/** The few stylesheet properties the reader honours from a book's own CSS (lengths in em). */
data class CssDecl(
    val textIndent: Float? = null,
    val marginTop: Float? = null,
    val marginBottom: Float? = null,
    val align: TextAlign? = null,
    val italic: Boolean? = null,
    val bold: Boolean? = null,
    val hidden: Boolean? = null,
) {
    fun merge(o: CssDecl) = CssDecl(
        o.textIndent ?: textIndent, o.marginTop ?: marginTop, o.marginBottom ?: marginBottom,
        o.align ?: align, o.italic ?: italic, o.bold ?: bold, o.hidden ?: hidden,
    )
    val isEmpty get() = this == CssDecl()
}

/** A simple selector part: `tag`, `.class` or `tag.class`. */
data class CssPart(val tag: String?, val cls: String?) {
    fun matches(t: String, classes: List<String>) = (tag == null || tag == t) && (cls == null || cls in classes)
}

/**
 * One rule: the element's own part plus the ancestors it must sit inside (descendant and child
 * combinators are both treated as "inside"; sibling selectors are skipped).
 */
class CssRule(val tag: String?, val cls: String?, val decl: CssDecl, val order: Int, val ancestors: List<CssPart> = emptyList()) {
    val specificity get() = (if (tag != null) 1 else 0) + (if (cls != null) 10 else 0) +
        ancestors.sumOf { (if (it.tag != null) 1 else 0) + (if (it.cls != null) 10 else 0) }
}

/** A small CSS reader: enough for indents, paragraph spacing, alignment and emphasis in EPUBs. */
object Css {
    fun parse(text: String, startOrder: Int = 0): List<CssRule> {
        val src = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(text, "")
            .replace(Regex("@media[^{]*\\{([^{}]*\\{[^}]*\\})*\\s*\\}"), "") // skip media blocks
        val out = mutableListOf<CssRule>()
        var order = startOrder
        for (chunk in src.split('}')) {
            val brace = chunk.indexOf('{')
            if (brace < 0) continue
            val selectors = chunk.substring(0, brace).trim()
            if (selectors.startsWith("@")) continue
            val decl = declarations(chunk.substring(brace + 1))
            if (decl.isEmpty) continue
            for (sel in selectors.split(',')) {
                if ('+' in sel || '~' in sel) continue
                val parts = sel.trim().split(Regex("[\\s>]+")).map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.isEmpty() || parts.any { ':' in it || '[' in it || '#' in it }) continue
                val compounds = parts.map { part(it) }
                val last = compounds.last()
                out += CssRule(last.tag, last.cls, decl, order++, compounds.dropLast(1))
            }
        }
        return out
    }

    private fun part(p: String): CssPart {
        val tag = p.substringBefore('.').lowercase().ifEmpty { null }?.takeIf { it != "*" }
        val cls = p.substringAfter('.', "").substringBefore('.').ifEmpty { null }
        return CssPart(tag, cls)
    }

    fun declarations(body: String): CssDecl {
        var d = CssDecl()
        for (part in body.split(';')) {
            val k = part.substringBefore(':').trim().lowercase()
            val v = part.substringAfter(':', "").trim().lowercase().removeSuffix("!important").trim()
            if (v.isEmpty()) continue
            d = when (k) {
                "text-indent" -> d.copy(textIndent = length(v))
                "margin-top" -> d.copy(marginTop = length(v))
                "margin-bottom" -> d.copy(marginBottom = length(v))
                "margin" -> {
                    val p = v.split(Regex("\\s+")).map { length(it) }
                    when (p.size) {
                        1 -> d.copy(marginTop = p[0], marginBottom = p[0])
                        2, 3 -> d.copy(marginTop = p[0], marginBottom = p.getOrNull(2) ?: p[0])
                        else -> d.copy(marginTop = p[0], marginBottom = p[2])
                    }
                }
                "text-align" -> d.copy(align = when (v) {
                    "center" -> TextAlign.Center; "right", "end" -> TextAlign.End
                    "justify" -> TextAlign.Justify; "left", "start" -> TextAlign.Start; else -> null
                })
                "font-style" -> d.copy(italic = v == "italic" || v == "oblique")
                "font-weight" -> d.copy(bold = v == "bold" || v == "bolder" || (v.toIntOrNull() ?: 400) >= 600)
                "display" -> d.copy(hidden = v == "none")
                else -> d
            }
        }
        return d
    }

    /** CSS length → em (16 px per em; percentages and auto are ignored). */
    fun length(v: String): Float? {
        val num = Regex("^-?[0-9]*\\.?[0-9]+").find(v)?.value?.toFloatOrNull() ?: return null
        val unit = v.substring(Regex("^-?[0-9]*\\.?[0-9]+").find(v)!!.value.length).trim()
        return when (unit) {
            "em", "rem", "" -> if (unit.isEmpty() && num != 0f) null else num
            "px" -> num / 16f
            "pt" -> num / 12f
            "ex" -> num / 2f
            else -> null
        }
    }

    /** [stack] holds the ancestors, outermost first, as (tag, classes). */
    fun resolve(rules: List<CssRule>, tag: String, classes: List<String>, inline: String?, stack: List<Pair<String, List<String>>> = emptyList()): CssDecl {
        var d = CssDecl()
        fun inside(anc: List<CssPart>): Boolean {
            var i = 0
            for ((t, c) in stack) if (i < anc.size && anc[i].matches(t, c)) i++
            return i == anc.size
        }
        rules.filter { (it.tag == null || it.tag == tag) && (it.cls == null || it.cls in classes) && (it.tag != null || it.cls != null) && inside(it.ancestors) }
            .sortedWith(compareBy({ it.specificity }, { it.order }))
            .forEach { d = d.merge(it.decl) }
        if (!inline.isNullOrBlank()) d = d.merge(declarations(inline))
        return d
    }
}
