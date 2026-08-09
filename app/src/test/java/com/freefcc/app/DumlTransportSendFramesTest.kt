package com.freefcc.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers DumlTransport.allFramesSucceeded() — the aggregation rule for
 * sendFrames(): a non-empty send series reports success only if EVERY write
 * succeeded, and an empty series is always a failure. This is what prevents
 * the app from reporting "FCC enabled" when the link dropped partway through
 * the 21-frame x 2-round apply (the old "any success = true" behaviour).
 */
class DumlTransportSendFramesTest {

    @Test
    fun allWritesSucceed_reportsSuccess() {
        assertTrue(DumlTransport.allFramesSucceeded(listOf(true, true, true)))
    }

    @Test
    fun singleWriteSucceeds_reportsSuccess() {
        assertTrue(DumlTransport.allFramesSucceeded(listOf(true)))
    }

    @Test
    fun oneWriteFails_reportsFailure() {
        // The exact scenario from the bug: link drops after the first write.
        assertFalse(DumlTransport.allFramesSucceeded(listOf(true, false, true)))
    }

    @Test
    fun onlyFirstWriteSucceeds_reportsFailure() {
        assertFalse(DumlTransport.allFramesSucceeded(listOf(true, false, false)))
    }

    @Test
    fun allWritesFail_reportsFailure() {
        assertFalse(DumlTransport.allFramesSucceeded(listOf(false, false)))
    }

    @Test
    fun emptySeries_reportsFailure() {
        assertFalse(DumlTransport.allFramesSucceeded(emptyList()))
    }
}
