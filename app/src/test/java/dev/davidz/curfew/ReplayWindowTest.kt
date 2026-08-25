package dev.davidz.curfew

import dev.davidz.curfew.core.ReplayWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayWindowTest {

    @Test
    fun `a pair can only be spent once`() {
        val window = ReplayWindow()
        assertTrue(window.consume(100L, 15, 100L))
        assertFalse(window.consume(100L, 15, 100L))
    }

    /** Same step, different grant: a distinct message, and a distinct code. Not a replay. */
    @Test
    fun `the duration is part of the identity`() {
        val window = ReplayWindow()
        assertTrue(window.consume(100L, 15, 100L))
        assertTrue(window.consume(100L, 30, 100L))
        assertTrue(window.consume(100L, 60, 100L))
        assertFalse(window.consume(100L, 30, 100L))
    }

    /** The whole point: the screenshot on the shield is worthless inside its own window. */
    @Test
    fun `a spent pair stays refused for as long as its code could still verify`() {
        val window = ReplayWindow()
        window.consume(100L, 15, 100L)
        // One step later the code is still inside the skew window, and still refused.
        assertTrue(window.isSpent(100L, 15))
        assertFalse(window.consume(100L, 15, 101L))
    }

    @Test
    fun `old pairs are pruned once they cannot verify again`() {
        val window = ReplayWindow()
        window.consume(100L, 15, 100L)
        window.prune(200L)
        assertFalse(window.isSpent(100L, 15))
        assertTrue(window.keys().isEmpty())
    }

    @Test
    fun `survives a round trip through storage`() {
        val first = ReplayWindow()
        first.consume(500L, 60, 500L)
        val restored = ReplayWindow(first.keys())
        assertFalse(restored.consume(500L, 60, 500L))
    }

    /** A clock that jumps far enough could otherwise make every entry look current. */
    @Test
    fun `stays bounded`() {
        val window = ReplayWindow()
        for (counter in 0L until 500L) window.consume(counter, 15, counter)
        assertTrue(window.keys().size <= 64)
        // Whatever is dropped, the pairs near the current step are the ones kept.
        assertEquals(true, window.isSpent(499L, 15))
    }

    @Test
    fun `ignores malformed stored keys`() {
        val window = ReplayWindow(listOf("not-a-pair", "100:15"))
        window.prune(100L)
        assertEquals(setOf("100:15"), window.keys())
    }
}
