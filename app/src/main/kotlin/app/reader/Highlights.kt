package app.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------- model

/** A highlight style: five colours, or an underline. */
enum class HlColor(
    val label: String,
    /** The swatch in the toolbar on a light screen, and the bar beside a quoted highlight. */
    val swatch: Color,
    /** Behind the text on a light page (and the swatch on a dark screen). */
    val light: Color,
    /** Behind the text on a dark page. */
    val dark: Color,
) {
    Yellow("Yellow", Color(0xFFE2C14E), Color(0xFFF3DE8C), Color(0xFF5E5226)),
    Green("Green", Color(0xFFA9C77A), Color(0xFFD3E3AE), Color(0xFF41502F)),
    Blue("Blue", Color(0xFF8DB3D6), Color(0xFFCCDDEB), Color(0xFF2F4559)),
    Rose("Rose", Color(0xFFE09A90), Color(0xFFF0CBC5), Color(0xFF673B38)),
    Lilac("Lilac", Color(0xFFB9A5D2), Color(0xFFDED2E8), Color(0xFF4B3F5E)),
    Underline("Underline", Color(0xFF6A4A2B), Color(0xFF6A4A2B), Color(0xFFE7C399));

    val isUnderline get() = this == Underline
    fun fill(dark: Boolean) = if (dark) this.dark else light
    /** The swatch drawn in a toolbar on a light or dark screen. */
    fun chip(darkUi: Boolean) = if (darkUi) light else swatch
}

/** A place in a chapter: paragraph and character. */
data class TextPos(val block: Int, val char: Int) : Comparable<TextPos> {
    override fun compareTo(other: TextPos) = if (block != other.block) block.compareTo(other.block) else char.compareTo(other.char)
}

/** A span of text in one chapter, from [start] (inclusive) to [end] (exclusive). */
data class TextSpan(val chapter: Int, val start: TextPos, val end: TextPos) {
    val isEmpty get() = start >= end
    fun contains(chapter: Int, p: TextPos) = chapter == this.chapter && p >= start && p < end
    fun overlaps(o: TextSpan) = chapter == o.chapter && start < o.end && o.start < end
    /** The span with its ends in reading order. */
    fun normalized() = if (end < start) TextSpan(chapter, end, start) else this
}

data class Highlight(
    val id: Long,
    val span: TextSpan,
    val color: HlColor,
    val note: String = "",
    val created: Long = id,
    /** When the note was last changed; 0 if it never was. */
    val edited: Long = 0L,
) {
    val hasNote get() = note.isNotBlank()

    companion object {
        val BookOrder: Comparator<Highlight> = compareBy({ it.span.chapter }, { it.span.start })

        fun encodeAll(list: List<Highlight>): String = JSONArray().apply {
            list.forEach { h ->
                put(JSONObject().apply {
                    put("id", h.id); put("c", h.span.chapter)
                    put("sb", h.span.start.block); put("sc", h.span.start.char)
                    put("eb", h.span.end.block); put("ec", h.span.end.char)
                    put("col", h.color.name); put("note", h.note); put("cr", h.created); put("ed", h.edited)
                })
            }
        }.toString()

        fun decodeAll(s: String?): List<Highlight> {
            if (s.isNullOrBlank()) return emptyList()
            return runCatching {
                val a = JSONArray(s)
                (0 until a.length()).mapNotNull { i ->
                    val o = a.optJSONObject(i) ?: return@mapNotNull null
                    Highlight(
                        id = o.optLong("id"),
                        span = TextSpan(o.optInt("c"), TextPos(o.optInt("sb"), o.optInt("sc")), TextPos(o.optInt("eb"), o.optInt("ec"))),
                        color = HlColor.entries.firstOrNull { it.name == o.optString("col") } ?: HlColor.Yellow,
                        note = o.optString("note"),
                        created = o.optLong("cr", o.optLong("id")),
                        edited = o.optLong("ed"),
                    )
                }
            }.getOrDefault(emptyList())
        }
    }
}

