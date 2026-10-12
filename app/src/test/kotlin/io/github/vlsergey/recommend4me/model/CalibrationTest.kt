package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.interest.Interest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalibrationTest {

    @Test
    fun `the chances of scores drawn from a known logistic are found again`() {
        val random = Random(1)
        val z = DoubleArray(4000) { random.nextDouble(-3.0, 3.0) }
        // The truth: chance σ(1.5·z − 0.5)
        val positive = BooleanArray(z.size) { random.nextDouble() < 1 / (1 + kotlin.math.exp(-(1.5 * z[it] - 0.5))) }
        val c = Calibration.fit(z, positive)!!
        assertEquals(1.5, c.slope, 0.15)
        assertEquals(-0.5, c.intercept, 0.15)
        assertTrue(c.chance(2.0) > c.chance(0.0))
    }

    @Test
    fun `scores that tell the kinds apart perfectly are not taken for certainty`() {
        val z = doubleArrayOf(-2.0, -1.0, 1.0, 2.0)
        val c = Calibration.fit(z, booleanArrayOf(false, false, true, true))!!
        // Platt's targets: of two works of each kind no more than 3/4 and no less than 1/4 are known
        assertTrue(c.chance(2.0) < 0.95, "${c.chance(2.0)}")
        assertTrue(c.chance(-2.0) > 0.05, "${c.chance(-2.0)}")
    }

    @Test
    fun `one kind alone is nothing to calibrate on`() {
        assertNull(Calibration.fit(doubleArrayOf(0.0, 1.0), booleanArrayOf(true, true)))
    }

    @Test
    fun `the user's word decides the chance of interest, the model only without it`() {
        assertEquals(0.0, Interest.chance(false, 5, 0.9))
        assertEquals(1.0, Interest.chance(true, 1, 0.1))
        assertEquals(1.0, Interest.chance(null, 4, 0.1))
        // A low grade says nothing of interest
        assertEquals(0.3, Interest.chance(null, 3, 0.3))
        assertNull(Interest.chance(null, null, null))
    }
}
