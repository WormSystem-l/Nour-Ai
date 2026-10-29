package com.nour.assistant.tools

/** نتيجة بحث: نص مصغّر للموديل + مصادر منظّمة + اسم المحرك اللي جابها */
class SearchOut(val text: String, val sources: List<Source>, val engine: String = "")

interface SearchEngine {
    val id: String
    /** بيرمي Exception لو فشل أو رجّع نتيجة فاضية */
    fun search(query: String, max: Int): SearchOut
}

/**
 * سلسلة محركات بحث: لو واحد وقع بنكمّل بالتاني. مع:
 *  - قاطع: محرك فشل مرتين ورا بعض بيتخطّى [blockMs] (عشان ماننتظرش محرك واقع كل مرة)
 *  - كاش: نفس السؤال خلال [cacheMs] مايتبحثش تاني
 */
class SearchChain(
    private val engines: List<SearchEngine>,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val blockMs: Long = 10 * 60_000,
    private val cacheMs: Long = 10 * 60_000,
    private val failuresToBlock: Int = 2
) {
    class Result(val out: SearchOut?, val errors: List<String>)

    private class State(var fails: Int = 0, var blockedUntil: Long = 0)
    private val state = HashMap<String, State>()
    private val cache = object : LinkedHashMap<String, Pair<Long, SearchOut>>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<Long, SearchOut>>?) = size > 40
    }

    @Synchronized private fun st(id: String) = state.getOrPut(id) { State() }

    fun search(queryRaw: String, max: Int = 5): Result {
        val q = queryRaw.trim()
        val key = q.lowercase()
        synchronized(cache) { cache[key]?.let { (t, o) -> if (now() - t < cacheMs) return Result(o, emptyList()) } }
        val errors = ArrayList<String>()
        for (e in engines) {
            val s = st(e.id)
            if (now() < s.blockedUntil) { errors += "${e.id}: skipped (recent failures)"; continue }
            try {
                val o = e.search(q, max)
                if (o.text.isBlank()) throw IllegalStateException("empty result")
                synchronized(this) { s.fails = 0 }
                val tagged = SearchOut(o.text, o.sources.map { if (it.via.isEmpty()) it.copy(via = e.id) else it }, e.id)
                synchronized(cache) { cache[key] = now() to tagged }
                return Result(tagged, errors)
            } catch (t: Throwable) {
                errors += "${e.id}: ${t.javaClass.simpleName} ${t.message?.take(80).orEmpty()}".trim()
                synchronized(this) { s.fails++; if (s.fails >= failuresToBlock) { s.blockedUntil = now() + blockMs; s.fails = 0 } }
            }
        }
        return Result(null, errors)
    }

    /** لو كل المحركات اتخطّت بسبب القاطع، نفكّه (المستخدم بيحاول تاني) */
    @Synchronized fun resetBreakers() { state.values.forEach { it.blockedUntil = 0; it.fails = 0 } }
}

/**
 * قرار البحث من الجهاز: أسئلة بتتغير مع الوقت (أسعار، أخبار، نتايج، طقس، آخر/أحدث...) لازم تتبحث
 * حتى لو الموديل اتردد. بيتحط في تعليمات الموديل ("ابحث الأول").
 */
object SearchIntent {
    private val AR = Regex("(اليوم|دلوقتي|دلوقت|الآن|حالياً|حاليا|آخر|اخر|أحدث|احدث|سعر|أسعار|اسعار|بكام|كام سعر|طقس|الجو|درجة الحرارة|أخبار|اخبار|خبر|نتيجة|نتايج|مباراة|ماتش|ترتيب|الدوري|مين فاز|مين كسب|عاجل|ترند|سعر الدولار|سعر الذهب|2025|2026|2027)")
    private val EN = Regex("(?i)\\b(today|tonight|now|currently|latest|newest|recent|news|price|prices|cost of|weather|forecast|score|scores|result|results|standings|who won|breaking|trending|stock|exchange rate|202[5-9])\\b")

    fun needsWeb(text: String): Boolean {
        val t = text.trim()
        if (t.length < 4) return false
        return AR.containsMatchIn(t) || EN.containsMatchIn(t)
    }
}
