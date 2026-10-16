package app.weekplan

import android.app.Application
import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter

/** Запоминает текст последней ошибки, чтобы показать его при следующем запуске. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "?" }
                val info = "Версия $ver, Android ${android.os.Build.VERSION.RELEASE} " +
                    "(${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL})\n\n$sw"
                getSharedPreferences("crash", Context.MODE_PRIVATE).edit()
                    .putString("last", info.take(6000)).commit()
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
    }

    companion object {
        fun takeCrash(ctx: Context): String? {
            val p = ctx.getSharedPreferences("crash", Context.MODE_PRIVATE)
            val s = p.getString("last", null) ?: return null
            p.edit().remove("last").commit()
            return s
        }
    }
}
