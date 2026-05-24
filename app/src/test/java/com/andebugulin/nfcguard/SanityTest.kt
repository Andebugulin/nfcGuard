package com.andebugulin.nfcguard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Smoke test proving the unit-test harness is wired up.
 * Real characterization tests follow in Phase 0.2 and 0.3.
 */
class SanityTest {

    @Test
    fun harness_runs() {
        assertEquals(4, 2 + 2)
    }
}
