package com.antigravity.client

import org.junit.Assert.*
import org.junit.Test

class DeduplicationTest {

    @Test
    fun testMonotonicSeqDeduplication() {
        val seenSeqs = mutableSetOf<Long>()
        val receivedEvents = listOf(
            100L, 101L, 102L, 103L, // first batch
            102L, 103L, 104L, 105L  // second batch with duplicate 102, 103
        )

        val processed = mutableListOf<Long>()
        for (seq in receivedEvents) {
            if (seenSeqs.add(seq)) {
                processed.add(seq)
            }
        }

        assertEquals(listOf(100L, 101L, 102L, 103L, 104L, 105L), processed)
        assertEquals(6, processed.size)
    }

    @Test
    fun testReconnectExponentialBackoffCalculation() {
        fun calculateDelay(attempt: Int): Long {
            return when (attempt) {
                0 -> 1L
                1 -> 2L
                2 -> 4L
                3 -> 8L
                4 -> 16L
                else -> 30L
            }
        }

        assertEquals(1L, calculateDelay(0))
        assertEquals(2L, calculateDelay(1))
        assertEquals(4L, calculateDelay(2))
        assertEquals(8L, calculateDelay(3))
        assertEquals(16L, calculateDelay(4))
        assertEquals(30L, calculateDelay(5))
        assertEquals(30L, calculateDelay(100))
    }
}
