package com.pkgmod.core

import java.io.ByteArrayOutputStream

/**
 * 小端序读写工具。AXML / ARSC / DEX 都是小端序。
 */
object Bytes {

    fun u8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xFF

    fun u16(b: ByteArray, off: Int): Int =
        u8(b, off) or (u8(b, off + 1) shl 8)

    fun i32(b: ByteArray, off: Int): Int =
        u8(b, off) or
            (u8(b, off + 1) shl 8) or
            (u8(b, off + 2) shl 16) or
            (u8(b, off + 3) shl 24)

    fun u32(b: ByteArray, off: Int): Long = i32(b, off).toLong() and 0xFFFFFFFFL

    fun putU8(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
    }

    fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    fun putI32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    fun putU32(b: ByteArray, off: Int, v: Long) = putI32(b, off, v.toInt())
}

fun ByteArrayOutputStream.putU32le(v: Long) {
    write((v and 0xFF).toInt())
    write(((v ushr 8) and 0xFF).toInt())
    write(((v ushr 16) and 0xFF).toInt())
    write(((v ushr 24) and 0xFF).toInt())
}
