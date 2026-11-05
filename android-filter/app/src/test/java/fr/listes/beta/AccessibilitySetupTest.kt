package fr.listes.beta

import org.junit.Assert.*
import org.junit.Test

class AccessibilitySetupTest {
    @Test fun unrelatedAccessibilityServiceIsNotEnough() {
        assertFalse(AccessibilityHealth(true,false,false,true,true).ready)
    }
    @Test fun enabledButUnboundIsNotReady() {
        val h=AccessibilityHealth(true,true,false,true,true)
        assertEquals("waiting_for_service",h.code);assertFalse(h.ready)
    }
    @Test fun connectedAloneIsNotPermission() {
        assertFalse(AccessibilityHealth(connected=true).ready)
    }
    @Test fun registeredEnabledAndBoundRequired() {
        assertTrue(AccessibilityHealth(true,true,true,true,true).ready)
    }
    @Test fun missingReadCapabilityBlocksWork() {
        val h=AccessibilityHealth(true,true,true,false,true)
        assertFalse(h.ready);assertEquals("read_capability_missing",h.code)
    }
    @Test fun missingServiceIsNotDisabledService() {
        assertEquals("service_not_found",AccessibilityHealth(inspected=true).code)
        assertEquals("permission_required",AccessibilityHealth(registered=true,inspected=true).code)
    }
    @Test fun targetedSettingsContainOurService() {
        val target="fr.listes.beta/fr.listes.beta.LocalService"
        val routes=SetupRoutes.service(target)
        assertEquals(SetupRoutes.DETAILS,routes[0].action);assertEquals(target,routes[0].component)
        assertEquals("android.settings.ACCESSIBILITY_SETTINGS",routes[1].action)
    }
    @Test fun unsupportedDetailsFallsBackWithoutCrashing() {
        val tried=mutableListOf<String>()
        val success=SetupRoutes.launch(SetupRoutes.service("test/service")) { r ->
            tried+=r.action;if(tried.size==1) throw IllegalStateException("unsupported");true
        }
        assertTrue(success);assertEquals(2,tried.size)
    }
    @Test fun successfulDetailsDoesNotOpenMoreScreens() {
        var launches=0
        assertTrue(SetupRoutes.launch(SetupRoutes.service("test/service")) { launches++;true })
        assertEquals(1,launches)
    }
    @Test fun allSettingsMissingReturnsFailure() {
        assertFalse(SetupRoutes.launch(SetupRoutes.service("test/service")) { throw IllegalStateException() })
    }
    @Test fun appInfoTargetsOnlyOurPackage() {
        val route=SetupRoutes.app("fr.listes.beta").single()
        assertEquals("package:fr.listes.beta",route.packageUri)
        assertEquals("android.settings.APPLICATION_DETAILS_SETTINGS",route.action)
    }
}
