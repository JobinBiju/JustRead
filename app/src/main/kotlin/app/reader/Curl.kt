package app.reader

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private const val PI_F = PI.toFloat()

/**
 * A page rolled round a cylinder. The page lies flat up to the
 * contact line, wraps half-way round a cylinder of [radius], then runs back over itself face down.
 * The screen looks straight down on it.
 *
 * All lengths are px in the page's own space (origin top-left).
 * - [angleDeg]: direction of n = (cos θ, sin θ), the unit normal of the curl line, pointing from the
 *   flat part towards the part being turned. 0° = the curl line is vertical.
 * - [position]: where the standing edge of the roll sits, measured along n.
 * - [radius]: the cylinder's radius. The contact line is at position − radius.
 */
data class Curl(val angleDeg: Float, val position: Float, val radius: Float) {
    private val theta = angleDeg * PI_F / 180f
    val nx = cos(theta)
    val ny = sin(theta)

    /** Where the paper lifts off the page below. */
    val contact get() = position - radius

    /** How far a page point sits past the contact line, along the paper. */
    fun s(x: Float, y: Float) = x * nx + y * ny - contact

    /** Where paper at s lands on screen, measured from the contact line. */
    fun g(s: Float): Float = when {
        s <= 0f -> s
        s < PI_F * radius -> radius * sin(s / radius)
        else -> PI_F * radius - s
    }

    /** Page point to screen point. */
    fun warp(x: Float, y: Float): Offset {
        val sv = s(x, y)
        val shift = g(sv) - sv
        return Offset(x + shift * nx, y + shift * ny)
    }

    companion object {
        /** Cubic Bézier (0.44, 0, 0.56, 1): the curl timing curve. */
        val Easing = CubicBezierEasing(0.44f, 0f, 0.56f, 1f)

        /** Releasing a dragged page past half way completes the turn. */
        const val COMPLETE_FRACTION = 0.5f

        /** Roll radius for a drag: never bigger than the paper pulled so far, at most 0.3 × page width. */
        fun radiusFor(pageWidth: Float, dragDistance: Float): Float =
            min(0.3f * pageWidth, dragDistance / PI_F).coerceAtLeast(2f)

        /** The curl that puts the grabbed page point under the finger, at a given angle. */
        private fun at(angleDeg: Float, grab: Offset, finger: Offset, radius: Float): Curl {
            val t = angleDeg * PI_F / 180f
            val nx = cos(t); val ny = sin(t)
            // the face-down part is a mirror image: the mirror line sits half-way between the two points
            val mirror = (grab.x + finger.x) / 2f * nx + (grab.y + finger.y) / 2f * ny
            return Curl(angleDeg, mirror - (PI_F / 2f - 1f) * radius, radius)
        }

        /**
         * The curl that puts [grab] under [finger]. While dragging, the paper must never lift off the
         * bound edge (x = 0): contact ≥ max(0, page height × sin θ). If the drag breaks that, the angle
         * eases towards 0 until it holds.
         */
        fun forDrag(grab: Offset, finger: Offset, radius: Float, pageHeight: Float): Curl {
            val dx = grab.x - finger.x
            val dy = grab.y - finger.y
            val len = hypot(dx, dy).coerceAtLeast(1f)
            val angle = atan2(dy / len, dx / len) * 180f / PI_F
            fun holds(c: Curl) = c.contact >= max(0f, pageHeight * c.ny)
            val c = at(angle, grab, finger, radius)
            if (holds(c) || abs(angle) < 0.01f) return c
            // The largest angle (towards the finger's) that keeps the bound edge down. Found by
            // bisection so it changes smoothly with the finger: stepping it in fixed ratios made the
            // page jump between angles while held at the limit.
            var ok = 0f; var bad = 1f
            repeat(18) {
                val mid = (ok + bad) / 2f
                if (holds(at(angle * mid, grab, finger, radius))) ok = mid else bad = mid
            }
            return at(angle * ok, grab, finger, radius)
        }

        fun lerp(a: Curl, b: Curl, t: Float) = Curl(
            a.angleDeg + (b.angleDeg - a.angleDeg) * t,
            a.position + (b.position - a.position) * t,
            a.radius + (b.radius - a.radius) * t,
        )
    }
}

