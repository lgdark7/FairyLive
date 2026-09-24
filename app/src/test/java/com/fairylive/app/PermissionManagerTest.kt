package com.fairylive.app

import com.fairylive.app.core.permission.PermissionManager
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionManagerTest {

    @Test
    fun testRequiredPermissionsContainAudioAndCamera() {
        val perms = PermissionManager.REQUIRED_PERMISSIONS
        assertTrue(perms.contains(android.Manifest.permission.RECORD_AUDIO))
        assertTrue(perms.contains(android.Manifest.permission.CAMERA))
    }

    @Test
    fun testOemBrandResolutionReturnsNonEmptyString() {
        val brand = PermissionManager.getOemBrand()
        assertNotNull(brand)
        assertTrue(brand.isNotBlank())
    }

    @Test
    fun testOemRestrictedDeviceCheck() {
        val isRestricted = PermissionManager.isOemRestrictedDevice()
        assertNotNull(isRestricted)
    }
}
