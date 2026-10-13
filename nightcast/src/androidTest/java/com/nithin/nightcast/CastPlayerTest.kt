package com.nithin.nightcast

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Plays a short local clip through [CastPlayer] and checks what it reports back, since those
 * reports are what the phone's scrubber and its end-of-video handling run on.
 */
@RunWith(AndroidJUnit4::class)
class CastPlayerTest {

    @Volatile private var longest = 0.0
    @Volatile private var furthest = -1.0
    @Volatile private var sawPlaying = false

    @Test
    fun playsToTheEndAndReportsProgress() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val clip = File(instrumentation.targetContext.cacheDir, "clip.mp4")
        instrumentation.context.assets.open("clip_320x180.mp4").use { input ->
            clip.outputStream().use { input.copyTo(it) }
        }

        val finished = CountDownLatch(1)
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        lateinit var player: CastPlayer
        instrumentation.runOnMainSync {
            player = CastPlayer(
                instrumentation.targetContext,
                report = { duration, position, rate, _, _, done ->
                    longest = maxOf(longest, duration)
                    furthest = maxOf(furthest, position)
                    if (rate == 1f) sawPlaying = true
                    if (done) finished.countDown()
                },
                onChanged = {}
            )
            player.play("file://${clip.absolutePath}", 0f, surface)
        }
        try {
            assertTrue("never reported the end of the clip", finished.await(15, TimeUnit.SECONDS))
            assertEquals(2.0, longest, 0.2)
            assertTrue("position never advanced: $furthest", furthest > 1.0)
            assertTrue(sawPlaying)
            instrumentation.runOnMainSync {
                assertEquals(320 to 180, player.width to player.height)
                player.stop()
                assertFalse(player.active)
            }
        } finally {
            surface.release()
            texture.release()
        }
    }
}
