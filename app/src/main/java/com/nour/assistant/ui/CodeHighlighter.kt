package com.nour.assistant.ui

/** تلوين كود خفيف (بدون مكتبات): تعليقات / نصوص / أرقام / كلمات مفتاحية — تقريبي بس مفيد لأغلب اللغات */
object CodeHighlighter {
    enum class Kind { COMMENT, STRING, NUMBER, KEYWORD }
    class Span(val start: Int, val end: Int, val kind: Kind)

    private val KEYWORDS = setOf(
        "fun", "val", "var", "class", "object", "interface", "override", "when", "is", "in", "return", "if", "else", "for", "while", "do",
        "break", "continue", "import", "package", "from", "as", "def", "lambda", "pass", "with", "yield", "async", "await", "try", "catch",
        "except", "finally", "throw", "raise", "new", "this", "self", "super", "function", "const", "let", "export", "default", "public",
        "private", "protected", "static", "final", "void", "int", "long", "double", "float", "boolean", "string", "char", "struct", "enum",
        "true", "false", "null", "nil", "none", "True", "False", "None", "and", "or", "not", "SELECT", "FROM", "WHERE", "INSERT", "UPDATE",
        "DELETE", "JOIN", "CREATE", "TABLE", "echo", "fi", "then", "elif", "done"
    )

    private val TOKEN = Regex(
        "(?<c>//[^\\n]*|#[^\\n]*|/\\*[\\s\\S]*?(?:\\*/|\\z)|--[^\\n]*)" +
            "|(?<s>\"\"\"[\\s\\S]*?(?:\"\"\"|\\z)|\"(?:\\\\.|[^\"\\\\\\n])*\"?|'(?:\\\\.|[^'\\\\\\n])*'?|`[^`]*`?)" +
            "|(?<n>\\b\\d+(?:\\.\\d+)?\\b)" +
            "|(?<k>\\b[A-Za-z_][A-Za-z_0-9]*\\b)"
    )

    fun highlight(code: String, lang: String = ""): List<Span> {
        if (code.isEmpty() || code.length > 8000) return emptyList()
        val hashComments = lang.lowercase() in setOf("python", "py", "bash", "sh", "shell", "yaml", "yml", "ruby", "rb", "toml", "zsh", "")
        val out = ArrayList<Span>()
        for (m in TOKEN.findAll(code)) {
            val g = m.groups
            when {
                g["c"] != null -> {
                    val t = m.value
                    // # مش تعليق في كل اللغات (مثلاً C# / CSS colors)، بنقبله بس للغات اللي بتستخدمه أو لو لغة مش معروفة
                    if (t.startsWith("#") && !hashComments && lang.isNotEmpty()) continue
                    out += Span(m.range.first, m.range.last + 1, Kind.COMMENT)
                }
                g["s"] != null -> out += Span(m.range.first, m.range.last + 1, Kind.STRING)
                g["n"] != null -> out += Span(m.range.first, m.range.last + 1, Kind.NUMBER)
                g["k"] != null -> if (m.value in KEYWORDS) out += Span(m.range.first, m.range.last + 1, Kind.KEYWORD)
            }
        }
        return out
    }
}