/** The words of [span], paragraphs joined by line breaks. */
fun EpubBook.textOf(span: TextSpan): String {
    val blocks = chapters.getOrNull(span.chapter)?.blocks ?: return ""
    val out = StringBuilder()
    for (b in span.start.block..span.end.block) {
        val t = (blocks.getOrNull(b) as? Block.Text)?.text?.text ?: continue
        val from = if (b == span.start.block) span.start.char.coerceIn(0, t.length) else 0
        val to = if (b == span.end.block) span.end.char.coerceIn(from, t.length) else t.length
        if (out.isNotEmpty()) out.append('\n')
        out.append(t, from, to)
    }
    return out.toString().trim()
}

// ---------------------------------------------------------------- page geometry

/** Where text sits on a laid-out page: hit testing and the boxes behind a span of text. */
class PageText(private val page: Page, private val spec: PageSpec, private val top: Float) {
    private class Hit(val f: Frag.Lines, val x0: Float)

    private fun lines(): Sequence<Hit> = sequence {
        page.columns.forEachIndexed { c, frags -> frags.forEach { if (it is Frag.Lines) yield(Hit(it, spec.columnX(c))) } }
    }

    /** The character under [pos]; with [nearest], the closest one on the page when [pos] is between lines. */
    fun charAt(pos: Offset, nearest: Boolean = false): TextPos? {
        var best: Hit? = null; var bestD = Float.MAX_VALUE
        for (h in lines()) {
            val f = h.f
            val left = h.x0 - spec.marginH * 0.5f; val right = h.x0 + spec.columnWidth + spec.marginH * 0.5f
            val t = top + f.y; val b = t + f.height
            val dy = when { pos.y < t -> t - pos.y; pos.y > b -> pos.y - b; else -> 0f }
            val dx = when { pos.x < left -> left - pos.x; pos.x > right -> pos.x - right; else -> 0f }
            val d = dy * 4f + dx // rows matter more than columns
            if (d < bestD) { bestD = d; best = h }
        }
        val h = best ?: return null
        if (!nearest && bestD > 0f) return null
        val f = h.f
        val localY = (pos.y - top - f.y).coerceIn(0f, f.height - 1f) + f.layout.getLineTop(f.first)
        val off = f.layout.getOffsetForPosition(Offset(pos.x - h.x0 - f.x, localY))
        return TextPos(f.block, off)
    }

    /** The word at [pos], or null on blank space. */
    fun wordAt(pos: Offset, chapter: Int): TextSpan? {
        val p = charAt(pos) ?: return null
        for (h in lines()) {
            if (h.f.block != p.block) continue
            val text = h.f.layout.layoutInput.text.text
            if (p.char >= text.length || !text[p.char].isLetterOrDigit()) {
                if (p.char > 0 && p.char <= text.length && text[p.char - 1].isLetterOrDigit()) return word(h, p.char - 1, chapter)
                return null
            }
            return word(h, p.char, chapter)
        }
        return null
    }

    private fun word(h: Hit, at: Int, chapter: Int): TextSpan {
        val r: TextRange = h.f.layout.getWordBoundary(at)
        return TextSpan(chapter, TextPos(h.f.block, r.start), TextPos(h.f.block, maxOf(r.end, at + 1)))
    }

    /** [p] moved out of the middle of a word: back to its start, or on to its end with [toEnd]. */
    fun snapToWord(p: TextPos, toEnd: Boolean): TextPos {
        val h = lines().firstOrNull { it.f.block == p.block } ?: return p
        val text = h.f.layout.layoutInput.text.text
        val c = p.char
        if (c <= 0 || c >= text.length || !text[c - 1].isLetterOrDigit() || !text[c].isLetterOrDigit()) return p
        val r = h.f.layout.getWordBoundary(c)
        return TextPos(p.block, if (toEnd) r.end else r.start)
    }