/**
 * The curl shader (AGSL, Android 13+). It runs once per screen
 * pixel and works backwards: which point of the paper shows here, and is it the front or the back?
 * It checks the top layer first, so the sheet coming back over the roll hides the sheet going up it.
 */
const val CURL_SHADER = """
uniform shader page;   // the page being turned, as drawn
uniform float2 size;   // page size
uniform float2 n;      // cos and sin of the angle
uniform float pos;     // standing edge, along n
uniform float r;       // cylinder radius
uniform float3 paper;  // the back of the sheet
uniform float3 shade;  // shadow colour
uniform float shadowK; // shadow strength
uniform float dp;      // px per dp: shadow sizes are in dp, like the design's CSS px

const float PI = 3.14159265;

bool onPage(float2 p) {
    return p.x >= 0.0 && p.y >= 0.0 && p.x <= size.x && p.y <= size.y;
}

// Signed distance to the page rectangle: negative inside.
float sdPage(float2 p) {
    float2 q = abs(p - size * 0.5) - size * 0.5;
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0);
}

// Signed distance from screen point p to the curled sheet's outline (negative on paper).
// flapOnly: just the face-down layer. Each layer is bounded by the roll's standing edge (d = r).
float sheetDist(float2 p, bool flapOnly) {
    float d = dot(p, n) - (pos - r);
    if (d > r) {
        float sb = sdPage(p + n * ((PI / 2.0) * r - d));
        return length(float2(d - r, max(sb, 0.0)));
    }
    float a = d > 0.0 ? asin(d / r) : 0.0;
    float su = max(sdPage(p + n * (d > 0.0 ? r * (PI - a) - d : PI * r - 2.0 * d)), d - r);
    if (flapOnly) return su;
    float sl = max(sdPage(p + n * (d > 0.0 ? r * a - d : 0.0)), d - r);
    return min(su, sl);
}

// A CSS drop-shadow of a shape, at the point dist px from its edge (after the offset):
// the shape blurred with sigma dp, i.e. a Gaussian step, approximated by a logistic.
float blurStep(float dist, float sigma) {
    return 1.0 / (1.0 + exp(1.702 * dist / (sigma * dp)));
}

float stack(float a, float b) { return 1.0 - (1.0 - a) * (1.0 - b); }

// The design's sheet shadow, filter: drop-shadow(12dp along n, blur 26) 0.34, then drop-shadow(2dp, blur 5) 0.22.
// The offsets follow n, so only edges facing the turn throw a wide shadow. The second filter also
// shadows the first one's shadow, as CSS filters chain.
float castShadow(float2 q) {
    if (sheetDist(q, false) > 64.0 * dp) return 0.0; // too far from the paper for any shadow
    float a12 = 0.34 * blurStep(sheetDist(q - n * (12.0 * dp), false), 13.0);
    float a14 = 0.34 * blurStep(sheetDist(q - n * (14.0 * dp), false), 13.0);
    float b = 0.22 * stack(blurStep(sheetDist(q - n * (2.0 * dp), false), 2.5), a14);
    return stack(a12, b);
}

// The face-down flap onto what lies under it: drop-shadow(4dp against n, blur 9) 0.20,
// then drop-shadow(14dp, blur 26) 0.13.
float flapShadow(float2 q) {
    if (sheetDist(q, true) > 48.0 * dp) return 0.0;
    float a4 = 0.20 * blurStep(sheetDist(q + n * (4.0 * dp), true), 4.5);
    float a18 = 0.20 * blurStep(sheetDist(q + n * (18.0 * dp), true), 4.5);
    float b = 0.13 * stack(blurStep(sheetDist(q + n * (14.0 * dp), true), 13.0), a18);
    return stack(a4, b);
}

half4 main(float2 q) {
    float d = dot(q, n) - (pos - r); // distance past the contact line
    float a = d > 0.0 && d <= r ? asin(d / r) : 0.0; // 0 at contact, quarter-turn at the edge
    if (d <= r) {
        // Top layer first: the sheet coming back over the roll, back showing.
        float2 up = q + n * (d > 0.0 ? r * (PI - a) - d : PI * r - 2.0 * d);
        if (onPage(up)) {
            float u = 1.0 - a / (PI / 2.0);
            float sh = 0.26 * pow(max(0.0, 1.0 - u / 0.45), 1.6);
            half3 c = mix(half3(paper), page.eval(up).rgb, 0.24); // print shows through
            return half4(c * (1.0 - sh), 1.0);
        }
        // Then the sheet going up the roll, or still flat: front showing, under the flap's shadow.
        float2 lo = q + n * (d > 0.0 ? r * a - d : 0.0);
        if (onPage(lo)) {
            float rim = d > 0.0 ? 0.13 * pow(max(0.0, 1.0 - (1.0 - d / r) / 0.4), 2.0) : 0.0;
            float dim = min(0.85, shadowK * flapShadow(q));
            half4 c = page.eval(lo);
            return half4(mix(c.rgb * (1.0 - rim), half3(shade), half(dim)), c.a);
        }
    }
    // No paper here: the next page, under the sheet's shadow and the flap's.
    float s = min(0.85, shadowK * stack(castShadow(q), flapShadow(q)));
    return half4(half3(shade) * s, s);
}
"""

