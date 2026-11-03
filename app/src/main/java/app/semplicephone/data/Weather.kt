package app.semplicephone.data

import android.content.Context
import android.util.Log
import app.semplicephone.R
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Meteo da Open-Meteo (servizio libero, senza chiave né registrazione). */
object Weather {
    private const val TAG = "Weather"
    private const val MAX_AGE_MS = 30 * 60_000L

    data class Now(val temp: Int, val min: Int, val max: Int, val code: Int, val at: Long)
    data class Place(val name: String, val area: String, val lat: Double, val lon: Double) {
        /** Valore salvato nel riquadro: target = "lat,lon". */
        val target: String get() = "%.4f,%.4f".format(Locale.ROOT, lat, lon)
    }

    fun parseTarget(target: String): Pair<Double, Double>? = runCatching {
        val (a, b) = target.split(',').map { it.trim().toDouble() }
        require(a in -90.0..90.0 && b in -180.0..180.0)
        a to b
    }.getOrNull()

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 10_000 }
        require(c.responseCode == 200) { "HTTP ${c.responseCode}" }
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** Previsione attuale, con cache di 30 minuti (anche senza rete resta l'ultima nota). */
    fun current(context: Context, target: String): Now? {
        val (lat, lon) = parseTarget(target) ?: return null
        val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)
        val cached = prefs.getString(target, null)?.let { parse(it) }
        if (cached != null && System.currentTimeMillis() - cached.at < MAX_AGE_MS) return cached
        return runCatching {
            val body = get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1",
            )
            val j = JSONObject(body)
            val cur = j.getJSONObject("current")
            val daily = j.getJSONObject("daily")
            val now = Now(
                temp = Math.round(cur.getDouble("temperature_2m")).toInt(),
                min = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)).toInt(),
                max = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
                code = cur.getInt("weather_code"),
                at = System.currentTimeMillis(),
            )
            prefs.edit().putString(target, "${now.temp};${now.min};${now.max};${now.code};${now.at}").apply()
            now
        }.onFailure { Log.w(TAG, "meteo non disponibile", it) }.getOrNull() ?: cached
    }

    private fun parse(s: String): Now? = runCatching {
        val p = s.split(';')
        Now(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toLong())
    }.getOrNull()

    /** Ricerca località per nome (nella lingua indicata). */
    fun search(name: String, lang: String): List<Place> = runCatching {
        val body = get(
            "https://geocoding-api.open-meteo.com/v1/search?count=6&format=json&language=$lang&name=" +
                URLEncoder.encode(name.trim(), "UTF-8"),
        )
        val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Place(
                o.getString("name"),
                listOf(o.optString("admin1"), o.optString("country")).filter { it.isNotBlank() }.joinToString(", "),
                o.getDouble("latitude"), o.getDouble("longitude"),
            )
        }
    }.onFailure { Log.w(TAG, "ricerca località non riuscita", it) }.getOrDefault(emptyList())

    /** Codice meteo WMO → simbolo e descrizione. */
    fun symbol(code: Int): String = when (code) {
        0, 1 -> "weather"
        2, 3 -> "cloud"
        45, 48 -> "fog"
        in 51..67, in 80..82 -> "rain"
        in 71..77, 85, 86 -> "snow"
        in 95..99 -> "storm"
        else -> "cloud"
    }

    fun description(code: Int): Int = when (symbol(code)) {
        "weather" -> R.string.weather_clear
        "fog" -> R.string.weather_fog
        "rain" -> R.string.weather_rain
        "snow" -> R.string.weather_snow
        "storm" -> R.string.weather_storm
        else -> R.string.weather_cloudy
    }
}
