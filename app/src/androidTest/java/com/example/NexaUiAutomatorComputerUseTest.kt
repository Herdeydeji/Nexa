package com.example

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Physical-device / live-emulator UI Automator + Compose verification harness.
 * Requires ADB + an attached device or Android emulator (cannot execute inside container).
 */
@RunWith(AndroidJUnit4::class)
class NexaUiAutomatorComputerUseTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesAndUiDeviceResponds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        assertTrue(device.displayWidth > 0 && device.displayHeight > 0)
        assertTrue(device.currentPackageName != null)
    }
}
