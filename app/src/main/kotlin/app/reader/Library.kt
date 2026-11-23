package app.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun Library(controller: AppController) {
    val jr = LocalJr.current
    @Suppress("UNUSED_VARIABLE") val version = controller.metaVersion // recompose when saved data changes
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sortSheet by remember { mutableStateOf(false) }
    var goalDialog by remember { mutableStateOf(false) }

    val books = controller.sortedShelf().let { all ->
        if (query.isBlank()) all else all.filter { it.title.contains(query, true) || it.author.contains(query, true) }
    }
    val current = controller.shelf.filter { controller.position(it) != null && !controller.finished(it) }
        .maxByOrNull { controller.lastOpened(it) }
    val grid = controller.view == ShelfView.Grid
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize().background(jr.background)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            TopBar(controller, searching, query, onQuery = { query = it }, onSearch = { searching = it; if (!it) query = "" },
                onGoal = { goalDialog = true })
            LazyVerticalGrid(
                columns = if (grid) GridCells.Adaptive(150.dp) else GridCells.Fixed(1),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = if (grid) 24.dp else 0.dp, end = if (grid) 24.dp else 0.dp, bottom = 24.dp + navBottom),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(if (grid) 20.dp else 0.dp),
            ) {
                val full: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
                if (!searching) {
                    if (controller.update != UpdateState.Idle) item(span = full) {
                        UpdateCard(controller, Modifier.padding(horizontal = if (grid) 0.dp else 16.dp).padding(top = 8.dp, bottom = if (grid) 0.dp else 12.dp))
                    }
                    if (current != null) item(span = full) {
                        ContinueCard(controller, current, Modifier.padding(horizontal = if (grid) 0.dp else 16.dp).padding(top = 8.dp, bottom = if (grid) 0.dp else 12.dp))
                    }
                    item(span = full) {
                        GoalCard(controller, Modifier.padding(horizontal = if (grid) 0.dp else 16.dp)
                            .padding(top = if (current != null) 0.dp else 8.dp, bottom = if (grid) 0.dp else 12.dp)) { goalDialog = true }
                    }
                }
                item(span = full) {
                    ShelfHeader(controller, books.size, Modifier.padding(start = if (grid) 0.dp else 24.dp, end = if (grid) 0.dp else 4.dp)) { sortSheet = true }
                }
                if (books.isEmpty()) item(span = full) {
                    Text(if (query.isNotBlank()) "No books match “$query”" else "Your library is empty",
                        style = JrType.body, color = jr.onVariant, modifier = Modifier.padding(24.dp))
                }
                items(books, key = { it.key }) { b ->
                    if (grid) GridBook(controller, b) else ListBook(controller, b)
                }
            }
        }
        if (sortSheet) SortSheet(controller) { sortSheet = false }
        if (goalDialog) GoalDialog(controller) { goalDialog = false }
    }
}

@Composable
private fun TopBar(controller: AppController, searching: Boolean, query: String, onQuery: (String) -> Unit, onSearch: (Boolean) -> Unit, onGoal: () -> Unit) {
    val jr = LocalJr.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(64.dp).padding(start = if (searching) 4.dp else 24.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (searching) {
            BackAction { onSearch(false) }
            IconBtn(JrIcons.Back, "Close search", jr.onBackground) { onSearch(false) }
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text("Search your library", style = JrType.bodyLarge, color = jr.onVariant)
                BasicTextField(query, onQuery, singleLine = true, textStyle = JrType.bodyLarge.copy(color = jr.onBackground),
                    cursorBrush = SolidColor(jr.primary), modifier = Modifier.fillMaxWidth().focusRequester(focus))
            }
            if (query.isNotEmpty()) IconBtn(JrIcons.Close, "Clear search", jr.onVariant) { onQuery("") }
        } else {
            Text(buildAnnotatedString {
                append("Just")
                withStyle(SpanStyle(color = jr.primary)) { append("Read") }
            }, style = JrType.wordmark, color = jr.onBackground, modifier = Modifier.weight(1f).semantics { contentDescription = "JustRead" })
            IconBtn(JrIcons.Search, "Search library", jr.onBackground) { onSearch(true) }
            Box {
                IconBtn(JrIcons.More, "More options", jr.onBackground) { menu = true }
                DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = jr.menu) {
                    if (controller.requestOpenFile != null) MenuRow("Add a book", JrIcons.Plus) { menu = false; controller.requestOpenFile?.invoke() }
                    MenuRow("Daily reading goal", JrIcons.Target) { menu = false; onGoal() }
                    controller.checkForUpdates?.let { check -> MenuRow("Check for updates", JrIcons.Download) { menu = false; check(true) } }
                }
            }
        }
    }
}

@Composable
fun MenuRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, trailing: String? = null, onClick: () -> Unit) {
    val jr = LocalJr.current
    DropdownMenuItem(
        text = { Text(label, style = JrType.bodyLarge, color = jr.onBackground) },
        leadingIcon = if (icon != null) { { Icon(icon, null, tint = jr.onVariant, modifier = Modifier.size(24.dp)) } } else null,
        trailingIcon = if (trailing != null) { { Text(trailing, style = JrType.labelSmall, color = jr.onVariant) } } else null,
        onClick = onClick,
        modifier = Modifier.width(232.dp),
    )
}

