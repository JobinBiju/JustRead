package app.reader

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

/** Draws one page: paper, running header (book title), the text columns and the page number. */
fun DrawScope.drawPage(
    page: Page?,
    spec: PageSpec,
    colors: ThemeColors,
    measurer: TextMeasurer,
    header: String,
    footer: String,
) {
    drawRect(colors.page)
    val small = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif,
        color = colors.secondary, textAlign = TextAlign.Center)
    val dp = density
    if (header.isNotEmpty()) {
        val l = measurer.measure(header, small, maxLines = 1, overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = (size.width * 0.7f).toInt().coerceAtLeast(1)))
        drawText(l, topLeft = Offset((size.width - l.size.width) / 2f, spec.insetTop + 22f * dp - l.size.height / 2f))
    }
    if (footer.isNotEmpty()) {
        val l = measurer.measure(footer, small, maxLines = 1)
        drawText(l, topLeft = Offset((size.width - l.size.width) / 2f, size.height - spec.insetBottom - 26f * dp - l.size.height / 2f))
    }
    if (page != null) drawColumns(page, spec, colors, spec.marginTop)
}

fun DrawScope.drawColumns(page: Page, spec: PageSpec, colors: ThemeColors, top: Float) {
    page.columns.forEachIndexed { c, frags ->
        val x0 = spec.columnX(c)
        for (f in frags) when (f) {
            is Frag.Lines -> {
                val y = top + f.y
                clipRect(left = x0 - spec.marginH * 0.8f, top = y, right = x0 + spec.columnWidth + spec.marginH * 0.8f, bottom = y + f.height) {
                    drawText(f.layout, color = if (f.secondary) colors.secondary else colors.text,
                        topLeft = Offset(x0 + f.x, y - f.layout.getLineTop(f.first)))
                }
            }
            is Frag.Picture -> {
                val img = f.image
                if (img != null) drawImage(img, IntOffset.Zero, IntSize(img.width, img.height),
                    IntOffset((x0 + f.x).toInt(), (top + f.y).toInt()), IntSize(f.w.toInt().coerceAtLeast(1), f.h.toInt().coerceAtLeast(1)))
                else drawRoundRect(colors.secondary.copy(alpha = 0.2f), Offset(x0 + f.x, top + f.y), Size(f.w, f.h), CornerRadius(8f))
            }
            is Frag.Rule -> drawLine(colors.secondary.copy(alpha = 0.6f), Offset(x0 + f.x, top + f.y), Offset(x0 + f.x + f.w, top + f.y), strokeWidth = 1.5f)
        }
    }
}

/**
 * Paints into an offscreen picture, used for the curl and slide turns. From Android 9 the drawing is
 * recorded and rasterised on the GPU into a hardware bitmap: no CPU text rendering on the main thread
 * and no texture upload when the picture is first drawn. Older versions paint a software bitmap.
 */
fun renderToBitmap(width: Int, height: Int, density: Density, block: DrawScope.() -> Unit): ImageBitmap {
    val w = width.coerceAtLeast(1); val h = height.coerceAtLeast(1)
    val size = Size(w.toFloat(), h.toFloat())
    if (Build.VERSION.SDK_INT >= 28) {
        val picture = android.graphics.Picture()
        val canvas = Canvas(picture.beginRecording(w, h))
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas, size, block)
        picture.endRecording()
        runCatching { return Bitmap.createBitmap(picture, w, h, Bitmap.Config.HARDWARE).asImageBitmap() }
    }
    val bmp = ImageBitmap(w, h)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bmp), size, block)
    return bmp
}
