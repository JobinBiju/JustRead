package app.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private enum class Panel { Themes, Customise, Fonts, Contents, Search, Note }

/** A page turn in progress. */
private class Turn {
    var curl by mutableStateOf<Curl?>(null)
    var top: ImageBitmap? = null
    var under: ImageBitmap? = null
    var forward = true
    var start = Offset.Zero
    var grab = Offset.Zero
    var finger = Offset.Zero
    val velocity = VelocityTracker()
    var busy = false
    var slide by mutableFloatStateOf(0f)
}

/** A row of the table of contents with the page it starts on. */
class TocRow(val title: String, val level: Int, val page: Int)

@Composable
fun ReaderScreen(controller: AppController, shelf: ShelfBook, uiDark: Boolean, onClose: () -> Unit) {
    val settings = controller.settings
    val colors = settings.theme.colors(uiDark)
    val pageDark = settings.isDark(uiDark)
    val book = shelf.book
    val density = LocalDensity.current
    val fontResolver = LocalFontFamilyResolver.current
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()

    var locator by remember(shelf) { mutableStateOf(controller.position(shelf) ?: Locator(0, 0, 0)) }
    var chrome by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var guideY by remember { mutableStateOf(-1f) }
    val pictureCache = remember(book) { HashMap<String, ImageBitmap?>() }
    val focus = remember { FocusRequester() }
    val turn = remember { Turn() }

    // reading time for the daily goal (frames stop while the app is in the background)
    LaunchedEffect(shelf) {
        while (true) {
            delay(15_000)
            withFrameNanos { }
            controller.addReadingSeconds(15)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.page)) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val safe = WindowInsets.safeDrawing
        val insetTop = safe.getTop(density).toFloat()
        val insetBottom = safe.getBottom(density).toFloat()
        val spec = remember(w, h, density.density, settings, insetTop, insetBottom) {
            PageSpec(w, h, density.density, settings, book.rightToLeft, insetTop, insetBottom)
        }
        val layoutKey = listOf(w, h, density.density, insetTop, insetBottom, settings.layoutKey())

        fun picture(p: String): ImageBitmap? = pictureCache.getOrPut(p) { book.files[p]?.let { decodePicture(it, maxSide = 2048) } }
        fun paginate(): Pagination {
            val m = TextMeasurer(fontResolver, Density(density.density, 1f), LayoutDirection.Ltr, 0)
            return Paginator(book, spec, m) { picture(it) }.run()
        }

        var pagination by remember(book) { mutableStateOf<Pagination?>(null) }
        LaunchedEffect(book, layoutKey) {
            if (pagination?.spec?.sameLayout(spec) == true) return@LaunchedEffect
            pagination = withContext(Dispatchers.Default) { paginate() }
        }
        val pg = pagination
        if (pg == null || pg.pages.isEmpty()) {
            OpeningScreen(shelf, controller.lastPage(shelf))
            return@BoxWithConstraints
        }

        val pages = pg.pages
        val index = pg.pageFor(locator.chapter, locator.block, locator.char).coerceIn(0, pages.lastIndex)
        val bitmaps = remember(pg, colors) { HashMap<Int, ImageBitmap>() }
        val toc = remember(book, pg) { tocRows(book, pg) }

        fun chapterName(i: Int): String = toc.lastOrNull { it.page <= i }?.title
            ?: book.chapters.getOrNull(pages[i].chapter)?.title.orEmpty()
        fun bitmap(i: Int): ImageBitmap = bitmaps.getOrPut(i) {
            if (bitmaps.size > 5) bitmaps.keys.filter { abs(it - i) > 2 }.forEach { bitmaps.remove(it) }
            renderToBitmap(w.toInt(), h.toInt(), density) {
                drawPage(pages[i], pg.spec, colors, measurer, book.title, "${i + 1}")
            }
        }
        fun goTo(i: Int) {
            val k = i.coerceIn(0, pages.lastIndex)
            val p = pages[k]
            locator = Locator(p.chapter, p.startBlock, p.startChar)
            controller.savePosition(shelf, locator, k, pages.size, chapterName(k))
        }
        fun goToLocator(l: Locator) = goTo(pg.pageFor(l.chapter, l.block, l.char))

        // record where we are (progress and chapter for the library) once the book is laid out
        LaunchedEffect(pg) { goTo(index) }

        // render this page and its neighbours ahead of time, one per frame, while nothing moves
        LaunchedEffect(pg, colors, index, w, h) {
            for (i in listOf(index, index + 1, index - 1)) {
                if (i !in pages.indices || bitmaps.containsKey(i)) continue
                withFrameNanos { }
                if (!turn.busy) bitmap(i)
            }
        }

        /** A curl for the finger at [finger] while the page grabbed at [grab] is turned. */
        fun curlAt(grab: Offset, finger: Offset) =
            Curl.forDrag(grab, finger, Curl.radiusFor(w, (grab - finger).getDistance()), h)

        /** Lets a curl go: on to [turned] (the page is over) or back flat. */
        suspend fun settle(turnedAway: Boolean) {
            val from = turn.curl ?: return
            val r = from.radius
            val target = if (turnedAway) Curl(0f, -r, r) else Curl(0f, w + r, r)
            val a = Animatable(0f)
            a.animateTo(1f, spring(dampingRatio = 1f, stiffness = 900f)) { turn.curl = Curl.lerp(from, target, value) }
        }

        suspend fun autoTurn(forward: Boolean) {
            if (turn.busy) return
            val target = if (forward) index + 1 else index - 1
            if (target !in pages.indices) return
            turn.busy = true
            when (settings.pageTurn) {
                PageTurn.Curl -> {
                    turn.top = if (forward) bitmap(index) else bitmap(index - 1)
                    turn.under = if (forward) bitmap(index + 1) else bitmap(index)
                    val grab = Offset(w, h * 0.86f)
                    fun at(t: Float) = curlAt(grab, Offset(w - t.coerceAtLeast(0.002f) * 2f * w, h * (0.86f - 0.05f * t)))
                    val a = Animatable(if (forward) 0f else 1f)
                    turn.curl = at(a.value)
                    a.animateTo(if (forward) 1f else 0f, tween(450, easing = Curl.Easing)) { turn.curl = at(value) }
                    goTo(target)
                    turn.curl = null
                }
                PageTurn.Slide -> {
                    val a = Animatable(0f)
                    a.animateTo(if (forward) -w else w, tween(250, easing = FastOutSlowInEasing)) { turn.slide = value }
                    goTo(target); turn.slide = 0f
                }
                PageTurn.Scroll -> goTo(target)
            }
            turn.busy = false
        }

        fun hitLink(pos: Offset): Boolean {
            val page = pages[index]
            page.columns.forEachIndexed { c, frags ->
                val x0 = pg.spec.columnX(c)
                for (f in frags) if (f is Frag.Lines) {
                    val left = x0 + f.x; val top = pg.spec.marginTop + f.y
                    if (pos.x < left || pos.x > left + f.layout.size.width || pos.y < top || pos.y > top + f.height) continue
                    val off = f.layout.getOffsetForPosition(Offset(pos.x - left, pos.y - top + f.layout.getLineTop(f.first)))
                    val ann = f.layout.layoutInput.text.getStringAnnotations(off, off).firstOrNull() ?: continue
                    if (ann.tag == "noteref") { note = book.footnotes[ann.item] ?: "(This note is empty.)"; panel = Panel.Note; return true }
                    if (ann.tag == "link") {
                        val path = ann.item.substringBefore('#'); val frag = ann.item.substringAfter('#', "")
                        val ci = book.chapterIndexFor(path)
                        if (ci >= 0) {
                            val bi = book.chapters[ci].blocks.indexOfFirst { b ->
                                frag.isNotEmpty() && ((b is Block.Text && frag in b.anchors) || (b is Block.Picture && frag in b.anchors))
                            }.coerceAtLeast(0)
                            goToLocator(Locator(ci, bi, 0))
                            return true
                        }
                    }
                }
            }
            return false
        }

        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

        val readerModifier = Modifier.fillMaxSize()
            .focusRequester(focus).focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionRight, Key.Spacebar, Key.PageDown, Key.VolumeDown -> { scope.launch { autoTurn(true) }; true }
                    Key.DirectionLeft, Key.PageUp, Key.VolumeUp -> { scope.launch { autoTurn(false) }; true }
                    Key.Escape -> { if (panel != null) panel = null else chrome = !chrome; true }
                    else -> false
                }
            }

        val lineH = settings.fontSizePx * density.density * settings.effectiveLineHeight
        val guide = if (settings.lineGuide) (if (guideY < 0f) h * 0.42f else guideY) else -1f

        if (settings.pageTurn == PageTurn.Scroll) {
            ScrollReader(pages, pg, colors, index, readerModifier, onTap = { chrome = !chrome }) { i -> goTo(i) }
        } else {
            Canvas(readerModifier
                .clipToBounds()
                .reserveEdgesForPageTurns()
                .pointerInput(pg, settings.pageTurn, index, settings.lineGuide) {
                    detectTapGestures(
                        onLongPress = { pos -> if (settings.lineGuide) guideY = pos.y },
                    ) { pos ->
                        if (chrome) { chrome = false; return@detectTapGestures }
                        if (hitLink(pos)) return@detectTapGestures
                        val edge = max(64f * density.density, w * 0.25f)
                        when {
                            pos.x < edge -> scope.launch { autoTurn(false) }
                            pos.x > w - edge -> scope.launch { autoTurn(true) }
                            else -> chrome = true
                        }
                    }
                }
                .pointerInput(pg, settings.pageTurn, index) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            if (turn.busy) return@detectDragGestures
                            chrome = false
                            val forward = pos.x > w / 2f
                            if ((forward && index >= pages.lastIndex) || (!forward && index <= 0)) return@detectDragGestures
                            turn.forward = forward; turn.start = pos; turn.busy = true; turn.velocity.resetTracking()
                            if (settings.pageTurn == PageTurn.Curl) {
                                turn.top = if (forward) bitmap(index) else bitmap(index - 1)
                                turn.under = if (forward) bitmap(index + 1) else bitmap(index)
                                turn.grab = Offset(w, pos.y.coerceIn(h * 0.04f, h * 0.96f))
                                turn.finger = if (forward) turn.grab else Offset(-w, turn.grab.y)
                                turn.curl = curlAt(turn.grab, turn.finger)
                            }
                        },
                        onDrag = { change, _ ->
                            if (!turn.busy) return@detectDragGestures
                            change.consume()
                            turn.velocity.addPosition(change.uptimeMillis, change.position)
                            val p = change.position
                            val dx = p.x - turn.start.x; val dy = p.y - turn.start.y
                            if (settings.pageTurn == PageTurn.Curl) {
                                // the grabbed edge follows the finger; going back, the page comes in from the left at twice the pace
                                val fx = if (turn.forward) turn.grab.x + dx else -w + 2f * dx
                                turn.finger = Offset(fx.coerceIn(-w, w - 0.5f), (turn.grab.y + dy).coerceIn(-h * 0.2f, h * 1.2f))
                                turn.curl = curlAt(turn.grab, turn.finger)
                            } else {
                                turn.slide = if (turn.forward) dx.coerceIn(-w, 0f) else dx.coerceIn(0f, w)
                            }
                        },
                        onDragEnd = {
                            if (!turn.busy) return@detectDragGestures
                            scope.launch {
                                val forward = turn.forward
                                val vx = turn.velocity.calculateVelocity().x / density.density // dp per second
                                val target = if (forward) index + 1 else index - 1
                                if (settings.pageTurn == PageTurn.Curl) {
                                    // past half the page, or a flick towards the turn, completes it
                                    val complete = if (forward) turn.finger.x < w * Curl.COMPLETE_FRACTION || vx < -300f
                                    else turn.finger.x > -w * Curl.COMPLETE_FRACTION || vx > 300f
                                    settle(turnedAway = forward == complete)
                                    if (complete) goTo(target)
                                    turn.curl = null
                                } else {
                                    val fling = if (forward) vx < -300f else vx > 300f
                                    val complete = abs(turn.slide) > w * 0.25f || fling
                                    val endX = if (!complete) 0f else if (forward) -w else w
                                    val a = Animatable(turn.slide)
                                    a.animateTo(endX, tween(220, easing = LinearOutSlowInEasing)) { turn.slide = value }
                                    if (complete) goTo(target)
                                    turn.slide = 0f
                                }
                                turn.busy = false
                            }
                        },
                        onDragCancel = {
                            scope.launch { settle(turnedAway = !turn.forward); turn.curl = null; turn.slide = 0f; turn.busy = false }
                        },
                    )
                }
            ) {
                val c = turn.curl; val top = turn.top; val under = turn.under
                if (settings.pageTurn == PageTurn.Curl && c != null && top != null && under != null) {
                    drawCurl(top, under, c, colors.page, pageDark)
                } else if (turn.slide != 0f) {
                    val other = if (turn.slide < 0) index + 1 else index - 1
                    drawImage(bitmap(index), topLeft = Offset(turn.slide, 0f))
                    if (other in pages.indices) drawImage(bitmap(other), topLeft = Offset(turn.slide + (if (turn.slide < 0) w else -w), 0f))
                    val edge = if (turn.slide < 0) w + turn.slide else turn.slide
                    drawLine(colors.secondary.copy(alpha = 0.25f), Offset(edge, 0f), Offset(edge, size.height), 1f)
                } else {
                    // the pre-rendered page is a single GPU texture; fall back to drawing the text
                    val ready = bitmaps[index]
                    if (ready != null) drawImage(ready)
                    else drawPage(pages[index], pg.spec, colors, measurer, book.title, "${index + 1}")
                    if (guide >= 0f) {
                        // line guide: everything outside a three-line band fades back
                        val band = lineH * 1.6f
                        val veil = colors.page.copy(alpha = 0.62f)
                        drawRect(veil, Offset.Zero, Size(size.width, (guide - band / 2f).coerceAtLeast(0f)))
                        drawRect(veil, Offset(0f, guide + band / 2f), Size(size.width, (size.height - guide - band / 2f).coerceAtLeast(0f)))
                    }
                }
            }
        }

        val bookmarked = controller.bookmarks(shelf).any { pg.pageFor(it.locator.chapter, it.locator.block, it.locator.char) == index }
        fun toggleBookmark() {
            if (bookmarked) controller.removeBookmarks(shelf) { pg.pageFor(it.locator.chapter, it.locator.block, it.locator.char) == index }
            else controller.addBookmark(shelf, Locator(pages[index].chapter, pages[index].startBlock, pages[index].startChar))
        }

        if (chrome && panel == null) BackAction { chrome = false }
        ReaderChrome(
            visible = chrome && panel == null,
            book = book, chapter = chapterName(index), pages = pages, index = index, bookmarked = bookmarked, settings = settings,
            onClose = onClose, onBookmark = { toggleBookmark() },
            onScrub = { goTo(it) },
            onContents = { panel = Panel.Contents }, onSearch = { panel = Panel.Search }, onThemes = { panel = Panel.Themes },
            onSettings = { controller.updateSettings(it) },
            onShare = { Hooks.share("${book.title}${if (book.author.isNotBlank()) " by ${book.author}" else ""} — reading it in JustRead") },
        )

        when (panel) {
            Panel.Themes -> ThemesSheet(settings, uiDark, onChange = { controller.updateSettings(it) },
                onCustomise = { panel = Panel.Customise }, onDismiss = { panel = null })
            Panel.Customise -> CustomiseScreen(settings, colors, onChange = { controller.updateSettings(it) },
                onFonts = { panel = Panel.Fonts }, onDone = { panel = Panel.Themes })
            Panel.Fonts -> FontScreen(settings, onChange = { controller.updateSettings(it) }, onBack = { panel = Panel.Customise })
            Panel.Contents -> ContentsSheet(shelf, controller, pg, toc, index,
                onPick = { goTo(it); panel = null; chrome = false },
                onPickLocator = { goToLocator(it); panel = null; chrome = false },
                onDismiss = { panel = null })
            Panel.Search -> SearchSheet(book, pg, toc,
                onPick = { goTo(it); panel = null; chrome = false }, onDismiss = { panel = null })
            Panel.Note -> JrSheet(onDismiss = { panel = null }) {
                SheetTitle("Note", onClose = { panel = null })
                Text(note.orEmpty(), style = JrType.bodyLarge, color = LocalJr.current.onBackground,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp))
            }
            null -> {}
        }
    }
}

