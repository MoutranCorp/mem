package com.moutrancorp.memspike

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadedVideoSearchRegressionTest {
    @Test
    fun downloadedYoutubeSearchResultDoesNotCrash() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("mem_seed", "downloaded_youtube_search_crash")

        ActivityScenario.launch<MainActivity>(intent).use {
            val resultAppeared = device.wait(
                Until.hasObject(By.textContains("Downloaded YouTube search crash fixture")),
                12_000,
            )
            assertTrue(
                "Seeded downloaded YouTube result did not render. If the app crashed, check files/last_crash.txt or logcat.",
                resultAppeared,
            )
        }
    }
}
