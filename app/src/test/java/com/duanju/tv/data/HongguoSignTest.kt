package com.duanju.tv.data

import com.duanju.tv.data.remote.HongguoSign
import org.junit.Assert.*
import org.junit.Test

/**
 * Golden tests for HongguoSign - verified against Go implementation:
 * /tmp/opencode/guoapp/native/core/provider_hongguo_sign.go (signHongguoRequest)
 *
 * Go test reference (run with: go run /tmp/test_sign.go):
 *   Empty body:
 *     X-Gorgon: 8404401c0000c29547edcc232d3d7022103fad22c9d970977eb7
 *     X-Khronos: 1700000000
 *   Non-empty body:
 *     X-Gorgon: 8404401c0000c295471987e0a7167022103fad22c9d970977eb7
 *     X-Khronos: 1700000000
 */
class HongguoSignTest {

    private val FIXED_TS = 1700000000L
    private val QUERY = "aid=8662&version_code=73532"
    private val BODY_EMPTY = byteArrayOf()
    private val BODY_NON_EMPTY = """{"item_id":"123","play_delta":1}""".toByteArray()

    // Expected values from Go implementation (provider_hongguo_sign.go)
    private val EXPECTED_GORGON_EMPTY = "8404401c0000c29547edcc232d3d7022103fad22c9d970977eb7"
    private val EXPECTED_GORGON_NON_EMPTY = "8404401c0000c295471987e0a7167022103fad22c9d970977eb7"
    private val EXPECTED_KHRONOS = "1700000000"
    private val EXPECTED_PREFIX = "8404401c0000"
    private val EXPECTED_LEN = 52

    @Test
    fun testEmptyBody_matchesGo() {
        val (gorgon, khronos, _) = HongguoSign.sign(QUERY, BODY_EMPTY, FIXED_TS)

        assertEquals("X-Gorgon must match Go byte-for-byte", EXPECTED_GORGON_EMPTY, gorgon)
        assertEquals("X-Khronos must match Go", EXPECTED_KHRONOS, khronos)
        assertEquals("X-Gorgon length must be 52 chars (26 bytes hex)", EXPECTED_LEN, gorgon.length)
        assertTrue("X-Gorgon must start with prefix 8404401c0000", gorgon.startsWith(EXPECTED_PREFIX))
    }

    @Test
    fun testNonEmptyBody_matchesGo() {
        val (gorgon, khronos, _) = HongguoSign.sign(QUERY, BODY_NON_EMPTY, FIXED_TS)

        assertEquals("X-Gorgon must match Go byte-for-byte", EXPECTED_GORGON_NON_EMPTY, gorgon)
        assertEquals("X-Khronos must match Go", EXPECTED_KHRONOS, khronos)
        assertEquals("X-Gorgon length must be 52 chars (26 bytes hex)", EXPECTED_LEN, gorgon.length)
        assertTrue("X-Gorgon must start with prefix 8404401c0000", gorgon.startsWith(EXPECTED_PREFIX))
    }

    @Test
    fun testStub_matchesGo() {
        // Go: fmt.Sprintf("%X", bodyHash) -> 大写 hex
        val body = """{"item_id":"123","play_delta":1}""".toByteArray()
        val stub = HongguoSign.stub(body)

        assertEquals("2F20C14DB2D89D8A7E3BA75476B01967", stub)
    }
}