/** The table of contents with the page each entry starts on (the spine's chapters if the book has no TOC). */
fun tocRows(book: EpubBook, pg: Pagination): List<TocRow> {
    val fromToc = book.toc.mapNotNull { e ->
        val ci = book.chapterIndexFor(e.path)
        if (ci < 0) null else {
            val bi = e.fragment?.let { f -> book.chapters[ci].blocks.indexOfFirst { b -> b is Block.Text && f in b.anchors } }?.takeIf { it >= 0 } ?: 0
            TocRow(e.title.ifBlank { book.chapters[ci].title }, e.level, pg.pageFor(ci, bi, 0))
        }
    }
    return fromToc.ifEmpty { book.chapters.mapIndexed { i, c -> TocRow(c.title.ifBlank { "Section ${i + 1}" }, 0, pg.chapterFirstPage[i]) } }
}

@Composable
private fun OpeningScreen(shelf: ShelfBook, page: Int?) {
    val jr = LocalJr.current
    val t = rememberInfiniteTransition()
    val progress = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart))
    val x = { progress.value }
    Box(Modifier.fillMaxSize().background(jr.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BookCover(shelf, Modifier.size(200.dp, 300.dp), corner = 6.dp)
            Spacer(Modifier.height(36.dp))
            Text(shelf.book.title, style = JrType.serif(24, 32), color = jr.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 32.dp))
            if (shelf.book.author.isNotBlank()) Text(shelf.book.author, style = JrType.bodyLarge, color = jr.onVariant)
            Spacer(Modifier.height(48.dp))
            Box(Modifier.size(128.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(jr.secondaryContainer)) {
                // read in the layout phase, so the running bar doesn't recompose every frame
                Box(Modifier.offset { IntOffset(((176f * x() - 48f) * density).roundToInt(), 0) }.size(48.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(jr.primary))
            }
            Spacer(Modifier.height(12.dp))
            Text(if (page != null) "Opening at page $page" else "Opening", style = JrType.caption, color = jr.onVariant)
        }
    }
}

