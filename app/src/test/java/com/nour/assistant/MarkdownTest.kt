package com.nour.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nour.assistant.ui.Markdown
import com.nour.assistant.ui.MdParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarkdownTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun stripsMarkersAndKeepsText() {
        val out = Markdown.render(ctx, "## عنوان\n**عريض** و *مائل*\n- بند", Markdown.DARK).toString()
        assertFalse(out.contains("**")); assertFalse(out.contains("##"))
        assertTrue(out.contains("عريض") && out.contains("• بند"))
    }

    @Test fun codeBlockGetsCopyLabel_andKeepsCode() {
        val out = Markdown.render(ctx, "كود:\n```kotlin\nval x = 1\n```", Markdown.DARK).toString()
        assertFalse(out.contains("```"))
        assertTrue(out.contains("val x = 1")); assertTrue(out.contains("kotlin"))
    }

    @Test fun plainTextUnchanged() {
        assertEquals("دي إجابة تجريبية", Markdown.render(ctx, "دي إجابة تجريبية", Markdown.DARK).toString())
    }

    @Test fun parser_unclosedFence_isStillCodeWhileStreaming() {
        val b = MdParser.parse("```py\nprint(1)")
        assertTrue(b.single() is MdParser.Block.Code && !(b.single() as MdParser.Block.Code).closed)
    }

    @Test fun markdownView_buildsTextAndCodeBlocks_incrementally() {
        val v = com.nour.assistant.ui.MarkdownView(ctx)
        v.setMarkdown("مقدمة\n```py\nprint(1)\n```\nخاتمة")
        assertEquals(3, v.childCount)
        assertTrue(v.getChildAt(1) is com.nour.assistant.ui.CodeBlockView)
        v.setMarkdown("مقدمة\n```py\nprint(1)\nprint(2)\n```\nخاتمة طويلة")
        assertEquals(3, v.childCount)
        v.setMarkdown("بس نص")
        assertEquals(1, v.childCount)
    }
}