/**
 * Draws [top] curled by [curl] over [under]. Uses the shader where the platform has one, else draws
 * the paper in strips parallel to the curl line.
 */
fun DrawScope.drawCurl(top: ImageBitmap, under: ImageBitmap, curl: Curl, paper: Color, darkPage: Boolean) {
    drawImage(under)
    val shade = if (darkPage) Color.Black else Color(0xFF2E2814)
    val k = if (darkPage) 1.6f else 1f
    if (drawCurlWithShader(top, curl, paper, shade, k)) return
    drawCurlStrips(top, curl, paper, shade, k)
}

/** How far the cast shadow reaches past the paper edge, in dp (where it has faded to nothing). */
private const val CAST_REACH_DP = 48f

/** Same profile as the shader's castShadow, [x] dp from the edge. */
private fun castShadowAlpha(x: Float, k: Float): Float {
    fun tail(off: Float, sigma: Float) = 1f / (1f + kotlin.math.exp(1.702f * (x - off) / sigma))
    fun stack(a: Float, b: Float) = 1f - (1f - a) * (1f - b)
    val a = stack(0.34f * tail(12f, 13f), 0.22f * stack(tail(2f, 2.5f), 0.34f * tail(14f, 13f)))
    return (a * k).coerceAtMost(0.85f)
}

/** Paper used by a quarter-turn is πr/2; each half of the roll is drawn in this many strips. */
private const val STRIPS = 22
private const val FAR = 100_000f

