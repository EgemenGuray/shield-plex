package app.plexhdr.shield.services

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Plex trick-play thumbnails: a BIF file is a header, a table of (timestamp, offset) pairs and the JPEGs back to back. */
class Bif private constructor(private val data: ByteArray, private val times: LongArray, private val starts: IntArray, private val ends: IntArray) {
    private var cacheIndex = -1
    private var cacheImage: Bitmap? = null

    /** The thumbnail closest to (at or before) [timeMs]. */
    fun image(timeMs: Long): Bitmap? {
        var lo = 0
        var hi = times.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (times[mid] <= timeMs) lo = mid else hi = mid - 1
        }
        if (lo == cacheIndex) return cacheImage
        val img = BitmapFactory.decodeByteArray(data, starts[lo], ends[lo] - starts[lo]) ?: return null
        cacheIndex = lo
        cacheImage = img
        return img
    }

    companion object {
        private val magic = byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0D, 0x0A, 0x1A, 0x0A)

        fun parse(data: ByteArray): Bif? {
            if (data.size <= 64 || !data.copyOfRange(0, 8).contentEquals(magic)) return null
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            fun u32(at: Int) = b.getInt(at).toLong() and 0xffffffffL
            val count = u32(12).toInt()
            val mult = u32(16).let { if (it == 0L) 1000L else it }
            val t = ArrayList<Long>(); val s = ArrayList<Int>(); val e = ArrayList<Int>()
            for (n in 0 until count) {
                val at = 64 + n * 8
                if (at + 12 > data.size) break
                val time = u32(at)
                if (time == 0xffffffffL) break
                val start = u32(at + 4).toInt()
                val end = u32(at + 12).toInt()
                if (start >= end || end > data.size) continue
                t += time * mult; s += start; e += end
            }
            if (t.isEmpty()) return null
            return Bif(data, t.toLongArray(), s.toIntArray(), e.toIntArray())
        }
    }
}
