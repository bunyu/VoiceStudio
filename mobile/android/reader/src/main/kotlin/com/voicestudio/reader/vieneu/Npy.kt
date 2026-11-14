package com.voicestudio.reader.vieneu

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** A numeric array from a NumPy file, widened or narrowed to 32-bit floats. */
class NpyArray(val shape: IntArray, val data: FloatArray)

/** Reads the little-endian float arrays of an uncompressed or deflated `.npz` archive (NumPy format 1.0 to 3.0). */
object Npy {
    private const val LIMIT = 64L * 1024 * 1024

    fun readArchive(file: File): Map<String, NpyArray> = ZipFile(file).use { zip ->
        zip.entries().asSequence().filter { it.name.endsWith(".npy") && it.size in 1..LIMIT }.associate { entry ->
            entry.name.removeSuffix(".npy") to parse(zip.getInputStream(entry).use { it.readBytes() })
        }
    }

    fun parse(bytes: ByteArray): NpyArray {
        require(bytes.size >= 12 && bytes[0] == 0x93.toByte() && String(bytes, 1, 5, Charsets.US_ASCII) == "NUMPY") { "not a NumPy file" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val major = bytes[6].toInt()
        val headerLength = if (major == 1) buffer.getShort(8).toInt() and 0xffff else buffer.getInt(8)
        val start = if (major == 1) 10 else 12
        require(headerLength >= 0 && start + headerLength <= bytes.size)
        val header = String(bytes, start, headerLength, Charsets.ISO_8859_1)
        val type = Regex("""'descr'\s*:\s*'([^']+)'""").find(header)?.groupValues?.get(1) ?: error("NumPy header has no type")
        require("'fortran_order': False" in header.replace(Regex("""\s+"""), " ")) { "column-major arrays are not supported" }
        val shape = (Regex("""'shape'\s*:\s*\(([^)]*)\)""").find(header)?.groupValues?.get(1) ?: error("NumPy header has no shape"))
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() }.toIntArray()
        val count = shape.fold(1L) { total, n -> require(n >= 0); total * n }
        require(count <= LIMIT / 4)
        buffer.position(start + headerLength)
        val width = when (type) { "<f4" -> 4; "<f8" -> 8; "<f2" -> 2; else -> error("unsupported NumPy type $type") }
        require(buffer.remaining() >= count * width)
        val data = FloatArray(count.toInt()) {
            when (width) { 4 -> buffer.getFloat(); 8 -> buffer.getDouble().toFloat(); else -> halfToFloat(buffer.getShort()) }
        }
        return NpyArray(shape, data)
    }

    private fun halfToFloat(half: Short): Float {
        val bits = half.toInt() and 0xffff
        val sign = if (bits and 0x8000 != 0) -1f else 1f
        val exponent = (bits shr 10) and 0x1f
        val fraction = bits and 0x3ff
        return sign * when (exponent) {
            0 -> fraction * Math.scalb(1f, -24)
            31 -> if (fraction == 0) Float.POSITIVE_INFINITY else Float.NaN
            else -> (1f + fraction / 1024f) * Math.scalb(1f, exponent - 15)
        }
    }
}
