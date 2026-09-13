package com.fairylive.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Baseline unit test to verify that the JVM testing pipeline is active and healthy.
 */
class BaselineUnitTest {

    @Test
    fun testPackageConfiguration() {
        val expectedPackage = "com.fairylive.app"
        assertEquals("com.fairylive.app", expectedPackage)
    }

    @Test
    fun testEnvironmentSanity() {
        assertTrue("JVM environment should be functional", System.currentTimeMillis() > 0)
    }
}