    /** All the text on this page, as a span. */
    fun all(chapter: Int): TextSpan? {
        val ls = lines().toList()
        val first = ls.firstOrNull() ?: return null; val last = ls.last()
        return TextSpan(chapter, TextPos(first.f.block, first.f.layout.getLineStart(first.f.first)),
            TextPos(last.f.block, last.f.layout.getLineEnd(last.f.last - 1)))
    }

    /** One box per line of [span] that is on this page, in page coordinates. */
    fun boxes(span: TextSpan, density: Float): List<Rect> {
        val out = ArrayList<Rect>()
        for (h in lines()) {
            val f = h.f
            if (f.block < span.start.block || f.block > span.end.block) continue
            val l = f.layout
            val from = if (f.block == span.start.block) span.start.char else 0
            val to = if (f.block == span.end.block) span.end.char else Int.MAX_VALUE
            val fontPx = l.layoutInput.style.fontSize.value * density
            for (line in f.first until f.last) {
                val ls = l.getLineStart(line); val le = l.getLineEnd(line, visibleEnd = true)
                val s = maxOf(from, ls); val e = minOf(to, le)
                if (s >= e) continue
                val x1 = if (s <= ls) l.getLineLeft(line) else l.getHorizontalPosition(s, true)
                val x2 = if (e >= le) l.getLineRight(line) else l.getHorizontalPosition(e, true)
                val lt = l.getLineTop(line); val lb = l.getLineBottom(line)
                val mid = top + f.y + ((lt + lb) / 2f - l.getLineTop(f.first))
                val half = minOf((lb - lt) / 2f - 1f * density, 0.66f * fontPx + 2f * density)
                val ox = h.x0 + f.x
                out += Rect(ox + minOf(x1, x2), mid - half, ox + maxOf(x1, x2), mid + half)
            }
        }
        return out
    }
}

/** Draws the highlights of [chapter] that fall on [page], behind its text. */
fun DrawScope.drawHighlights(page: Page, spec: PageSpec, top: Float, marks: List<Highlight>, pageDark: Boolean) {
    val mine = marks.filter { it.span.chapter == page.chapter }
    if (mine.isEmpty()) return
    val text = PageText(page, spec, top)
    val dp = density
    for (h in mine) {
        val boxes = text.boxes(h.span, dp)
        if (boxes.isEmpty()) continue
        if (h.color.isUnderline) {
            val c = h.color.fill(pageDark)
            boxes.forEach { r -> drawRect(c, Offset(r.left, r.bottom - 2.5f * dp), Size(r.width, 1.6f * dp)) }
        } else {
            val c = h.color.fill(pageDark)
            boxes.forEach { r -> drawRoundRect(c, r.topLeft, r.size, CornerRadius(3f * dp)) }
        }
        if (h.hasNote) {
            // a small note mark, raised like a footnote sign, where the highlight ends
            val end = boxes.last()
            val ink = if (pageDark) Color(0xFFE7C399) else Color(0xFF6A4A2B)
            drawNoteMark(ink, Offset(end.right - 1f * dp, end.top - 9f * dp), 14f * dp)
        }
    }
}

/** The note icon (a page with lines), [s] px square, top-left at [at]. */
fun DrawScope.drawNoteMark(color: Color, at: Offset, s: Float) {
    val k = s / 24f
    val stroke = Stroke(width = 2.2f * k)
    drawRoundRect(color, Offset(at.x + 5f * k, at.y + 4f * k), Size(14f * k, 16f * k), CornerRadius(2f * k), style = stroke)
    drawLine(color, Offset(at.x + 9f * k, at.y + 9f * k), Offset(at.x + 15f * k, at.y + 9f * k), 2.2f * k)
    drawLine(color, Offset(at.x + 9f * k, at.y + 13f * k), Offset(at.x + 15f * k, at.y + 13f * k), 2.2f * k)
    drawLine(color, Offset(at.x + 9f * k, at.y + 17f * k), Offset(at.x + 12f * k, at.y + 17f * k), 2.2f * k)
}

