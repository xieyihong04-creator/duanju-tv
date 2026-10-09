package com.duanju.tv.data

import com.duanju.tv.data.model.Drama
import com.duanju.tv.data.model.Episode
import com.duanju.tv.data.model.PlayGroup
import com.duanju.tv.data.remote.HotRanker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 热度算法单测。
 *
 * CMS 不提供可用的播放量，排序完全依赖这里的本地打分，
 * 因此必须锁住几条可预期的性质：新的优先、集数多的优先、完结加分、时间解析可用。
 */
class HotRankerTest {

    private fun drama(
        id: Int,
        updated: String,
        episodes: Int,
        remarks: String = "",
    ): Drama = Drama(
        id = id,
        sourceId = "test",
        name = "剧$id",
        pic = "",
        type = "短剧",
        typeId = 1,
        remarks = remarks,
        year = "2026",
        area = "大陆",
        director = "",
        actors = "",
        blurb = "",
        detail = "",
        tag = "",
        score = "",
        updated = updated,
        playGroups = listOf(
            PlayGroup(
                "line",
                (1..episodes).map { Episode(it, "第${it}集", "https://a.com/$it.m3u8") },
            ),
        ),
    )

    private val now = System.currentTimeMillis() / 1000

    private fun stamp(daysAgo: Int): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, -daysAgo)
        return "%04d-%02d-%02d %02d:%02d:%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            12, 0, 0,
        )
    }

    @Test
    fun parseTimeReadsDateTime() {
        val epoch = HotRanker.parseTime("2026-03-04 05:06:07")
        assertTrue(epoch != null && epoch > 0)
        assertNull(HotRanker.parseTime(""))
    }

    @Test
    fun newerDramaScoresHigher() {
        val fresh = drama(1, stamp(0), 20)
        val stale = drama(2, stamp(60), 20)
        assertTrue(HotRanker.score(fresh, now) > HotRanker.score(stale, now))
    }

    @Test
    fun moreEpisodesScoreHigher() {
        val long = drama(1, stamp(1), 80)
        val short = drama(2, stamp(1), 8)
        assertTrue(HotRanker.score(long, now) > HotRanker.score(short, now))
    }

    @Test
    fun completedBonusApplied() {
        val done = drama(1, stamp(1), 20, remarks = "已完结")
        val ongoing = drama(2, stamp(1), 20, remarks = "更新至20集")
        assertTrue(HotRanker.score(done, now) > HotRanker.score(ongoing, now))
    }

    @Test
    fun rankPutsHotFirstAndTopLimitsSize() {
        val items = listOf(
            drama(1, stamp(40), 10),
            drama(2, stamp(0), 60, remarks = "完结"),
            drama(3, stamp(5), 30),
        )
        val ranked = HotRanker.rank(items, now)
        assertEquals(2, ranked.first().id)
        assertEquals(1, ranked.last().id)
        assertEquals(2, HotRanker.top(items, 2, now).size)
    }

    @Test
    fun missingTimeFallsBackToIdOrder() {
        val a = drama(500, "", 10)
        val b = drama(100, "", 10)
        // 没有更新时间时用 id 造序，保证排序结果稳定而不是全 0
        assertTrue(HotRanker.score(a, now) > HotRanker.score(b, now))
    }
}
