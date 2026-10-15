package app.weekplan

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {

    private val shortDays = arrayOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    private val fullDays = arrayOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")
    private val inDays = arrayOf("в понедельник", "во вторник", "в среду", "в четверг", "в пятницу", "в субботу", "в воскресенье")
    private val ru = Locale("ru")

    /** 0 — экран недели, 1..7 — открыт день. */
    private var openDay = 0

    private lateinit var root: FrameLayout
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 60_000)
        }
    }
    private val savedScroll = HashMap<String, Pair<Int, Int>>()
    private var firstWeekRender = true

    /** Снимок данных на момент отрисовки. */
    private class Data(val tasks: List<Task>, val done: Set<Int>, val now: LocalDateTime, val today: Int, val weekStart: LocalDate) {
        fun day(d: Int) = tasks.filter { it.day == d }
        fun doneIn(list: List<Task>) = list.count { it.id in done }
    }

    // ---------- Жизненный цикл ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDay = savedInstanceState?.getInt("openDay", 0) ?: 0
        Notifs.ensureChannel(this)
        root = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        AlarmScheduler.scheduleAll(this)
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        firstWeekRender = true
        savedScroll.clear()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("openDay", openDay)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (openDay != 0) {
            openDay = 0
            render()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun data() = Data(
        Storage.tasks(this), Storage.done(this), LocalDateTime.now(),
        LocalDate.now().dayOfWeek.value, Storage.weekStart()
    )

    private val wide get() = resources.configuration.screenWidthDp >= 900

    private fun render() {
        rememberScroll(root)
        root.removeAllViews()
        val d = data()
        root.addView(if (openDay == 0) weekScreen(d) else dayScreen(d, openDay))
        restoreScroll(root)
    }

    private fun toggle(t: Task, value: Boolean) {
        Storage.setDone(this, t.id, value)
        if (value) getSystemService(NotificationManager::class.java)?.cancel(t.id)
        render()
    }

    // ---------- Экран недели ----------

    private fun weekScreen(d: Data): View {
        val page = vbox().apply {
            setPadding(dp(if (wide) 40 else 20), dp(28), dp(if (wide) 40 else 20), dp(24))
        }

        // Счёт за неделю
        val total = d.tasks.size
        val doneAll = d.doneIn(d.tasks)
        val score = hbox().apply { gravity = Gravity.BOTTOM }
        score.addView(TextView(this).apply {
            typeface = Fonts.display(this@MainActivity, 800)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (wide) 56f else 44f)
            includeFontPadding = false
            val sb = SpannableStringBuilder()
            sb.append("$doneAll", ForegroundColorSpan(C.ACCENT), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("/$total", ForegroundColorSpan(C.DIM), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = sb
        })
        val fmt = DateTimeFormatter.ofPattern("d MMMM", ru)
        score.addView(vbox().apply {
            setPadding(dp(18), 0, 0, dp(4))
            addView(label(if (total == 0) "план пока пуст" else "упражнений за неделю", 17f, C.TEXT, Fonts.body(context, 600)))
            addView(label("${d.weekStart.format(fmt)} – ${d.weekStart.plusDays(6).format(fmt)}", 14f, C.MUTED, Fonts.body(context, 400)))
        })

        val buttons = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        buttons.addView(iconButton(R.drawable.ic_more, "Ещё") { showMoreMenu(it) })
        buttons.addView(space(10))
        buttons.addView(iconButton(R.drawable.ic_reset, "Сбросить галочки") { confirmReset() })
        buttons.addView(space(10))
        buttons.addView(accentButton("Вставить план") { showImport() })

        val next = nextUp(d)?.let { nextChip(it.first, it.second) }

        if (wide) {
            val header = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
            header.addView(score)
            header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            if (next != null) {
                header.addView(next)
                header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
            header.addView(buttons)
            page.addView(header)
        } else {
            val header = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
            header.addView(score)
            header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            header.addView(buttons)
            page.addView(header)
            if (next != null) {
                page.addView(next, lp(match = true).apply { topMargin = dp(16) })
            }
        }

        batteryBanner(d)?.let { page.addView(it, lp(match = true).apply { topMargin = dp(16) }) }

        if (d.tasks.isEmpty()) {
            page.addView(emptyHint(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(20) })
            return page
        }

        // Колонки дней
        val gap = 12
        val sidePad = if (wide) 40 else 20
        val per = (resources.configuration.screenWidthDp - sidePad * 2 - gap * 6) / 7
        val colW = dp(maxOf(per, 150))

        val row = hbox()
        for (day in 1..7) {
            val col = dayColumn(d, day)
            row.addView(col, LinearLayout.LayoutParams(colW, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                if (day > 1) leftMargin = dp(gap)
            })
        }
        val hs = HorizontalScrollView(this).apply {
            tag = "week-h"
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        page.addView(hs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(20) })

        // На узком экране сразу показываем сегодняшний день
        if (firstWeekRender && per < 150) {
            hs.post { hs.scrollTo((d.today - 1) * (colW + dp(gap)) - dp(20), 0) }
        }
        firstWeekRender = false
        return page
    }

    private fun dayColumn(d: Data, day: Int): View {
        val items = d.day(day)
        val doneN = d.doneIn(items)
        val isToday = day == d.today
        val col = vbox().apply {
            background = rounded(if (isToday) C.TODAY_BG else C.SURFACE, 20, if (isToday) C.ACCENT else C.BORDER)
            setPadding(dp(10), dp(10), dp(10), dp(12))
        }

        // Шапка дня — кнопка «открыть»
        val head = hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setPadding(dp(6), 0, dp(2), 0)
            background = rounded(Color.TRANSPARENT, 12)
            isClickable = true
            contentDescription = "Открыть: ${fullDays[day - 1]}"
            setOnClickListener { openDay = day; render() }
        }
        head.addView(label(shortDays[day - 1], 18f, if (isToday) C.ACCENT else C.TEXT, Fonts.display(this, 700)))
        head.addView(label(if (items.isEmpty()) "" else "  $doneN/${items.size}", 14f, C.MUTED, Fonts.body(this, 600)))
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(icon(R.drawable.ic_chevron_right, if (isToday) C.ACCENT else C.TEXT, 18))
        col.addView(head)

        col.addView(progressBar(doneN, items.size), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply {
            setMargins(dp(6), dp(4), dp(6), dp(10))
        })

        if (items.isEmpty()) {
            col.addView(label("Отдых", 15f, C.DIM, Fonts.body(this, 500)).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            return col
        }

        val next = if (isToday) nextUp(d)?.first?.takeIf { it.day == day } else null
        val list = vbox()
        items.forEachIndexed { i, t ->
            val isDone = t.id in d.done
            val card = hbox().apply {
                gravity = Gravity.TOP
                setPadding(dp(8), dp(10), dp(8), dp(10))
                background = rounded(if (isDone) C.CARD_DONE else if (t == next) C.NEXT_BG else C.CARD, 12)
                isClickable = true
                contentDescription = "${t.time} ${t.text}" + if (isDone) ", выполнено" else ""
                setOnClickListener { toggle(t, !isDone) }
            }
            card.addView(checkMark(isDone, 20), LinearLayout.LayoutParams(dp(20), dp(20)).apply { topMargin = dp(1) })
            val texts = vbox().apply { setPadding(dp(8), 0, 0, 0) }
            texts.addView(label(t.time, 13f, if (isDone) C.DIM else C.ACCENT, Fonts.body(this, 700)))
            texts.addView(label(t.text, 14f, if (isDone) C.DIM else C.TEXT, Fonts.body(this, 500)).apply {
                setLineSpacing(0f, 1.15f)
                if (isDone) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            })
            card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(card, lp(match = true).apply { if (i > 0) topMargin = dp(8) })
        }
        val sv = ScrollView(this).apply {
            tag = "col-$day"
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        col.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    private fun nextChip(t: Task, whenText: String): View {
        val chip = hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(C.SURFACE2, 18, C.BORDER2)
            setPadding(dp(20), dp(10), dp(12), dp(10))
            isClickable = true
            contentDescription = "Дальше: ${t.time} ${t.text}, $whenText. Открыть день"
            setOnClickListener { openDay = t.day; render() }
        }
        val texts = vbox()
        texts.addView(label("ДАЛЬШЕ · $whenText".uppercase(ru), 12f, C.MUTED, Fonts.body(this, 600)))
        texts.addView(TextView(this).apply {
            typeface = Fonts.body(context, 600)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(C.TEXT)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            val sb = SpannableStringBuilder()
            sb.append(t.time, ForegroundColorSpan(C.ACCENT), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("  ${t.text}")
            text = sb
        })
        val textsLp = if (wide) LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        textsLp.rightMargin = dp(16)
        chip.addView(texts, textsLp)
        chip.addView(FrameLayout(this).apply {
            background = rounded(C.ACCENT, 12)
            addView(icon(R.drawable.ic_chevron_right, C.BG, 20), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        return chip
    }

    // ---------- Экран дня ----------

    private fun dayScreen(d: Data, day: Int): View {
        val items = d.day(day)
        val doneN = d.doneIn(items)
        val page = vbox().apply { setPadding(dp(if (wide) 40 else 20), dp(28), dp(if (wide) 40 else 20), dp(24)) }

        // Шапка: назад, название, переключатель дней
        val top = hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(iconButton(R.drawable.ic_back, "Назад к неделе") { openDay = 0; render() })
        val date = d.weekStart.plusDays((day - 1).toLong())
        val rel = when (day - d.today) { 0 -> " · сегодня"; -1 -> " · вчера"; 1 -> " · завтра"; else -> "" }
        val titles = vbox().apply { setPadding(dp(18), 0, 0, 0) }
        titles.addView(label(fullDays[day - 1], if (wide) 30f else 26f, if (day == d.today) C.ACCENT else C.TEXT, Fonts.display(this, 700)))
        titles.addView(label(date.format(DateTimeFormatter.ofPattern("d MMMM", ru)) + rel, 15f, C.MUTED, Fonts.body(this, 400)))
        top.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val strip = dayStrip(d, day)
        if (wide) top.addView(strip)
        page.addView(top)
        if (!wide) page.addView(strip, lp(match = true).apply { topMargin = dp(16) })

        // Левая часть: карточка «дальше» и кольцо
        val left = vbox()
        left.addView(dayNextCard(d, day, items), lp(match = true))
        left.addView(ringCard(d, doneN, items.size), lp(match = true).apply { topMargin = dp(20) })

        // Правая часть: таймлайн
        val timeline = vbox().apply {
            background = rounded(C.SURFACE, 28, C.BORDER)
            setPadding(dp(if (wide) 32 else 20), dp(16), dp(if (wide) 32 else 20), dp(16))
        }
        if (items.isEmpty()) {
            timeline.addView(label("В этот день ничего не запланировано", 18f, C.MUTED, Fonts.body(this, 500)).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(40), 0, dp(40))
            }, lp(match = true))
        }
        val nextT = dayFocus(d, day, items)?.first
        for (t in items) timeline.addView(timelineRow(t, t.id in d.done, t == nextT))

        if (wide) {
            val body = hbox()
            body.addView(left, LinearLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT))
            val sv = ScrollView(this).apply {
                tag = "day-$day"
                isVerticalScrollBarEnabled = false
                addView(timeline)
            }
            body.addView(sv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(24) })
            page.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(24) })
        } else {
            val inner = vbox()
            inner.addView(left, lp(match = true))
            inner.addView(timeline, lp(match = true).apply { topMargin = dp(20) })
            val sv = ScrollView(this).apply {
                tag = "day-$day"
                isVerticalScrollBarEnabled = false
                addView(inner)
            }
            page.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(20) })
        }
        return page
    }

    private fun dayStrip(d: Data, selected: Int): View {
        val strip = hbox()
        for (day in 1..7) {
            val items = d.day(day)
            val n = d.doneIn(items)
            val sel = day == selected
            val pill = vbox().apply {
                gravity = Gravity.CENTER
                background = rounded(if (sel) C.TODAY_BG else C.CARD_DONE, 14, if (sel) C.ACCENT else C.BORDER)
                isClickable = true
                contentDescription = fullDays[day - 1]
                setOnClickListener { openDay = day; render() }
            }
            pill.addView(label(shortDays[day - 1], 15f, if (sel) C.ACCENT else C.TEXT, Fonts.body(this, if (day == d.today) 800 else 700)))
            val barColor = when {
                items.isEmpty() -> Color.TRANSPARENT
                n == items.size -> C.ACCENT
                n > 0 -> C.HALF
                else -> C.BORDER2
            }
            pill.addView(View(this).apply { background = rounded(barColor, 2) },
                LinearLayout.LayoutParams(dp(28), dp(4)).apply { topMargin = dp(6) })
            val p = if (wide) LinearLayout.LayoutParams(dp(64), dp(56)) else LinearLayout.LayoutParams(0, dp(56), 1f)
            if (day > 1) p.leftMargin = dp(8)
            strip.addView(pill, p)
        }
        return strip
    }

    /** Что показать в большой карточке дня: задача и подпись над временем. */
    private fun dayFocus(d: Data, day: Int, items: List<Task>): Pair<Task, String>? {
        val undone = items.filter { it.id !in d.done }
        if (undone.isEmpty()) return null
        val nowMin = d.now.hour * 60 + d.now.minute
        return when {
            day == d.today -> {
                val upcoming = undone.firstOrNull { it.hour * 60 + it.minute >= nowMin }
                if (upcoming != null) upcoming to "Дальше · " + inMinutes(upcoming.hour * 60 + upcoming.minute - nowMin)
                else undone.first() to "Осталось отметить"
            }
            day > d.today -> undone.first() to "Первое в этот день"
            else -> undone.first() to "Осталось отметить"
        }
    }

    private fun dayNextCard(d: Data, day: Int, items: List<Task>): View {
        val card = vbox().apply {
            background = rounded(C.ACCENT, 28)
            setPadding(dp(28), dp(26), dp(28), dp(26))
        }
        val focus = dayFocus(d, day, items)
        if (focus == null) {
            card.addView(label(if (items.isEmpty()) "ОТДЫХ" else "ДЕНЬ ЗАКРЫТ", 14f, C.BG, Fonts.body(this, 700)).apply { letterSpacing = 0.03f })
            card.addView(label(if (items.isEmpty()) "Восстановление" else "Всё сделано", if (wide) 40f else 34f, C.BG, Fonts.display(this, 800)).apply {
                setPadding(0, dp(12), 0, 0)
            })
            return card
        }
        val (t, caption) = focus
        card.addView(label(caption.uppercase(ru), 14f, C.BG, Fonts.body(this, 700)).apply { letterSpacing = 0.03f })
        card.addView(label(t.time, if (wide) 58f else 48f, C.BG, Fonts.display(this, 800)).apply {
            includeFontPadding = false
            setPadding(0, dp(12), 0, dp(10))
        })
        card.addView(label(t.text, 26f, C.BG, Fonts.body(this, 600)).apply { setLineSpacing(0f, 1.15f) })
        val done = hbox().apply {
            gravity = Gravity.CENTER
            background = rounded(C.BG, 18)
            isClickable = true
            contentDescription = "Готово: ${t.text}"
            setOnClickListener { toggle(t, true) }
        }
        done.addView(icon(R.drawable.ic_check, C.ACCENT, 22))
        done.addView(label("  Готово", 16f, C.ACCENT, Fonts.display(this, 700)))
        card.addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)).apply { topMargin = dp(18) })
        return card
    }

    private fun ringCard(d: Data, done: Int, total: Int): View {
        val card = hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(C.CARD_DONE, 28, C.BORDER)
            setPadding(dp(22), dp(20), dp(22), dp(20))
        }
        card.addView(RingView(this).apply { this.done = done; this.total = total }, LinearLayout.LayoutParams(dp(104), dp(104)))
        val caption = when {
            total == 0 -> "День отдыха"
            done == total -> "Всё сделано!"
            done == 0 -> "Поехали"
            done * 2 == total -> "Половина дня позади"
            done * 2 > total -> "Больше половины позади"
            else -> "Хорошее начало"
        }
        val texts = vbox().apply { setPadding(dp(22), 0, 0, 0) }
        texts.addView(label(caption, 20f, C.TEXT, Fonts.body(this, 700)))
        texts.addView(label("За неделю: ${d.doneIn(d.tasks)} из ${d.tasks.size}", 16f, C.MUTED, Fonts.body(this, 400)).apply {
            setPadding(0, dp(6), 0, 0)
        })
        card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return card
    }

    private fun timelineRow(t: Task, isDone: Boolean, isNext: Boolean): View {
        val row = hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(80)
        }
        row.addView(label(t.time, 19f, when { isNext -> C.ACCENT; isDone -> C.DIM; else -> C.TEXT }, Fonts.body(this, 700)),
            LinearLayout.LayoutParams(dp(if (wide) 64 else 56), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(View(this).apply { setBackgroundColor(if (isDone) C.ACCENT else C.TRACK) },
            LinearLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT).apply { rightMargin = dp(if (wide) 20 else 14) })
        val card = hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = rounded(
                when { isNext -> C.NEXT_BG; isDone -> Color.parseColor("#121513"); else -> C.CARD }, 18,
                if (isNext) C.ACCENT else null
            )
            isClickable = true
            contentDescription = "${t.time} ${t.text}" + if (isDone) ", выполнено" else ""
            setOnClickListener { toggle(t, !isDone) }
        }
        card.addView(checkMark(isDone, 26), LinearLayout.LayoutParams(dp(26), dp(26)))
        card.addView(label(t.text, 19f, if (isDone) C.DIM else C.TEXT, Fonts.body(this, if (isNext) 700 else 500)).apply {
            setPadding(dp(16), 0, 0, 0)
            if (isDone) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            topMargin = dp(6); bottomMargin = dp(6)
        })
        return row
    }

    // ---------- «Что дальше» ----------

    /** Ближайшее невыполненное упражнение с этого момента до конца недели. */
    private fun nextUp(d: Data): Pair<Task, String>? {
        val nowMin = d.now.hour * 60 + d.now.minute
        val t = d.tasks
            .filter { it.id !in d.done }
            .filter { it.day > d.today || (it.day == d.today && it.hour * 60 + it.minute >= nowMin) }
            .minWithOrNull(compareBy({ it.day }, { it.hour * 60 + it.minute }))
            ?: return null
        val text = when (t.day - d.today) {
            0 -> inMinutes(t.hour * 60 + t.minute - nowMin)
            1 -> "завтра"
            else -> inDays[t.day - 1]
        }
        return t to text
    }

    private fun inMinutes(m: Int): String = when {
        m <= 0 -> "сейчас"
        m < 60 -> "через $m мин"
        m % 60 == 0 -> "через ${m / 60} ч"
        else -> "через ${m / 60} ч ${m % 60} мин"
    }

    // ---------- Вставка плана ----------

    private fun showImport() {
        val edit = EditText(this).apply {
            setText(Storage.rawPlan(this@MainActivity))
            hint = "Понедельник\n10:00 10 отжиманий\n10:30 30 пресс\n\nВторник\n..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 10
            maxLines = 18
            gravity = Gravity.TOP or Gravity.START
            typeface = Fonts.body(context, 400)
            setTextColor(C.TEXT)
            setHintTextColor(C.DIM)
            backgroundTintList = ColorStateList.valueOf(C.ACCENT)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(edit)
        }

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("План на неделю")
            .setView(box)
            .setPositiveButton("Сохранить", null)
            .setNeutralButton("Из буфера", null)
            .setNegativeButton("Отмена", null)
            .create()
        dialog.show()
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE)
            .forEach { dialog.getButton(it)?.setTextColor(C.ACCENT) }

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            val clip = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            if (clip.isNullOrBlank()) toast("Буфер обмена пуст") else edit.setText(clip)
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val raw = edit.text.toString()
            val result = PlanParser.parse(raw)
            when {
                result.tasks.isEmpty() -> toast("Не нашёл ни одного упражнения. Нужен день недели, а под ним строки «10:00 упражнение».")
                result.badLines.isNotEmpty() -> {
                    val shown = result.badLines.take(8).joinToString("\n")
                    AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("Не все строки понятны")
                        .setMessage("Пропущу эти строки:\n\n$shown${if (result.badLines.size > 8) "\n…" else ""}\n\nСохранить остальное (${result.tasks.size} шт.)?")
                        .setPositiveButton("Сохранить") { _, _ -> applyPlan(result, raw); dialog.dismiss() }
                        .setNegativeButton("Исправить", null)
                        .show()
                }
                else -> { applyPlan(result, raw); dialog.dismiss() }
            }
        }
    }

    private fun applyPlan(result: PlanParser.Result, raw: String) {
        AlarmScheduler.cancel(this, Storage.tasks(this))
        Storage.saveTasks(this, result.tasks, raw)
        AlarmScheduler.scheduleAll(this)
        openDay = 0
        firstWeekRender = true
        render()
        toast("Сохранено упражнений: ${result.tasks.size}")
    }

    private fun confirmReset() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setMessage("Снять все галочки за эту неделю?")
            .setPositiveButton("Снять") { _, _ -> Storage.resetDone(this); render() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showMoreMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Проверить уведомление")
            menu.add(0, 2, 1, "Формат плана")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> testNotification()
                    2 -> showFormatHelp()
                }
                true
            }
            show()
        }
    }

    private fun testNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            toast("Уведомления запрещены. Разрешите их в настройках приложения.")
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } else {
            Notifs.show(this, 999_999, "Проверка", "Уведомления работают", null)
        }
    }

    private fun showFormatHelp() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Формат плана")
            .setMessage(
                "День недели на отдельной строке, под ним упражнения со временем:\n\n" +
                    "Понедельник\n10:00 10 отжиманий\n10:30 30 пресс\n\n" +
                    "Вторник\n9:00 Бег 3 км\n\n" +
                    "Можно писать коротко: Пн, Вт, Ср…\n" +
                    "«Каждый день» — упражнения повторятся во все дни.\n\n" +
                    "Новый план заменяет старый, галочки сбрасываются. " +
                    "В понедельник галочки обнуляются сами."
            )
            .setPositiveButton("Понятно", null)
            .show()
    }

    // ---------- Батарея ----------

    private fun batteryBanner(d: Data): View? {
        val pm = getSystemService(PowerManager::class.java) ?: return null
        if (d.tasks.isEmpty() || pm.isIgnoringBatteryOptimizations(packageName)) return null
        val b = hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(C.SURFACE2, 14, C.HALF)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            setOnClickListener { askBatteryWhitelist() }
        }
        b.addView(label("Чтобы напоминания приходили вовремя, разрешите приложению работать в фоне", 15f, C.TEXT, Fonts.body(this, 500)),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        b.addView(label("Разрешить", 15f, C.ACCENT, Fonts.body(this, 700)).apply { setPadding(dp(16), 0, 0, 0) })
        return b
    }

    private fun askBatteryWhitelist() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    // ---------- Мелкие помощники ----------

    private fun emptyHint(): View = vbox().apply {
        gravity = Gravity.CENTER
        background = rounded(C.SURFACE, 28, C.BORDER)
        setPadding(dp(32), dp(32), dp(32), dp(32))
        addView(label("План пока пуст", 26f, C.TEXT, Fonts.display(context, 700)).apply { gravity = Gravity.CENTER })
        addView(label("Нажмите «Вставить план» и вставьте текст вида:", 17f, C.MUTED, Fonts.body(context, 400)).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14))
        })
        addView(label("Понедельник\n10:00 10 отжиманий\n10:30 30 пресс", 18f, C.ACCENT, Fonts.body(context, 600)).apply {
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.3f)
        })
    }

    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun hbox() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun space(w: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(w), 1) }
    private fun lp(match: Boolean = false) = LinearLayout.LayoutParams(
        if (match) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun label(s: String, sp: Float, color: Int, tf: android.graphics.Typeface) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = tf
    }

    private fun icon(res: Int, color: Int, sizeDp: Int) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun iconButton(res: Int, desc: String, onClick: (View) -> Unit) = FrameLayout(this).apply {
        background = rounded(C.SURFACE2, 16, C.BORDER2)
        contentDescription = desc
        isClickable = true
        setOnClickListener { onClick(it) }
        addView(icon(res, C.TEXT, 22), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
    }

    private fun accentButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        typeface = Fonts.display(context, 700)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(C.BG)
        gravity = Gravity.CENTER
        background = rounded(C.ACCENT, 16)
        setPadding(dp(22), 0, dp(22), 0)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56))
    }

    private fun checkMark(done: Boolean, sizeDp: Int): View = FrameLayout(this).apply {
        background = if (done) rounded(C.ACCENT, 6) else rounded(Color.TRANSPARENT, 6, C.MUTED, 2)
        if (done) addView(icon(R.drawable.ic_check, C.BG, sizeDp - 6),
            FrameLayout.LayoutParams(dp(sizeDp - 6), dp(sizeDp - 6), Gravity.CENTER))
    }

    private fun progressBar(done: Int, total: Int): View = LinearLayout(this).apply {
        background = rounded(C.TRACK, 2)
        if (total > 0 && done > 0) {
            addView(View(context).apply { background = rounded(C.ACCENT, 2) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, done.toFloat()))
        }
        if (total - done > 0 || total == 0) {
            addView(View(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, maxOf(total - done, 1).toFloat()))
        }
    }

    private fun rememberScroll(v: View) {
        if (v.tag is String && (v is ScrollView || v is HorizontalScrollView)) {
            savedScroll[v.tag as String] = v.scrollX to v.scrollY
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) rememberScroll(v.getChildAt(i))
    }

    private fun restoreScroll(v: View) {
        val key = v.tag as? String
        if (key != null && (v is ScrollView || v is HorizontalScrollView)) {
            savedScroll[key]?.let { (x, y) -> v.post { v.scrollTo(x, y) } }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) restoreScroll(v.getChildAt(i))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
