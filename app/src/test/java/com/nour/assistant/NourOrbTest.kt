package com.nour.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.nour.assistant.ui.NourOrbView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NourOrbTest {
    private fun render(mode: NourOrbView.Mode, level: Float): Bitmap {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val v = NourOrbView(ctx).apply { animated = false; this.mode = mode; this.level = level }
        v.measure(android.view.View.MeasureSpec.makeMeasureSpec(200, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(200, android.view.View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 200, 200)
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return bmp
    }

    @Test fun drawsSomethingInEveryMode() {
        for (m in NourOrbView.Mode.values()) {
            val b = render(m, 0.8f)
            var painted = 0
            for (x in 0 until 200 step 5) for (y in 0 until 200 step 5) if ((b.getPixel(x, y) ushr 24) > 0) painted++
            assertTrue("mode $m drew nothing", painted > 100)
        }
    }
}
