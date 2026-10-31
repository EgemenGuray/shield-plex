package app.plexhdr.shield.plex

import org.json.JSONArray
import org.json.JSONObject

/**
 * Plex sends numbers as numbers or strings and flags as bools or 0/1 depending on the server version and field, so
 * the models wrap the raw JSON object and read it leniently instead of using strict typed parsing.
 */
open class J(val o: JSONObject) {
    fun str(key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        val v = o.opt(key)
        val s = when (v) {
            is String -> v
            is Number -> v.toString()
            is Boolean -> v.toString()
            else -> null
        }
        return if (s.isNullOrEmpty()) null else s
    }

    fun int(key: String): Int? = when (val v = o.opt(key)) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: v.toDoubleOrNull()?.toInt()
        else -> null
    }

    fun long(key: String): Long? = when (val v = o.opt(key)) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: v.toDoubleOrNull()?.toLong()
        else -> null
    }

    fun dbl(key: String): Double? = when (val v = o.opt(key)) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    fun bool(key: String): Boolean = when (val v = o.opt(key)) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v == "1" || v.equals("true", ignoreCase = true)
        else -> false
    }

    fun list(key: String): List<JSONObject> = o.optJSONArray(key).objects()
    fun obj(key: String): JSONObject? = o.optJSONObject(key)
    fun tags(key: String): List<String> = list(key).mapNotNull { it.optString("tag").ifEmpty { null } }
}

fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) optJSONObject(i)?.let { out.add(it) }
    return out
}

fun JSONObject.strOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key).ifEmpty { null } else null