@Composable
private fun ContinueCard(controller: AppController, b: ShelfBook, modifier: Modifier) {
    val jr = LocalJr.current
    val progress = controller.progress(b) ?: 0f
    val pct = (progress * 100).roundToInt()
    Row(modifier.fillMaxWidth().height(128.dp).clip(RoundedCornerShape(12.dp)).background(jr.container)
        .clickable { controller.reading = b }.padding(16.dp)
        .semantics { contentDescription = "Resume ${b.title}, $pct% read" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        BookCover(b, Modifier.width(64.dp).height(96.dp), showText = false)
        Column(Modifier.weight(1f).height(96.dp)) {
            Text("Continue reading", style = JrType.labelSmall, color = jr.onVariant)
            Text(b.title, style = JrType.titleLarge, color = jr.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp))
            controller.chapterTitle(b)?.let {
                Text(it, style = JrType.body, color = jr.onVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StopProgress(progress, Modifier.weight(1f))
                Text("$pct%", style = JrType.labelSmall, color = jr.onVariant)
            }
        }
        Box(Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(jr.primary).clickable { controller.reading = b }
            .padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
            Text("Resume", style = JrType.label, color = jr.onPrimary)
        }
    }
}

/** An app update: available, downloading, waiting for the installer, or what went wrong. */
@Composable
private fun UpdateCard(controller: AppController, modifier: Modifier) {
    val jr = LocalJr.current
    val s = controller.update
    val (title, detail) = when (s) {
        UpdateState.Idle -> return
        UpdateState.Checking -> "Checking for updates…" to null
        is UpdateState.UpToDate -> "JustRead is up to date" to "Version ${s.version}"
        is UpdateState.Available -> "JustRead ${s.version} is available" to "Update to get the latest changes."
        is UpdateState.Downloading -> "Downloading JustRead ${s.version}" to "${(s.fraction * 100).roundToInt()}%"
        is UpdateState.Installing -> "Installing JustRead ${s.version}" to "Confirm the update when Android asks."
        is UpdateState.Failed -> "Update didn't finish" to s.message
    }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(jr.container).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(JrIcons.Download, null, tint = jr.primary, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = JrType.label, color = jr.onBackground)
                if (detail != null) Text(detail, style = JrType.caption, color = jr.onVariant)
            }
            when (s) {
                is UpdateState.Available, is UpdateState.Failed -> {
                    val label = if (s is UpdateState.Available) "Update" else "Try again"
                    Box(Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(jr.primary)
                        .clickable { if (s is UpdateState.Available) controller.installUpdate?.invoke() else controller.checkForUpdates?.invoke(true) }
                        .padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = JrType.label, color = jr.onPrimary)
                    }
                }
                else -> {}
            }
            if (s !is UpdateState.Downloading && s !is UpdateState.Checking)
                IconBtn(JrIcons.Close, "Dismiss", jr.onVariant, size = 40.dp) { controller.update = UpdateState.Idle }
        }
        if (s is UpdateState.Downloading) StopProgress(s.fraction, Modifier.fillMaxWidth().padding(end = 8.dp))
    }
}

@Composable
private fun GoalCard(controller: AppController, modifier: Modifier, onClick: () -> Unit) {
    val jr = LocalJr.current
    val goal = controller.goalMinutes
    val minutes = controller.secondsToday() / 60
    val streak = controller.streak()
    Row(modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, jr.outlineVariant, RoundedCornerShape(12.dp))
        .clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        GoalRing(minutes / goal.toFloat())
        Column(Modifier.weight(1f)) {
            Text(if (minutes >= goal) "Goal reached · $minutes minutes today" else "$minutes of $goal minutes today",
                style = JrType.label, color = jr.onBackground)
            Text(when (streak) { 0 -> "Daily goal"; 1 -> "Daily goal · 1 day in a row"; else -> "Daily goal · $streak days in a row" },
                style = JrType.caption, color = jr.onVariant)
        }
    }
}

@Composable
private fun ShelfHeader(controller: AppController, count: Int, modifier: Modifier, onSort: () -> Unit) {
    val jr = LocalJr.current
    Row(modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("All books", style = JrType.title, color = jr.onBackground)
            Text("$count", style = JrType.body, color = jr.onVariant, modifier = Modifier.padding(bottom = 2.dp))
        }
        Row(Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, jr.outlineVariant, RoundedCornerShape(8.dp))
            .clickable(onClick = onSort).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(JrIcons.Sort, null, tint = jr.primary, modifier = Modifier.size(18.dp))
            Text(controller.sortBy.label, style = JrType.label, color = jr.onBackground)
            Icon(JrIcons.ChevronDown, null, tint = jr.onBackground, modifier = Modifier.size(18.dp))
        }
        val grid = controller.view == ShelfView.Grid
        IconBtn(if (grid) JrIcons.Contents else JrIcons.Grid, if (grid) "Show as list" else "Show as grid", jr.onVariant) {
            controller.updateView(if (grid) ShelfView.List else ShelfView.Grid)
        }
    }
}