/** The live selection: a tint behind the text, and the two handles. */
fun DrawScope.drawSelection(boxes: List<Rect>, color: Color) {
    if (boxes.isEmpty()) return
    val dp = density
    boxes.forEach { r -> drawRect(color.copy(alpha = 0.24f), r.topLeft, r.size) }
    val first = boxes.first(); val last = boxes.last()
    // caret lines and teardrop handles below each end
    drawRect(color, Offset(first.left - 1f * dp, first.top), Size(2f * dp, first.height))
    drawRect(color, Offset(last.right - 1f * dp, last.top), Size(2f * dp, last.height))
    val r = 10f * dp
    drawCircle(color, r, Offset(first.left - r, first.bottom + r))
    drawRect(color, Offset(first.left - r, first.bottom), Size(r, r))
    drawCircle(color, r, Offset(last.right + r, last.bottom + r))
    drawRect(color, Offset(last.right, last.bottom), Size(r, r))
}

// ---------------------------------------------------------------- toolbars

private val toolbarShape = RoundedCornerShape(26.dp)

@Composable
private fun Toolbar(modifier: Modifier, label: String, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val jr = LocalJr.current
    Row(modifier.shadow(10.dp, toolbarShape, ambientColor = jr.shadow, spotColor = jr.shadow)
        .clip(toolbarShape).background(if (jr.dark) Color(0xFF3A352E) else jr.menu)
        .semantics { contentDescription = label }
        .height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

/** Highlight · Note · Copy · Share · more, over a fresh selection. */
@Composable
fun SelectionToolbar(
    modifier: Modifier,
    onHighlight: () -> Unit, onNote: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit,
    onTranslate: () -> Unit, onSearch: () -> Unit, onSelectAll: () -> Unit,
) {
    val jr = LocalJr.current
    Toolbar(modifier, "Selected text") {
        listOf("Highlight" to onHighlight, "Note" to onNote, "Copy" to onCopy, "Share" to onShare).forEach { (label, action) ->
            Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = action), contentAlignment = Alignment.Center) {
                Text(label, style = JrType.label, color = jr.onBackground)
            }
        }
        var more by remember { mutableStateOf(false) }
        Box {
            IconBtn(JrIcons.More, "More: Translate, Search the book, Select all", jr.onVariant) { more = true }
            DropdownMenu(more, onDismissRequest = { more = false }, containerColor = jr.menu, offset = androidx.compose.ui.unit.DpOffset(0.dp, 12.dp)) {
                MenuRow("Translate", null) { more = false; onTranslate() }
                MenuRow("Search the book", JrIcons.Search) { more = false; onSearch() }
                MenuRow("Select all", null) { more = false; onSelectAll() }
            }
        }
    }
}

