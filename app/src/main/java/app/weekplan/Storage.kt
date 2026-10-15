package app.weekplan

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class Task(
    val id: Int,
    val day: Int,      // 1 = Пн … 7 = Вс
    val hour: Int,
    val minute: Int,
    val text: String,
) {
    val time: String get() = "%02d:%02d".format(hour, minute)
}

/** Хранит план и галочки. Галочки сами сбрасываются в начале новой недели. */
object Storage {

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("plan", Context.MODE_PRIVATE)

    fun weekStart(): LocalDate =
        LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun tasks(ctx: Context): List<Task> {
        val json = prefs(ctx).getString("tasks", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Task(o.getInt("id"), o.getInt("day"), o.getInt("h"), o.getInt("m"), o.getString("t"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun task(ctx: Context, id: Int): Task? = tasks(ctx).firstOrNull { it.id == id }

    fun rawPlan(ctx: Context): String = prefs(ctx).getString("raw", "") ?: ""

    fun saveTasks(ctx: Context, tasks: List<Task>, raw: String) {
        val arr = JSONArray()
        tasks.forEach {
            arr.put(JSONObject().put("id", it.id).put("day", it.day).put("h", it.hour).put("m", it.minute).put("t", it.text))
        }
        prefs(ctx).edit()
            .putString("tasks", arr.toString())
            .putString("raw", raw)
            .putString("done", "")
            .putString("week", weekStart().toString())
            .apply()
    }

    fun done(ctx: Context): Set<Int> {
        ensureCurrentWeek(ctx)
        val s = prefs(ctx).getString("done", "") ?: ""
        return s.split(",").mapNotNull { it.toIntOrNull() }.toSet()
    }

    fun setDone(ctx: Context, id: Int, value: Boolean) {
        val cur = done(ctx).toMutableSet()
        if (value) cur += id else cur -= id
        prefs(ctx).edit().putString("done", cur.joinToString(",")).commit()
    }

    fun resetDone(ctx: Context) {
        prefs(ctx).edit().putString("done", "").putString("week", weekStart().toString()).commit()
    }

    private fun ensureCurrentWeek(ctx: Context) {
        val wk = weekStart().toString()
        val p = prefs(ctx)
        if (p.getString("week", null) != wk) {
            p.edit().putString("week", wk).putString("done", "").commit()
        }
    }
}
