package app.weekplan

/**
 * Разбирает план из текста вида:
 *
 *   Понедельник
 *   10:00 10 отжиманий
 *   10:30 30 пресс
 *
 * Понимает полные и короткие названия дней (Пн, Вт...), "Каждый день" / "Ежедневно",
 * время через двоеточие или точку (9:00, 09.00), а также лишние символы
 * из чата: **жирный**, ## заголовки, маркеры списков, ``` блоки.
 *
 * Регулярные выражения тут намеренно не используются: движок регулярок на Android
 * отличается от обычной Java и падал на символах тире.
 */
object PlanParser {

    data class Result(
        val tasks: List<Task>,
        val badLines: List<String>,
    )

    private val dayNames: Map<String, Int> = mapOf(
        "понедельник" to 1, "пн" to 1, "пон" to 1,
        "вторник" to 2, "вт" to 2,
        "среда" to 3, "ср" to 3, "среду" to 3,
        "четверг" to 4, "чт" to 4,
        "пятница" to 5, "пт" to 5, "пятницу" to 5,
        "суббота" to 6, "сб" to 6, "субботу" to 6,
        "воскресенье" to 7, "вс" to 7, "воскр" to 7,
    )

    private val everyDayPrefixes = listOf("каждый день", "ежедневно", "все дни")

    private const val DASHES = "-–—−"
    private const val DECOR = "*#`_>•·  \t" + DASHES

    fun parse(text: String): Result {
        val tasks = mutableListOf<Task>()
        val bad = mutableListOf<String>()
        var currentDays: List<Int>? = null
        var nextId = 1

        for (raw in text.lines()) {
            val line = raw.trim { it.isWhitespace() || it in DECOR }
            if (line.isEmpty()) continue

            val days = dayFor(line)
            if (days != null) {
                currentDays = days
                continue
            }

            val parsed = parseTaskLine(line)
            if (parsed != null && currentDays != null) {
                val (h, m, what) = parsed
                for (d in currentDays) {
                    tasks += Task(nextId++, d, h, m, what)
                }
                continue
            }
            bad += raw.trim()
        }

        val sorted = tasks.sortedWith(compareBy({ it.day }, { it.hour }, { it.minute }))
        return Result(sorted, bad)
    }

    /** «10:00 текст», «9.30 - текст», «10:00 — текст» → часы, минуты, текст. */
    internal fun parseTaskLine(line: String): Triple<Int, Int, String>? {
        var i = 0
        while (i < line.length && i < 2 && line[i].isAsciiDigit()) i++
        if (i == 0 || i >= line.length) return null
        val h = line.substring(0, i).toInt()
        if (line[i] != ':' && line[i] != '.') return null
        i++
        if (i + 2 > line.length || !line[i].isAsciiDigit() || !line[i + 1].isAsciiDigit()) return null
        val m = line.substring(i, i + 2).toInt()
        i += 2
        if (i < line.length && line[i].isAsciiDigit()) return null
        if (h !in 0..23 || m !in 0..59) return null
        val what = line.substring(i).trim { it.isWhitespace() || it in DECOR || it == ':' }
        if (what.isEmpty()) return null
        return Triple(h, m, what)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    /** Возвращает дни недели (1 = Пн … 7 = Вс), если строка — заголовок дня. */
    private fun dayFor(line: String): List<Int>? {
        val lower = line.lowercase()
        if (everyDayPrefixes.any { lower.startsWith(it) }) return (1..7).toList()
        if (lower.first().isDigit()) return null
        val first = lower.takeWhile { it.isLetter() }
        return dayNames[first]?.let { listOf(it) }
    }
}