/** Colours, underline, note and remove, for a highlight. */
@Composable
fun HighlightToolbar(
    modifier: Modifier, h: Highlight,
    onColor: (HlColor) -> Unit, onNote: () -> Unit, onRemoveHighlight: () -> Unit, onRemoveNote: () -> Unit,
) {
    val jr = LocalJr.current
    Toolbar(modifier, "Highlight") {
        Row(Modifier.weight(1f).padding(start = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            HlColor.entries.forEach { c ->
                val on = c == h.color
                Box(Modifier.size(40.dp, 48.dp).clip(RoundedCornerShape(20.dp)).clickable { onColor(c) }
                    .semantics { contentDescription = if (c.isUnderline) "Underline instead" else "${c.label} highlight" },
                    contentAlignment = Alignment.Center) {
                    val ring = if (on) Modifier.border(2.dp, jr.primary, CircleShape).padding(4.dp) else Modifier.padding(4.dp)
                    if (c.isUnderline) {
                        Box(Modifier.size(34.dp).then(ring), contentAlignment = Alignment.Center) {
                            Text("U", style = JrType.serif(17, 20).copy(textDecoration = TextDecoration.Underline), color = jr.onBackground)
                        }
                    } else {
                        Box(Modifier.size(34.dp).then(ring).clip(CircleShape).background(c.chip(jr.dark)))
                    }
                }
            }
        }
        Box(Modifier.padding(horizontal = 4.dp).size(1.dp, 24.dp).background(jr.outlineVariant))
        IconBtn(JrIcons.Note, if (h.hasNote) "Edit note" else "Add a note", jr.onVariant, size = 44.dp, onClick = onNote)
        var menu by remember { mutableStateOf(false) }
        Box {
            IconBtn(JrIcons.Trash, if (h.hasNote) "Remove…" else "Remove highlight", jr.error, size = 44.dp) {
                if (h.hasNote) menu = true else onRemoveHighlight()
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = jr.menu, offset = androidx.compose.ui.unit.DpOffset(0.dp, 12.dp)) {
                MenuRow("Remove note", JrIcons.NoteRemove) { menu = false; onRemoveNote() }
                Divider(Modifier.padding(vertical = 8.dp))
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Remove highlight and note", style = JrType.bodyLarge, color = jr.error) },
                    leadingIcon = { Icon(JrIcons.Trash, null, tint = jr.error, modifier = Modifier.size(24.dp)) },
                    onClick = { menu = false; onRemoveHighlight() },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- note sheet

/** Adding or editing the note on a highlight. Save with an empty note removes it. */
@Composable
fun NoteSheet(h: Highlight, quote: String, place: String, onSave: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val jr = LocalJr.current
    val editing = h.hasNote
    var text by remember(h.id) { mutableStateOf(h.note) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    JrSheet(onDismiss) {
        SheetTitle(if (editing) "Edit note" else "Add a note", onClose = onDismiss)
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val edited = if (editing && h.edited > 0) " · Edited ${relativeDay(h.edited)}" else ""
            Text(place + edited, style = JrType.labelSmall, color = jr.onVariant)
            Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(h.color.chip(jr.dark)))
                Text(quote, style = JrType.serif(17, 27, FontWeight.Normal), color = jr.onBackground, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.padding(start = 41.dp, end = 24.dp, top = 4.dp).fillMaxWidth().heightIn(min = 108.dp)) {
            if (text.isEmpty()) Text("Write a thought…", style = JrType.serif(17, 27, FontWeight.Normal), color = jr.onVariant)
            BasicTextField(text, { text = it }, textStyle = JrType.serif(17, 27, FontWeight.Normal).copy(color = jr.onBackground),
                cursorBrush = SolidColor(jr.primary), modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    .semantics { contentDescription = "Your note" })
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 24.dp, top = 16.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (editing) TextAction("Delete note", jr.error, onClick = onDelete)
            Spacer(Modifier.weight(1f))
            TextAction("Cancel", jr.primary, onClick = onDismiss)
            val canSave = text.isNotBlank() || editing
            Box(Modifier.height(40.dp).clip(RoundedCornerShape(20.dp))
                .background(if (canSave) jr.primary else jr.onBackground.copy(alpha = 0.12f))
                .clickable(enabled = canSave) { onSave(text.trim()) }.padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                Text("Save", style = JrType.label, color = if (canSave) jr.onPrimary else jr.onBackground.copy(alpha = 0.38f))
            }
        }
    }
}

@Composable
private fun TextAction(label: String, color: Color, onClick: () -> Unit) {
    Box(Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center) { Text(label, style = JrType.label, color = color) }
}

/** "today", "yesterday", or a short date. */
fun relativeDay(millis: Long): String {
    val day = Math.floorDiv(millis + java.util.TimeZone.getDefault().getOffset(millis), 86_400_000L)
    return when (localEpochDay() - day) {
        0L -> "today"
        1L -> "yesterday"
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(millis))
    }
}

// ---------------------------------------------------------------- the list in Contents

private enum class HlSort(val label: String) { Book("Book order"), Newest("Newest first") }

/** The Highlights tab of the contents sheet. */
@Composable
fun androidx.compose.foundation.layout.ColumnScope.HighlightsList(
    marks: List<Highlight>, book: EpubBook, pg: Pagination, place: (Highlight) -> String,
    onPick: (Highlight) -> Unit, onNote: (Highlight) -> Unit, onCopy: (Highlight) -> Unit, onRemove: (Highlight) -> Unit,
) {
    val jr = LocalJr.current
    var filter by remember { mutableStateOf<HlColor?>(null) }
    var sort by remember { mutableStateOf(HlSort.Book) }
    if (marks.isEmpty()) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(JrIcons.Highlight, null, tint = jr.onVariant, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(12.dp))
            Text("No highlights yet", style = JrType.title, color = jr.onBackground)
            Text("Press and hold on any words to highlight them or add a note.", style = JrType.body, color = jr.onVariant, textAlign = TextAlign.Center)
        }
        return
    }
    Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        var colours by remember { mutableStateOf(false) }
        Box {
            Chip(filter?.let { if (it.isUnderline) "Underlined" else it.label } ?: "All colours", null) { colours = true }
            DropdownMenu(colours, onDismissRequest = { colours = false }, containerColor = jr.menu) {
                MenuRow("All colours", null) { filter = null; colours = false }
                HlColor.entries.filter { c -> marks.any { it.color == c } }.forEach { c ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(if (c.isUnderline) "Underlined" else c.label, style = JrType.bodyLarge, color = jr.onBackground) },
                        leadingIcon = { Box(Modifier.size(18.dp).clip(CircleShape).background(c.chip(jr.dark))) },
                        onClick = { filter = c; colours = false },
                    )
                }
            }
        }
        var sorting by remember { mutableStateOf(false) }
        Box {
            Chip(sort.label, JrIcons.Sort) { sorting = true }
            DropdownMenu(sorting, onDismissRequest = { sorting = false }, containerColor = jr.menu) {
                HlSort.entries.forEach { s -> MenuRow(s.label, if (s == sort) JrIcons.Check else null) { sort = s; sorting = false } }
            }
        }
    }
    val shown = marks.filter { filter == null || it.color == filter }
        .let { if (sort == HlSort.Newest) it.sortedByDescending { h -> h.created } else it.sortedWith(Highlight.BookOrder) }
    androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        shown.forEachIndexed { i, h ->
            item(key = h.id) {
                if (i > 0) Divider(Modifier.padding(horizontal = 24.dp))
                Row(Modifier.fillMaxWidth().clickable { onPick(h) }.padding(start = 24.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(place(h), style = JrType.labelSmall, color = jr.onVariant)
                        val quote = book.textOf(h.span)
                        Text(buildAnnotatedString {
                            withStyle(if (h.color.isUnderline) SpanStyle(textDecoration = TextDecoration.Underline)
                                else SpanStyle(background = h.color.fill(jr.dark))) { append(quote) }
                        }, style = JrType.serif(16, 26, FontWeight.Normal), color = jr.onBackground, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        if (h.hasNote) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(JrIcons.Note, null, tint = jr.onVariant, modifier = Modifier.size(18.dp))
                            Text(h.note, style = JrType.body, color = jr.onVariant)
                        }
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconBtn(JrIcons.More, "Options for this highlight", jr.onVariant, size = 44.dp) { menu = true }
                        DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = jr.menu) {
                            MenuRow(if (h.hasNote) "Edit note" else "Add a note", JrIcons.Note) { menu = false; onNote(h) }
                            MenuRow("Copy", null) { menu = false; onCopy(h) }
                            MenuRow(if (h.hasNote) "Remove highlight and note" else "Remove highlight", JrIcons.Trash) { menu = false; onRemove(h) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit) {
    val jr = LocalJr.current
    Row(Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, jr.outlineVariant, RoundedCornerShape(8.dp))
        .clickable(onClick = onClick).padding(start = if (icon != null) 8.dp else 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) Icon(icon, null, tint = jr.primary, modifier = Modifier.size(18.dp))
        Text(label, style = JrType.label, color = jr.onBackground)
        Icon(JrIcons.ChevronDown, null, tint = jr.onBackground, modifier = Modifier.size(18.dp))
    }
}
