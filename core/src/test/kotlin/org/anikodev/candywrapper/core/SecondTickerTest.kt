package org.anikodev.candywrapper.core

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.anikodev.candywrapper.core.timing.SecondTicker
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SecondTickerTest {
    @Test
    fun ticksEachSecondWhileHeldAndStopsWhenNothingIsWorking() = runTest {
        val ticks = ArrayList<Long>()
        val ticker = SecondTicker(backgroundScope, { 1_000_000L + testScheduler.currentTime }) { ticks += it }
        assertFalse(ticker.running)

        // Two working labels on screen share one ticker.
        ticker.acquire()
        ticker.acquire()
        runCurrent()
        assertEquals(listOf(1_000_000L), ticks)
        advanceTimeBy(3_001)
        assertEquals(listOf(1_000_000L, 1_001_000L, 1_002_000L, 1_003_000L), ticks)

        // One label leaves; the other still ticks.
        ticker.release()
        advanceTimeBy(1_000)
        assertEquals(5, ticks.size)

        // The last working label leaves: no more ticks, however long the screen stays idle.
        ticker.release()
        assertFalse(ticker.running)
        advanceTimeBy(60_000)
        assertEquals(5, ticks.size)

        // A stray release does not go negative, and a new label starts it again.
        ticker.release()
        ticker.acquire()
        assertTrue(ticker.running)
        runCurrent()
        assertEquals(6, ticks.size)
        ticker.release()
    }

    @Test
    fun firstTickLandsOnTheNextWholeSecond() = runTest {
        val ticks = ArrayList<Long>()
        val ticker = SecondTicker(backgroundScope, { 5_400L + testScheduler.currentTime }) { ticks += it }
        ticker.acquire()
        advanceTimeBy(599)
        assertEquals(listOf(5_000L), ticks)
        advanceTimeBy(2)
        assertEquals(listOf(5_000L, 6_000L), ticks)
        ticker.release()
    }
}