/** Below Android 13: the page cut into strips parallel to the curl line, each squeezed to follow the roll. */
private fun DrawScope.drawCurlStrips(page: ImageBitmap, curl: Curl, paper: Color, shade: Color, k: Float) {
    val half = PI_F * curl.radius / 2f
    val pageSize = Size(page.width.toFloat(), page.height.toFloat())

    // shadow cast on the next page beyond the standing edge, where the roll has paper
    val span = vSpanPast(curl, pageSize, min(curl.radius, 1f))
    if (span != null) {
        rotate(curl.angleDeg, pivot = Offset.Zero) {
            val reach = CAST_REACH_DP * density
            val stops = Array(13) { i ->
                val f = i / 12f
                f to shade.copy(alpha = castShadowAlpha(f * CAST_REACH_DP, k))
            }
            drawRect(
                Brush.horizontalGradient(*stops, startX = curl.position, endX = curl.position + reach),
                topLeft = Offset(curl.position, span.first), size = Size(reach, span.second - span.first),
            )
        }
    }
    // 1. flat part
    drawStrip(curl, page, -FAR, 0f, 1f, null, 0f, pageSize)
    // 2. front, going up the roll (darkening a little towards the standing edge)
    for (i in 0 until STRIPS) {
        val a = half * i / STRIPS
        val dRel = sin((a + half / STRIPS / 2f) / curl.radius) // d / r
        val rim = 0.13f * max(0f, 1f - (1f - dRel) / 0.4f).pow(2)
        drawStrip(curl, page, a, a + half / STRIPS, 1f, null, rim, pageSize)
    }
    // 3. back, coming over the top: paper with the print showing through faintly, shaded at the edge
    for (i in 0 until STRIPS) {
        val a = half + half * i / STRIPS
        val turn = (a + half / STRIPS / 2f) / curl.radius // angle round the roll, π/2 … π
        val u = 1f - (PI_F - turn) / (PI_F / 2f)
        val sh = 0.26f * max(0f, 1f - u / 0.45f).pow(1.6f)
        drawStrip(curl, page, a, a + half / STRIPS, 0.24f, paper, sh, pageSize)
    }
    // 4. flat, face down
    drawStrip(curl, page, 2 * half, FAR, 0.24f, paper, 0f, pageSize)
}

/** The paper from a to b past the contact line, drawn where the roll puts it. */
private fun DrawScope.drawStrip(curl: Curl, page: ImageBitmap, a: Float, b: Float, ink: Float, paper: Color?, darken: Float, pageSize: Size) {
    // squeeze along n; a negative k mirrors the strip
    val ga = if (a <= -FAR / 2) a else curl.g(a)
    val gb = if (b >= FAR / 2) PI_F * curl.radius - b else curl.g(b)
    val k = if (a <= -FAR / 2) 1f else (gb - ga) / (b - a)
    val t = (1 - k) * curl.contact + ga - k * a
    val nx = curl.nx; val ny = curl.ny
    val m = Matrix()
    m.values[Matrix.ScaleX] = 1 + (k - 1) * nx * nx
    m.values[Matrix.SkewX] = (k - 1) * nx * ny
    m.values[Matrix.TranslateX] = t * nx
    m.values[Matrix.SkewY] = (k - 1) * nx * ny
    m.values[Matrix.ScaleY] = 1 + (k - 1) * ny * ny
    m.values[Matrix.TranslateY] = t * ny
    withTransform({ transform(m) }) {
        rotate(curl.angleDeg, pivot = Offset.Zero) { // x now runs along n
            clipRect(curl.contact + a - 0.6f, -FAR, curl.contact + b + 0.6f, FAR) {
                rotate(-curl.angleDeg, pivot = Offset.Zero) {
                    if (paper != null) drawRect(paper, size = pageSize)
                    drawImage(page, alpha = ink)
                    if (darken > 0.003f) drawRect(Color.Black.copy(alpha = darken), size = pageSize)
                }
            }
        }
    }
}

/**
 * The span across the curl line of the page's points lying at least [past] beyond the contact line,
 * or null. Clips the page rectangle, in the curl's (u, v) frame, to u ≥ contact + past.
 */
private fun vSpanPast(curl: Curl, size: Size, past: Float): Pair<Float, Float>? {
    val u0 = curl.contact + past
    val pts = listOf(Offset(0f, 0f), Offset(size.width, 0f), Offset(size.width, size.height), Offset(0f, size.height))
        .map { Offset(it.x * curl.nx + it.y * curl.ny, -it.x * curl.ny + it.y * curl.nx) }
    var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
    for (i in pts.indices) {
        val a = pts[i]; val b = pts[(i + 1) % pts.size]
        if (a.x >= u0) { lo = min(lo, a.y); hi = max(hi, a.y) }
        if ((a.x - u0) * (b.x - u0) < 0f) {
            val v = a.y + (b.y - a.y) * (u0 - a.x) / (b.x - a.x)
            lo = min(lo, v); hi = max(hi, v)
        }
    }
    return if (lo <= hi) lo to hi else null
}
