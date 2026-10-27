package com.nour.assistant.tools

/** صياغة طلبات «اسأل عن الصفحة» (عربي/إنجليزي) — منطق نقي */
object PagePrompts {
    enum class Mode { ASK, SUMMARIZE, TRANSLATE }

    /** النص اللي يظهر للمستخدم كسؤاله */
    fun visible(mode: Mode, typed: String, ar: Boolean): String = when {
        typed.isNotBlank() -> typed.trim()
        mode == Mode.SUMMARIZE -> if (ar) "لخّص الصفحة" else "Summarize this page"
        mode == Mode.TRANSLATE -> if (ar) "ترجم الصفحة" else "Translate this page"
        else -> if (ar) "إيه الصفحة دي؟" else "What is this page?"
    }

    private fun instruction(mode: Mode, typed: String, ar: Boolean): String = when {
        typed.isNotBlank() -> typed.trim()
        mode == Mode.SUMMARIZE -> if (ar) "لخّص الصفحة دي في نقاط واضحة ومختصرة، وابدأ بسطر يقول الصفحة عن إيه." else "Summarize this page in clear, concise bullet points, starting with one line saying what it is about."
        mode == Mode.TRANSLATE -> if (ar) "ترجم المحتوى الأساسي للصفحة للعربية بنفس الترتيب، وسيب الأسماء والمصطلحات التقنية زي ما هي." else "Translate the main content of this page to English, keeping the order; keep names and technical terms."
        else -> if (ar) "اشرحلي الصفحة دي باختصار: إيه هي؟ ولو فيها كلام بلغة تانية ترجم المهم منه للعربي." else "Briefly explain this page: what is it? If it has text in another language, translate the important parts."
    }

    /** الرسالة اللي بتتبعت للموديل: التعليمات + محتوى الصفحة (مرة واحدة، مش بتتخزن في المحادثة) */
    fun build(mode: Mode, typed: String, pageBlock: String, ar: Boolean): String {
        val head = instruction(mode, typed, ar)
        if (pageBlock.isBlank()) return head
        val tag = if (ar) "محتوى الصفحة اللي كان المستخدم فاتحها" else "Content of the web page the user was viewing"
        return "$head\n\n[$tag]\n$pageBlock"
    }
}

/** جلب نص الصفحة كاملة من رابطها، والرجوع للنص الظاهر لو الصفحة محمية أو بتتولّد بجافاسكريبت */
object PageFetcher {
    const val FULL_BUDGET = 14000
    const val MIN_USEFUL = 600

    /** لازم تتنادى على Thread خلفي */
    fun load(url: String?, focus: String?): String {
        val visible = ScreenContext.forModel(9000)
        if (url.isNullOrBlank()) return visible
        val o = try { WebTools.readPageFull(url, focus?.takeIf { it.isNotBlank() }, FULL_BUDGET) } catch (_: Exception) { null }
        return if (o != null && o.ok && o.text.length >= MIN_USEFUL) o.text else visible
    }
}