/** The small status under a book: a progress bar, "New", or "Finished". */
@Composable
private fun BookStatus(controller: AppController, b: ShelfBook, compact: Boolean) {
    val jr = LocalJr.current
    val p = controller.progress(b)
    when {
        p == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.height(20.dp).clip(RoundedCornerShape(10.dp)).background(jr.primary).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                Text("New", style = JrType.labelSmall.copy(fontSize = JrType.labelSmall.fontSize * 0.92f), color = jr.onPrimary)
            }
        }
        p >= 0.999f -> Text("Finished", style = JrType.labelSmall, color = jr.onVariant)
        compact -> Text("${(p * 100).roundToInt()}%", style = JrType.labelSmall, color = jr.onVariant)
        else -> Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StopProgress(p, Modifier.weight(1f))
            Text("${(p * 100).roundToInt()}%", style = JrType.labelSmall, color = jr.onVariant)
        }
    }
}

@Composable
private fun BookMenu(controller: AppController, b: ShelfBook) {
    val jr = LocalJr.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconBtn(JrIcons.More, "Options for ${b.title}", jr.onVariant, size = 44.dp) { open = true }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = jr.menu) {
            MenuRow("Open", JrIcons.ChevronRight) { open = false; controller.reading = b }
            if (controller.finished(b)) MenuRow("Mark as unread", JrIcons.Check) { open = false; controller.markFinished(b, false) }
            else MenuRow("Mark as finished", JrIcons.Check) { open = false; controller.markFinished(b, true) }
            if (b.storeId != null) MenuRow("Remove from library", JrIcons.Trash) { open = false; controller.remove(b) }
        }
    }
}

@Composable
private fun GridBook(controller: AppController, b: ShelfBook) {
    val jr = LocalJr.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BookCover(b, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clickable { controller.reading = b })
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(b.title, style = JrType.bookTitle, color = jr.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (b.author.isNotBlank()) Text(b.author, style = JrType.caption, color = jr.onVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.padding(top = 2.dp)) { BookStatus(controller, b, compact = false) }
            }
            Box(Modifier.padding(end = 0.dp).padding(top = 0.dp)) { BookMenu(controller, b) }
        }
    }
}

@Composable
private fun ListBook(controller: AppController, b: ShelfBook) {
    val jr = LocalJr.current
    Column {
        Row(Modifier.fillMaxWidth().clickable { controller.reading = b }.padding(start = 24.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BookCover(b, Modifier.width(40.dp).height(60.dp), showText = false, corner = 3.dp)
            Column(Modifier.weight(1f)) {
                Text(b.title, style = JrType.bookTitle, color = jr.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (b.author.isNotBlank()) Text(b.author, style = JrType.body, color = jr.onVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BookStatus(controller, b, compact = true)
            BookMenu(controller, b)
        }
        Divider(Modifier.padding(start = 80.dp, end = 16.dp))
    }
}

@Composable
private fun SortSheet(controller: AppController, onDismiss: () -> Unit) {
    val jr = LocalJr.current
    JrSheet(onDismiss) {
        SheetTitle("Sort and view", null)
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            SectionLabel("Sort by", Modifier.padding(top = 4.dp, bottom = 4.dp))
            for (s in SortBy.entries) {
                Row(Modifier.fillMaxWidth().height(52.dp).clickable { controller.updateSort(s) }, verticalAlignment = Alignment.CenterVertically) {
                    Text(s.label, style = JrType.bodyLarge, color = jr.onBackground, modifier = Modifier.weight(1f))
                    RadioButton(selected = controller.sortBy == s, onClick = { controller.updateSort(s) },
                        colors = RadioButtonDefaults.colors(selectedColor = jr.primary, unselectedColor = jr.onVariant))
                }
            }
            SectionLabel("Show as", Modifier.padding(top = 12.dp, bottom = 8.dp))
            Segmented(listOf("Grid", "List"), controller.view.ordinal) { controller.updateView(ShelfView.entries[it]) }
        }
    }
}

@Composable
private fun GoalDialog(controller: AppController, onDismiss: () -> Unit) {
    val jr = LocalJr.current
    JrSheet(onDismiss) {
        SheetTitle("Daily reading goal", null)
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Minutes of reading a day. The ring on your library fills as you read.", style = JrType.body, color = jr.onVariant,
                modifier = Modifier.padding(bottom = 8.dp))
            for (m in listOf(5, 10, 15, 20, 30, 45, 60)) {
                Row(Modifier.fillMaxWidth().height(52.dp).clickable { controller.updateGoal(m); onDismiss() }, verticalAlignment = Alignment.CenterVertically) {
                    Text("$m minutes", style = JrType.bodyLarge, color = jr.onBackground, modifier = Modifier.weight(1f))
                    RadioButton(selected = controller.goalMinutes == m, onClick = { controller.updateGoal(m); onDismiss() },
                        colors = RadioButtonDefaults.colors(selectedColor = jr.primary, unselectedColor = jr.onVariant))
                }
            }
        }
    }
}
