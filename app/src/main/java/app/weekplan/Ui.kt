package app.weekplan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View

/** Цвета из макета «Итог: Б + В». */
object C {
    val BG = Color.parseColor("#0F1110")
    val SURFACE = Color.parseColor("#141715")
    val SURFACE2 = Color.parseColor("#181B19")
    val CARD = Color.parseColor("#1D211E")
    val CARD_DONE = Color.parseColor("#161A17")
    val BORDER = Color.parseColor("#232824")
    val BORDER2 = Color.parseColor("#2E332D")
    val TRACK = Color.parseColor("#262B27")
    val TEXT = Color.parseColor("#F2F4EF")
    val MUTED = Color.parseColor("#A3AAA0")
    val DIM = Color.parseColor("#6E756C")
    val ACCENT = Color.parseColor("#C8F031")
    val TODAY_BG = Color.parseColor("#1A1F12")
    val NEXT_BG = Color.parseColor("#252C1A")
    val HALF = Color.parseColor("#6F8A1F")
}

/** Шрифты Unbounded (заголовки, цифры) и Onest (текст). Если файлов нет — системный. */
object Fonts {
    private val cache = HashMap<String, Typeface>()

    private fun load(ctx: Context, file: String, weight: Int): Typeface {
        val key = "$file-$weight"
        cache[key]?.let { return it }
        val tf = try {
            Typeface.Builder(ctx.assets, "fonts/$file")
                .setFontVariationSettings("'wght' $weight")
                .setWeight(weight)
                .build()
        } catch (e: Exception) {
            null
        } ?: Typeface.create(Typeface.SANS_SERIF, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        cache[key] = tf
        return tf
    }

    fun display(ctx: Context, weight: Int = 700) = load(ctx, "unbounded.ttf", weight)
    fun body(ctx: Context, weight: Int = 400) = load(ctx, "onest.ttf", weight)
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun Context.rounded(fill: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Int = 1): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dpf(radiusDp.toFloat())
        if (stroke != null) setStroke(dp(strokeDp), stroke)
    }

/** Кольцо прогресса «3/6». */
class RingView(ctx: Context) : View(ctx) {
    var done = 0
        set(v) { field = v; invalidate() }
    var total = 0
        set(v) { field = v; invalidate() }

    private val stroke = ctx.dpf(11f)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; color = C.TRACK
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = stroke; color = C.ACCENT; strokeCap = Paint.Cap.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.TEXT; textAlign = Paint.Align.CENTER
        typeface = Fonts.display(ctx, 800)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22f, ctx.resources.displayMetrics)
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        val pad = stroke / 2 + 2
        rect.set((width - s) / 2 + pad, (height - s) / 2 + pad, (width + s) / 2 - pad, (height + s) / 2 - pad)
        canvas.drawArc(rect, 0f, 360f, false, track)
        if (total > 0 && done > 0) {
            canvas.drawArc(rect, -90f, 360f * done / total, false, arc)
        }
        val y = height / 2f - (label.descent() + label.ascent()) / 2
        canvas.drawText(if (total == 0) "–" else "$done/$total", width / 2f, y, label)
    }
}