@Composable
private fun ScrollReader(
    pages: List<Page>, pg: Pagination, colors: ThemeColors, index: Int,
    modifier: Modifier, onTap: () -> Unit, onVisible: (Int) -> Unit,
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = index)
    val density = LocalDensity.current
    LaunchedEffect(state) { snapshotFlow { state.firstVisibleItemIndex }.collect { onVisible(it) } }
    val pad = pg.spec.marginTop / 3f
    val itemH = with(density) { (pg.spec.columnHeight + pad).toDp() }
    LazyColumn(state = state, modifier = modifier.windowInsetsPadding(WindowInsets.statusBars).pointerInput(Unit) { detectTapGestures { onTap() } }) {
        itemsIndexed(pages) { i, page ->
            if (page.pageInChapter == 0 && i > 0) {
                Box(Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(60.dp).height(1.dp).background(colors.secondary.copy(alpha = 0.4f)))
                }
            }
            Canvas(Modifier.fillMaxWidth().height(itemH)) { drawColumns(page, pg.spec, colors, pad / 2f) }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

/** Top bar (back, title and chapter, bookmark) and bottom bar (scrubber, page count, actions). */
@Composable
private fun ReaderChrome(
    visible: Boolean, book: EpubBook, chapter: String, pages: List<Page>, index: Int, bookmarked: Boolean, settings: ReaderSettings,
    onClose: () -> Unit, onBookmark: () -> Unit, onScrub: (Int) -> Unit,
    onContents: () -> Unit, onSearch: () -> Unit, onThemes: () -> Unit,
    onSettings: (ReaderSettings) -> Unit, onShare: () -> Unit,
) {
    val jr = LocalJr.current
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
            Row(Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures { } }.background(jr.container).windowInsetsPadding(WindowInsets.statusBars)
                .height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconBtn(JrIcons.Back, "Back to library", jr.onBackground, onClick = onClose)
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = JrType.title, color = jr.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (chapter.isNotBlank()) Text(chapter, style = JrType.caption, color = jr.onVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconBtn(if (bookmarked) JrIcons.BookmarkFilled else JrIcons.Bookmark,
                    if (bookmarked) "Remove bookmark" else "Bookmark this page", if (bookmarked) jr.primary else jr.onBackground, onClick = onBookmark)
            }
        }
        AnimatedVisibility(visible, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            var scrub by remember(index) { mutableStateOf(index) }
            val last = max(1, pages.lastIndex)
            Column(Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures { } }.background(jr.container).windowInsetsPadding(WindowInsets.navigationBars).padding(top = 6.dp, bottom = 12.dp)) {
                JrSlider(scrub / last.toFloat(), "Position in book", Modifier.padding(horizontal = 24.dp),
                    onDone = { if (scrub != index) onScrub(scrub) }) { scrub = (it * last).roundToInt() }
                val p = pages[scrub.coerceIn(0, pages.lastIndex)]
                val left = p.chapterPageCount - p.pageInChapter - 1
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 4.dp)) {
                    Text("Page ${scrub + 1} of ${pages.size}", style = JrType.caption, color = jr.onVariant, modifier = Modifier.weight(1f))
                    Text(when { left <= 0 -> "Last page in chapter"; left == 1 -> "1 page left in chapter"; else -> "$left pages left in chapter" },
                        style = JrType.caption, color = jr.onVariant)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    ChromeAction("Contents", Modifier.weight(1f), onContents) { Icon(JrIcons.Contents, null, tint = it, modifier = Modifier.size(24.dp)) }
                    ChromeAction("Search", Modifier.weight(1f), onSearch) { Icon(JrIcons.Search, null, tint = it, modifier = Modifier.size(24.dp)) }
                    ChromeAction("Themes", Modifier.weight(1f), onThemes) {
                        Text("Aa", style = JrType.sans(18, 24, androidx.compose.ui.text.font.FontWeight.Medium), color = it, modifier = Modifier.height(24.dp))
                    }
                    var more by remember { mutableStateOf(false) }
                    Box(Modifier.weight(1f)) {
                        ChromeAction("More", Modifier.fillMaxWidth(), { more = true }) { Icon(JrIcons.More, null, tint = it, modifier = Modifier.size(24.dp)) }
                        DropdownMenu(more, onDismissRequest = { more = false }, containerColor = jr.menu) {
                            MenuRow(if (bookmarked) "Remove bookmark" else "Add bookmark", if (bookmarked) JrIcons.BookmarkFilled else JrIcons.Bookmark) { more = false; onBookmark() }
                            MenuRow("Line guide", JrIcons.LineGuide, if (settings.lineGuide) "On" else "Off") { onSettings(settings.copy(lineGuide = !settings.lineGuide)) }
                            MenuRow("Lock rotation", JrIcons.LockRotation, if (settings.lockRotation) "On" else "Off") { onSettings(settings.copy(lockRotation = !settings.lockRotation)) }
                            MenuRow("Share", JrIcons.Share) { more = false; onShare() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChromeAction(label: String, modifier: Modifier, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    val jr = LocalJr.current
    Column(modifier.height(64.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        icon(jr.onVariant)
        Text(label, style = JrType.labelSmall, color = jr.onVariant)
    }
}
