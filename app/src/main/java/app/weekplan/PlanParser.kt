package app.weekplan

/**
 * Разбирает план из текста вида:
 *
 *   Понедельник
 *   10:00 10 отжиманий
 *   10:30 30 пресс
 *
 *   Вторник
 *   ...
 *
 * Понимает полные и короткие названия дней (Пн, Вт...), "Каждый день" / "Ежедневно",
 * время через двоеточие или точку (9:00, 09.00), а также лишние символы
 * из чата: **жирный**, ## заголовки, маркеры списков, ``` блоки.
 */
object PlanParser {

    data class Result(
        val tasks: List<Task>,
        val badLines: List<String>,
    )

    private val dayNames: Map<String, Int> = mapOf(
        "понедельник" to 1, "пн" to 1, "пон" to 1,
        "вторник" to 2, "вт" to 2,
        "среда" to 3, "ср" to 3,
        "четверг" to 4, "чт" to 4,
        "пятница" to 5, "пт" to 5,
        "суббота" to 6, "сб" to 6,
        "воскресенье" to 7, "вс" to 7, "воскр" to 7,
    )

    private val everyDayPrefixes = listOf("каждый день", "ежедневно", "все дни")

    // 10:00 текст | 9.30 - текст | 10:00 — текст
    private val taskRegex = Regex("""^(\d{1,2})[:.](\d{2})\s*[-–—:]?\s*(.+)$""")

    private val decorChars = "*#`_>•·-–— \t"

    fun parse(text: String): Result {
        val tasks = mutableListOf<Task>()
        val bad = mutableListOf<String>()
        var currentDays: List<Int>? = null
        var nextId = 1

        for (raw in text.lines()) {
            val line = raw.trim().trim { it in decorChars }.trim()
            if (line.isEmpty()) continue

            val days = dayFor(line)
            if (days != null) {
                currentDays = days
                continue
            }

            val m = taskRegex.find(line)
            if (m != null && currentDays != null) {
                val h = m.groupValues[1].toInt()
                val min = m.groupValues[2].toInt()
                val what = m.groupValues[3].trim().trim { it in decorChars }.trim()
                if (h in 0..23 && min in 0..59 && what.isNotEmpty()) {
                    for (d in currentDays) {
                        tasks += Task(nextId++, d, h, min, what)
                    }
                    continue
                }
            }
            bad += raw.trim()
        }

        val sorted = tasks.sortedWith(compareBy({ it.day }, { it.hour }, { it.minute }))
        return Result(sorted, bad)
    }

    /** Возвращает дни недели (1 = Пн … 7 = Вс), если строка — заголовок дня. */
    private fun dayFor(line: String): List<Int>? {
        val lower = line.lowercase()
        if (everyDayPrefixes.any { lower.startsWith(it) }) return (1..7).toList()
        // Заголовок не должен начинаться со времени
        if (lower.first().isDigit()) return null
        val first = lower.split(Regex("""[\s:.,;()\-–—]+""")).firstOrNull() ?: return null
        val day = dayNames[first] ?: return null
        return listOf(day)
    }
}
