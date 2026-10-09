package com.duanju.tv.data.remote

import java.security.MessageDigest
import java.time.Instant

/**
 * 红果 X-Gorgon 签名算法（JVM 移植版）
 *
 * 对应 Go: /tmp/opencode/guoapp/native/core/provider_hongguo_sign.go (signHongguoRequest)
 *
 * 算法流程（标注 Go 行号）：
 * 1. timestamp = 当前 Unix 秒 (uint32)                                    [Go:24]
 * 2. queryHash = MD5(query_string) 取前4字节                              [Go:25]
 * 3. bodyHash = MD5(body) 取前4字节 (body非空时)                          [Go:28-31]
 * 4. payload[20] 字节数组：
 *    - [0:4]   = queryHash[:4]                                            [Go:27]
 *    - [4:8]   = bodyHash[:4] (body为空则全0)                             [Go:30]
 *    - [12:16] = 固定魔数 {0, 6, 11, 28}                                  [Go:33]
 *    - [16:20] = timestamp (BigEndian uint32)                             [Go:34]
 * 5. 与 20字节固定 key 逐字节 XOR                                         [Go:35-38]
 * 6. 对每字节：RotateLeft8(4) ^ next_byte -> Reverse8 -> ^ 0xff ^ 20     [Go:39-42]
 * 7. signature = {0x84, 0x04, 0x40, 0x1c, 0, 0} + payload[20] = 26字节   [Go:43]
 * 8. X-Gorgon = signature hex编码 (52字符)                                [Go:45]
 * 9. X-Khronos = timestamp 十进制字符串                                   [Go:44]
 * 10. X-SS-Req-Ticket = 当前毫秒时间戳字符串                              [Go:46]
 */
object HongguoSign {

    // 固定 20 字节 key (对应 Go 签名 key)
    private val SIGN_KEY = byteArrayOf(
        0x44.toByte(), 0xb9.toByte(), 0xb9.toByte(), 0xd9.toByte(),
        0xa4.toByte(), 0xae.toByte(), 0xf9.toByte(), 0xfc.toByte(),
        0xa4.toByte(), 0x93.toByte(), 0xaa.toByte(), 0x75.toByte(),
        0x7c.toByte(), 0xa3.toByte(), 0xc2.toByte(), 0xc4.toByte(),
        0xa4.toByte(), 0x96.toByte(), 0x93.toByte(), 0x8f.toByte(),
    )

    // 固定魔数
    private val MAGIC_BYTES = byteArrayOf(0, 6, 11, 28)

    // 签名前缀
    private val SIG_PREFIX = byteArrayOf(
        0x84.toByte(), 0x04.toByte(), 0x40.toByte(), 0x1c.toByte(), 0, 0,
    )

    /**
     * 计算签名头
     *
     * @param queryString URL 查询字符串 (不含 ?)，如 "aid=8662&version_code=73532..."
     * @param bodyBytes 请求体字节数组，GET 请求传空数组
     * @param timestampSec 可选时间戳(秒)，测试用；生产传 null 使用当前时间
     * @return Triple<X-Gorgon, X-Khronos, X-SS-Req-Ticket>
     */
    fun sign(queryString: String, bodyBytes: ByteArray, timestampSec: Long? = null): Triple<String, String, String> {
        val md5 = MessageDigest.getInstance("MD5")
        val ts = timestampSec?.toInt() ?: Instant.now().epochSecond.toInt()
        val queryHash = md5.digest(queryString.toByteArray())
        val bodyHash = if (bodyBytes.isNotEmpty()) md5.digest(bodyBytes) else ByteArray(16)

        // payload[20] - 对应 Go:26-34
        val payload = ByteArray(20)
        System.arraycopy(queryHash, 0, payload, 0, 4)          // Go:27 queryHash[:4]
        System.arraycopy(bodyHash, 0, payload, 4, 4)           // Go:30 bodyHash[:4]
        System.arraycopy(MAGIC_BYTES, 0, payload, 12, 4)       // Go:33 魔数 {0,6,11,28}
        // timestamp BigEndian at [16:20] - Go:34
        payload[16] = (ts shr 24).toByte()
        payload[17] = (ts shr 16).toByte()
        payload[18] = (ts shr 8).toByte()
        payload[19] = ts.toByte()

        // XOR with key - Go:35-38
        for (i in payload.indices) {
            payload[i] = (payload[i].toInt() xor SIGN_KEY[i].toInt()).toByte()
        }

        // RotateLeft8(4) ^ next -> Reverse8 -> ^ 0xff ^ 20 - Go:39-42
        for (i in payload.indices) {
            val next = payload[(i + 1) % payload.size]
            val rotated = rotateLeft8(payload[i], 4)
            val mixed = (rotated.toInt() xor next.toInt()).toByte()
            val reversed = reverse8(mixed)
            payload[i] = (reversed.toInt() xor 0xff xor payload.size).toByte()
        }

        // Build signature: prefix + payload - Go:43
        val signature = ByteArray(SIG_PREFIX.size + payload.size)
        System.arraycopy(SIG_PREFIX, 0, signature, 0, SIG_PREFIX.size)
        System.arraycopy(payload, 0, signature, SIG_PREFIX.size, payload.size)

        val xGorgon = bytesToHex(signature)                      // Go:45
        val xKhronos = (ts.toLong() and 0xffffffffL).toString()  // Go:44
        val xReqTicket = Instant.now().toEpochMilli().toString() // Go:46

        return Triple(xGorgon, xKhronos, xReqTicket)
    }

    /** 计算 body 的 X-SS-STUB (MD5 大写 hex，与 Go fmt.Sprintf("%X") 一致) */
    fun stub(bodyBytes: ByteArray): String =
        bytesToHex(MessageDigest.getInstance("MD5").digest(bodyBytes)).uppercase()

    /**
     * 生成红果 device_id（对应 Go provider_hongguo_sign.go:15-21 newHongguoDeviceID）
     * 返回 19 位数字字符串，范围 [1000000000000000000, 9000000000000000000)
     */
    fun newDeviceId(): String {
        val random = ByteArray(8)
        try {
            java.security.SecureRandom().nextBytes(random)
        } catch (_: Exception) {
            // 兜底：时间戳纳秒
            return System.nanoTime().toString()
        }
        val uint64 = java.nio.ByteBuffer.wrap(random).order(java.nio.ByteOrder.BIG_ENDIAN).long
        val base = 1_000_000_000_000_000_000L
        val range = 8_000_000_000_000_000_000L
        // 使用无符号运算避免溢出
        val result = (base.toULong() + (uint64.toULong() % range.toULong())).toString()
        return result
    }

    // ===== 工具函数 =====

    private fun rotateLeft8(b: Byte, n: Int): Byte {
        val v = b.toInt() and 0xff
        return ((v shl n) or (v ushr (8 - n))).toByte()
    }

    private fun reverse8(b: Byte): Byte {
        var v = b.toInt() and 0xff
        v = ((v and 0xaa) ushr 1) or ((v and 0x55) shl 1)
        v = ((v and 0xcc) ushr 2) or ((v and 0x33) shl 2)
        v = ((v and 0xf0) ushr 4) or ((v and 0x0f) shl 4)
        return v.toByte()
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(hexChar((v ushr 4) and 0x0f))
            sb.append(hexChar(v and 0x0f))
        }
        return sb.toString()
    }

    private fun hexChar(n: Int): Char = "0123456789abcdef"[n]
}
