package com.nithin.nightcast

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Feeds a recorded H.264 clip through [VideoPlayer] the way the native side does: one Annex-B
 * access unit per call, the first one led by SPS and PPS. The clip is 590x1278, a size that is
 * not a multiple of 16, so the SPS carries cropping like a mirrored portrait iPhone does.
 */
@RunWith(AndroidJUnit4::class)
class VideoPlayerTest {

    private val clip: ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open("portrait_590x1278.h264").readBytes()

    @Test
    fun readsPictureSizeFromSps() {
        val first = accessUnits(clip).first()
        val config = H264.leadingConfig(first, first.size)
        assertNotNull(config)
        assertEquals(590 to 1278, H264.pictureSize(config!!.sps))
    }

    @Test
    fun ordinaryFrameHasNoConfig() {
        val second = accessUnits(clip)[1]
        assertNull(H264.leadingConfig(second, second.size))
    }

    @Test
    fun decodesClipOntoSurface() {
        val sized = CountDownLatch(1)
        var size = 0 to 0
        val player = VideoPlayer(onSize = { width, height ->
            size = width to height
            sized.countDown()
        })
        val drawn = AtomicInteger()
        val texture = SurfaceTexture(false)
        texture.setOnFrameAvailableListener { drawn.incrementAndGet() }
        val surface = Surface(texture)
        try {
            player.setSurface(surface)
            val units = accessUnits(clip)
            assertEquals(30, units.size)
            units.forEachIndexed { index, unit -> player.feed(unit, unit.size, index * 33_333_333L) }

            assertTrue("decoder never reported a size", sized.await(5, TimeUnit.SECONDS))
            assertEquals(590 to 1278, size)
            val deadline = System.currentTimeMillis() + 5000
            while (drawn.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue("no frame reached the surface", drawn.get() > 0)

            // Losing the screen must not stop the decoder: later frames are still accepted.
            player.setSurface(null)
            units.drop(1).forEachIndexed { index, unit -> player.feed(unit, unit.size, (30 + index) * 33_333_333L) }
        } finally {
            player.release()
            surface.release()
            texture.release()
        }
    }

    /** Splits an Annex-B stream into access units with 4-byte start codes, one picture each. */
    private fun accessUnits(stream: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 <= stream.size) {
            if (stream[i].toInt() == 0 && stream[i + 1].toInt() == 0 && stream[i + 2].toInt() == 1) {
                starts.add(i + 3)
                i += 3
            } else {
                i++
            }
        }
        val units = ArrayList<ByteArray>()
        var current = ByteArrayOutputStream()
        for ((n, start) in starts.withIndex()) {
            var end = if (n + 1 < starts.size) starts[n + 1] - 3 else stream.size
            if (n + 1 < starts.size && end > start && stream[end - 1].toInt() == 0) end--
            val type = stream[start].toInt() and 0x1f
            if (type == 6) continue // x264's SEI banner; iOS sends none here
            current.write(byteArrayOf(0, 0, 0, 1))
            current.write(stream, start, end - start)
            if (type == 1 || type == 5) {
                units.add(current.toByteArray())
                current = ByteArrayOutputStream()
            }
        }
        return units
    }
}
