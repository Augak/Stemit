package com.stemit.app

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StemitNavigationSmokeTest {
    @Test
    fun homeLibraryAndSettingsAreRealNavigableScreens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("stemit", 0).edit().clear().commit()

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull("Stemit launcher activity must be declared", launchIntent)
        launchIntent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)

        val device = UiDevice.getInstance(instrumentation)
        assertTrue("Welcome screen should appear", device.wait(Until.hasObject(By.text("Continue")), 8_000))
        device.findObject(By.text("Continue")).click()

        assertTrue(
            "Home must present the actual separation workflow",
            device.wait(Until.hasObject(By.text("Separate your track")), 5_000)
        )
        assertTrue("Audio picker action must be visible", device.hasObject(By.text("Choose audio file")))
        assertTrue("Separation action must be visible", device.hasObject(By.text("Separate audio")))

        device.findObject(By.text("Library")).click()
        assertTrue("Library screen must render", device.wait(Until.hasObject(By.text("Library")), 3_000))
        assertTrue("Empty library state must explain the next step", device.hasObject(By.text("No stems yet")))

        device.findObject(By.text("Settings")).click()
        assertTrue("Settings must render", device.wait(Until.hasObject(By.text("Model and storage")), 3_000))
        assertTrue("Settings must expose privacy information", device.hasObject(By.text("Privacy")))

        device.findObject(By.text("Home")).click()
        assertTrue("Home navigation must return to the separation screen", device.wait(Until.hasObject(By.text("Separate your track")), 3_000))
    }
}
