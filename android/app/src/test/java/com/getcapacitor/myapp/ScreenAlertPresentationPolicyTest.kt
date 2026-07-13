package com.getcapacitor.myapp

import com.randomwaterreminder.app.ReminderType
import com.randomwaterreminder.app.ScreenAlertPresentationPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAlertPresentationPolicyTest {
    @Test fun screenOverlayKeepsThePreviouslyUsedAppInFront() {
        assertFalse(ScreenAlertPresentationPolicy.shouldLaunchCenterActivity(ReminderType.SCREEN_LIMIT, overlayShown = true))
    }

    @Test fun screenActivityRemainsAvailableWhenOverlayCannotBeShown() {
        assertTrue(ScreenAlertPresentationPolicy.shouldLaunchCenterActivity(ReminderType.SCREEN_LIMIT, overlayShown = false))
    }

    @Test fun waterCheckInStillOpensInsideTheApp() {
        assertTrue(ScreenAlertPresentationPolicy.shouldLaunchCenterActivity(ReminderType.WATER, overlayShown = true))
    }
}
